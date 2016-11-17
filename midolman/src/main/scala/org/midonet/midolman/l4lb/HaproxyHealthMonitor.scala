/*
 * Copyright 2016 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.l4lb

import java.io._
import java.nio.ByteBuffer
import java.nio.channels.IllegalSelectorException
import java.nio.channels.spi.SelectorProvider
import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.control.NonFatal

import akka.actor._

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.models.Topology.Pool.{PoolHealthMonitorMappingStatus => PoolHMMappingStatus}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.SequenceDispenser.OverlayTunnelKey
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, SequenceDispenser}
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.{CheckHealth, ConfigUpdate, _}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.netlink.{NetlinkSelectorProvider, UnixDomainChannel}
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.util.AfUnix
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.process.ProcessHelper

/**
 * Actor that represents the interaction with Haproxy for health monitoring.
 * Creating this actor is basically synonymous with creating a health monitor,
 * so we handle all of the setup in preStart, and all of the cleanup in
 * postStop. Other than that, the only options for interaction with this
 * actor is telling it to stop/start monitoring, and receiving updates.
 *
 * All operations that need to be performed atomically are done by
 * acquiring a ZooKeeper lock. This is to prevent races with the
 * cluster which is meant to be the only component writing to the
 * topology.
  *
 * TODO: the agent should never write to topology elements.  All code
 * violating this principle will be refactored out of here shortly (MNA-1068).
*/
object HaproxyHealthMonitor {
    def props(config: PoolConfig, manager: ActorRef, routerId: UUID,
              store: Storage, hostId: UUID, sequenceDispenser: SequenceDispenser):
        Props = Props(new HaproxyHealthMonitor(config, manager, routerId,
                                               store, hostId, sequenceDispenser))

    sealed trait HHMMessage
    // This is a way of alerting the manager that setup has failed
    case object SetupFailure extends HHMMessage
    // This is a way of alerting the manager that it was unable to get
    // health information from Haproxy
    case object SockReadFailure extends HHMMessage
    // Tells this actor that the config for the health monitor has changed.
    case class ConfigUpdate(conf: PoolConfig) extends HHMMessage
    // Tells this actor to poll haproxy once for health info.
    case object CheckHealth extends HHMMessage
    // Tells this actor that its router has been removed
    case object RouterRemoved
    // Tells this actor that it now has a router
    case class RouterAdded(newRouterId: UUID)

    // Constants for linking namespace to router port
    val NameSpaceIp = IPv4Addr.fromString("169.254.17.42")
    val RouterIp = IPv4Addr.fromString("169.254.17.41")
    val RouterMAC = MAC.random
    val NetSubnet = IPv4Subnet.fromCidr("169.254.17.40/30")
    val NameSpaceMAC = "0C:0C:0C:0C:0C:0C"

    // Constants used in parsing haproxy output
    val StatusPos = 17
    val NamePos = 1
    val Backend = "BACKEND"
    val Frontend = "FRONTEND"
    val StatusUp = "UP"
    val StatusDown = "DOWN"
    val FieldName = "svname"
    val ShowStat = "show stat\n"
}

class HaproxyHealthMonitor(var config: PoolConfig,
                           val manager: ActorRef,
                           var routerId: UUID,
                           val store: Storage,
                           val hostId: UUID,
                           val seqDispenser: SequenceDispenser)
    extends Actor with ActorLogWithoutPath with Stash {


    implicit def system: ActorSystem = context.system
    implicit def executor = system.dispatcher

    private var currentUpNodes = Set.empty[UUID]
    private var currentDownNodes = Set.empty[UUID]
    private val healthMonitorName = config.id.toString.substring(0,8) +
                                    config.nsPostFix
    private var routerPortId: UUID = null
    private var routeId: UUID = null
    private var namespaceName: String = null

    val ipCommand = HealthMonitor.ipCommand


    private def setPoolMappingStatus(poolId: UUID, status: PoolHMMappingStatus,
                                     rethrowException: Boolean = false)
    : Unit = {
        try {
            store.tryTransaction { tx =>
                val pool = tx.get(classOf[Pool], poolId)
                tx.update(pool.toBuilder.setMappingStatus(status).build())
            }
        } catch {
            case NonFatal(e) =>
                log.info("Unable to set the mapping status for pool: {} to: {}",
                         Array(poolId, status, e): _*)
                if (rethrowException) {
                    throw e
                }
        }
    }

    override def preStart(): Unit = {
        try {
            writeConf(config)
            namespaceName = createNamespace(healthMonitorName,
                                            config.vip.ip)
            hookNamespaceToRouter()
            restartHaproxy(healthMonitorName, config.haproxyConfFileLoc,
                           config.haproxyPidFileLoc)
            system.scheduler.scheduleOnce(1 second, self, CheckHealth)
            setPoolMappingStatus(config.id, ACTIVE, rethrowException = true)
        } catch {
            case NonFatal(e) =>
                log.error("Unable to create Health Monitor for " +
                          config.haproxyConfFileLoc + ": " + e.getMessage)
                setPoolMappingStatus(config.id, ERROR)
                manager ! SetupFailure
        }
    }

    override def postStop(): Unit = {
        unhookNamespaceFromRouter()
        HealthMonitor.cleanAndDeleteNamespace(healthMonitorName,
                                              config.nsPostFix,
                                              config.l4lbFileLocs)
        setPoolMappingStatus(config.id, INACTIVE)
    }

    def receive = {
        case ConfigUpdate(conf) =>
            try {
                writeConf(conf)
                if (conf.isConfigurable){
                    restartHaproxy(healthMonitorName,
                                   config.haproxyConfFileLoc,
                                   config.haproxyPidFileLoc)

                    // The vip may have changed. If so, we need to change the
                    // routes on the router.
                    store.tryTransaction { tx =>
                        if (config.vip != conf.vip) {
                            if (routeId != null) {
                                tx.delete(classOf[Route], routeId, ignoresNeo = true)
                                deleteIpTableRules(healthMonitorName,
                                                   config.vip.ip)
                            }
                            if (routerId != null && routerPortId != null) {
                                tx.create(createVipRoute(conf.vip.ip))
                                createIpTableRules(healthMonitorName,
                                                   conf.vip.ip)
                            }
                        }
                        val pool = tx.get(classOf[Pool], config.id)
                        tx.update(pool.toBuilder.setMappingStatus(ACTIVE).build())
                    }
                } else {
                    killHaproxyIfRunning(healthMonitorName,
                                         conf.haproxyConfFileLoc,
                                         conf.haproxyPidFileLoc)
                }
            } catch {
                case NonFatal(e) =>
                    log.info("Unable to update Health Monitor for " +
                              config.haproxyConfFileLoc)
                    setPoolMappingStatus(config.id, ERROR)
                    manager ! SetupFailure
            } finally {
                config = conf
            }

        case CheckHealth =>
            try {
                val statusInfo = getHaproxyStatus(config.haproxySockFileLoc)
                val (upNodes, downNodes) = parseResponse(statusInfo)
                val newUpNodes = upNodes diff currentUpNodes
                val newDownNodes = downNodes diff currentDownNodes

                setMembersStatus(newUpNodes, newDownNodes)
                currentUpNodes = upNodes
                currentDownNodes = downNodes
            } catch {
                case NonFatal(e) =>
                    log.info("Unable to retrieve health information for "
                              + config.haproxySockFileLoc)
                    setPoolMappingStatus(config.id, ERROR)
                    manager ! SockReadFailure
            }
            system.scheduler.scheduleOnce(1 second, self, CheckHealth)

        case RouterAdded(newRouterId) =>
            routerId = newRouterId
            try {
                hookNamespaceToRouter()
                setPoolMappingStatus(config.id, ACTIVE,
                                     rethrowException = true)
            } catch {
                case NonFatal(e) =>
                    log.info("Unable to add router: {}", routeId)
            }

        case RouterRemoved =>
            try {
                unhookNamespaceFromRouter()
                setPoolMappingStatus(config.id, INACTIVE,
                                     rethrowException = true)
                routerId = null
            } catch {
                case NonFatal(e) =>
                    log.info("Unable to remove router: {}", routerId)
            }
    }

    /**
      * Sets the health status of active and inactive pool members.
      */
    protected def setMembersStatus(activeMemberIds: Set[UUID],
                                   inactiveMemberIds: Set[UUID]):Unit = {

        store.tryTransaction { tx =>
            val upMembers = tx
                .getAll(classOf[PoolMember], activeMemberIds.toSeq)
            for (activeMember <- upMembers) {
                tx.update(activeMember.toBuilder.setStatus(LBStatus.ACTIVE).build)
            }

            val downMembers = tx
                .getAll(classOf[PoolMember], inactiveMemberIds.toSeq)
            for (inactiveMember <- downMembers) {
                tx.update(inactiveMember.toBuilder.setStatus(LBStatus.INACTIVE).build)
            }
        }
    }

    /*
     * Take the output from the haproxy response and turn it into a set
     * of UP member ids. Assumes the following:
     * 1) The names of the hosts are the id's of the members.
     * 2) The format is "show stat" response.
     * Example of a line of the output:
     * backend_id,name_of_server,0,0,0,0,,0,0,0,,0,,0,0,0,0,DOWN,1,1,0,0,1,
     * 2411,2411,,1,2,2,,0,,2,0,,0,L4CON,,1999,,,,,,,0,,,,0,0,
     */
    protected def parseResponse(resp: String): (Set[UUID], Set[UUID]) = {
        if (resp == null) {
            return (currentUpNodes, currentDownNodes)
        }

        val upNodes = new mutable.HashSet[UUID]()
        val downNodes = new mutable.HashSet[UUID]()

        def isMemberEntry(entry: Array[String]): Boolean =
            entry.length > StatusPos &&
            entry(NamePos) != Backend &&
            entry(NamePos) != Frontend &&
            entry(NamePos) != FieldName

        def isUp(entry: Array[String]): Boolean = {
            if (isMemberEntry(entry))
                entry(StatusPos) == StatusUp
            else
                false
        }

        def isDown(entry: Array[String]): Boolean = {
            if (isMemberEntry(entry))
                entry(StatusPos) == StatusDown
            else
                false
        }

        val entries = resp split "\n" map (_.split(","))
        entries foreach {
            case entry if isUp(entry) =>
                upNodes add UUID.fromString(entry(NamePos))
            case entry if isDown(entry) =>
                downNodes add UUID.fromString(entry(NamePos))
            case _ => // Nothing we care about
        }
        (upNodes.toSet, downNodes.toSet)
    }

    /* ======================================================================
     * BLOCKING CALLS: functions that may block, and therefore need to be
     * called from within a different execution context. These are mostly
     * IO operations.
     */
    def writeConf(config: PoolConfig): Unit = {
        val file = new File(config.haproxyConfFileLoc)
        file.getParentFile.mkdirs()
        val writer = new PrintWriter(file, "UTF-8")
        try {
            writer.print(config.generateConfigFile())
        } catch {
            case fnfe: FileNotFoundException =>
                log.error("FileNotFoundException while trying to write " +
                          config.haproxyConfFileLoc)
                setPoolMappingStatus(config.id, ERROR)
                throw fnfe
            case uee: UnsupportedEncodingException =>
                log.error("UnsupportedEncodingException while trying to " +
                          "write " + config.haproxyConfFileLoc)
                setPoolMappingStatus(config.id, ERROR)
                throw uee
        } finally {
            writer.close()
        }
    }

    def deleteIpTableRules(nsName: String, ip: String) = {
        modifyIpTableRules(nsName, ip, "delete")
    }

    def createIpTableRules(nsName: String, ip: String) = {
        modifyIpTableRules(nsName, ip, "append")
    }

    def modifyIpTableRules(nsName: String, ip: String, op: String) = {
        val iptablePost = "iptables --table nat --" + op + " POSTROUTING " +
            "--source " + NameSpaceIp + " --jump SNAT --to-source " + ip
        val iptablePre = "iptables --table nat --" + op +" PREROUTING" +
            " --destination " + ip + " --jump DNAT --to-destination " +
            NameSpaceIp
        ipCommand.execIn(nsName, iptablePre)
        ipCommand.execIn(nsName, iptablePost)
    }


    /*
     * Creates a new namespace and hooks up an interface.
     */
    def createNamespace(name: String, ip: String): String = {
        /*
         * TODO: We should not be calling ip commands directly from midolman
         * because it depends on on the ip package being installed. We will
         * later fix this to call the kernel utility directly.
         */
        val dp = name + "_dp"
        val ns = name + "_ns"
        try {
            ipCommand.ensureNamespace(name)
            ipCommand.link("add name " + dp + " type veth peer name " + ns)
            ipCommand.link("set " + dp + " up")
            ipCommand.link("set " + ns + " netns " + name)
            ipCommand.execIn(name, "ip link set " + ns + " address " +
                             NameSpaceMAC)
            ipCommand.execIn(name, "ip link set " + ns + " up")
            ipCommand.execIn(name, "ip address add " + NameSpaceIp +
                                   "/30 dev " + ns)
            ipCommand.execIn(name, "ip link set dev lo up")
            ipCommand.execIn(name, "route add default gateway " +
                                   RouterIp + " " + ns)
        } catch {
            case e: Exception =>
                HealthMonitor.cleanAndDeleteNamespace(name, config.nsPostFix,
                                                      config.l4lbFileLocs)
                log.error("Failed to create Namespace: ", e.getMessage)
                setPoolMappingStatus(config.id, ERROR)
                throw e
        }
        dp
    }

    def haproxyCommandLine = "haproxy -f " +
        config.haproxyConfFileLoc + " -p " + config.haproxyPidFileLoc

    def killHaproxyIfRunning(name: String, confFileLoc: String,
                             pidFileLoc: String) {
        HealthMonitor.getHaproxyPid(pidFileLoc) match {
            case Some(pid) =>
                HealthMonitor.killHaproxy(name, pid, pidFileLoc, confFileLoc)
            case None =>
        }
    }

    def startHaproxy(name: String) = ipCommand.execIn(name, haproxyCommandLine)

    /*
     * This will restart haproxy with the given config file.
     */
    def restartHaproxy(name: String, confFileLoc: String,
                       pidFileLoc: String) {
        killHaproxyIfRunning(name, confFileLoc, pidFileLoc)
        startHaproxy(name)
    }

    def makeChannel(): UnixDomainChannel = SelectorProvider.provider() match {
        case nl: NetlinkSelectorProvider =>
            nl.openUnixDomainSocketChannel(AfUnix.Type.SOCK_STREAM)
        case other =>
          log.error("Invalid selector type: {} => jdk-bootstrap shadowing " +
                    "may have failed ?", other.getClass)
          setPoolMappingStatus(config.id, ERROR)
          throw new IllegalSelectorException
    }

    /*
     * Asks the given socket for haproxy info. This creates a new channel
     * Everytime because Haproxy will close the connection after a write/read
     * If necessary, we can get around this by maintaining a connection
     * by operation in "command line mode" for haproxy.
     */
    def getHaproxyStatus(path: String) : String = {
        val socketFile = new File(path)
        val socketAddress = new AfUnix.Address(socketFile.getAbsolutePath)
        val chan = makeChannel()
        chan.connect(socketAddress)
        val wb = ByteBuffer.wrap(ShowStat.getBytes)
        while(wb.hasRemaining)
            chan.write(wb)
        val data = new StringBuilder
        val buf = ByteBuffer.allocate(1024)
        while (chan.read(buf) > 0) {
            data append new String(buf.array(), "ASCII")
            buf.clear()
        }
        chan.close()
        data.toString()
    }

    def createVipRoute(ip: String): Route = {
        routeId = UUID.randomUUID()
        val destIp = IPAddressUtil.toProto(ip)
        Route.newBuilder
            .setId(toProto(routeId))
            .setSrcSubnet(IPSubnetUtil.AnyIPv4Subnet)
            .setDstSubnet(IPSubnetUtil.fromAddress(destIp))
            .setWeight(100)
            .setNextHopGateway(IPAddressUtil.toProto(NameSpaceIp))
            .setNextHop(Route.NextHop.PORT)
            .setNextHopPortId(toProto(routerPortId))
            .build
    }

    def hookNamespaceToRouter(): Unit = {
        if (routerId == null)
            return
        store.tryTransaction { tx =>
            val host = tx.get(classOf[Host], toProto(hostId))
            val ports = tx.getAll(classOf[Port], host.getPortIdsList)

            for (p <- ports.filter(_.getInterfaceName == namespaceName)) {
                log.info("Deleting unused health monitor port " +
                         s"${fromProto(p.getId)} for pool ${config.id}")
                tx.delete(classOf[Port], p.getId, ignoresNeo = false)
            }

            val tk = seqDispenser.next(OverlayTunnelKey).await()

            val hmPort = Port.newBuilder()
                .setId(randomUuidProto)
                .setRouterId(toProto(routerId))
                .setPortAddress(IPAddressUtil.toProto(RouterIp))
                .setPortSubnet(IPSubnetUtil.toProto(NetSubnet))
                .setPortMac(RouterMAC.toString)
                .setHostId(toProto(hostId))
                .setTunnelKey(tk)
                .setInterfaceName(namespaceName).build
            tx.create(hmPort)

            routerPortId = fromProto(hmPort.getId)
            tx.create(createVipRoute(config.vip.ip))
        }
        createIpTableRules(healthMonitorName, config.vip.ip)
    }

    def unhookNamespaceFromRouter():Unit = {
        if (routerPortId != null) {
            // This should delete the route also
            store.tryTransaction { tx =>
                tx.delete(classOf[Port], routerPortId, ignoresNeo = true)
            }

            deleteIpTableRules(healthMonitorName, config.vip.ip)
            routeId = null
            routerPortId = null
        }
    }
}

class IP { /* wrapper to ip commands => TODO: implement with RTNETLINK */

    def exec(s: String) =
        ProcessHelper.executeCommandLine(s).returnValue

    def execNoErrors(s: String) =
        ProcessHelper.executeCommandLine(s, true).returnValue

    def execGetOutput(s: String) =
        ProcessHelper.executeCommandLine(s).consoleOutput

    def link(linkStr: String) = exec("ip link " + linkStr)

    def ifaceExists(s: String) = execNoErrors("ip link show " + s)

    def netns(s: String) = exec("ip netns " + s)

    def netnsGetOutput(s: String) = execGetOutput("ip netns " + s)

    def execIn(ns: String, cmd: String): Int =
        if (ns == "") exec(cmd) else netns("exec " + ns + " " + cmd)

    def addNS(ns: String) = netns("add " + ns)

    def deleteNS(ns: String) = netns("del " + ns)

    def namespaceExist(ns: String) =
        execGetOutput("ip netns list").exists(_.contains(ns))

    /** Create a network namespace with name "ns" if it does not already exist.
      *  Do not try to delete an old network namespace with same name, because
      *  trying to do so when there's a process still running or interfaces
      *  still using it, it can lead to namespace corruption.
      */
    def ensureNamespace(ns: String): Int =
        if (!namespaceExist(ns)) addNS(ns) else 0

    /** checks if an interface exists and deletes if it does */
    def ensureNoInterface(itf: String) =
        if (ifaceExists(itf) == 0) deleteItf(itf) else 0

    def deleteItf(itf: String) = link("delete " + itf)

    def interfaceExistsInNs(ns: String, interface: String): Boolean =
        if (!namespaceExist(ns)) false
        else netns("exec " + ns + " ip link show " + interface) == 0

    /** creates an interface and put a mirror in given network namespace */
    def preparePair(itf: String, mirror: String, ns: String) =
        link("add name " + itf + " type veth peer name " + mirror) |
            link("set " + mirror + " netns " + ns)

    /** wake up local interface with given name */
    def configureUp(itf: String, ns: String = "") =
        execIn(ns, "ip link set dev " + itf + " up")

    /** wake up local interface with given name */
    def configureDown(itf: String, ns: String = "") =
        execIn(ns, "ip link set dev " + itf + " down")

    /** Configure the mac address of given interface */
    def configureMac(itf: String, mac: String, ns: String = "") =
        execIn(ns, "ip link set dev " + itf + " up address " + mac)

    /** Configure the ip address of given interface */
    def configureIp(itf: String, ip: String, ns: String = "") =
        execIn(ns, "ip addr add " + ip + " dev " + itf)

}
