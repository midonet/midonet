/*
 * Copyright 2014 Midokura SARL
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

import akka.actor._

import java.io._
import java.nio.ByteBuffer
import java.nio.channels.IllegalSelectorException
import java.nio.channels.spi.SelectorProvider
import java.util.UUID

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.ports.RouterPort
import org.midonet.cluster.data.Route
import org.midonet.midolman.l4lb.HaproxyHealthMonitor._
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.CheckHealth
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.ConfigUpdate
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.IP
import org.midonet.midolman.layer3.Route.NextHop.PORT
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus
import org.midonet.midolman.state.l4lb.LBStatus
import LBStatus.{INACTIVE => MemberInactive}
import LBStatus.{ACTIVE => MemberActive}
import org.midonet.netlink.{NetlinkSelectorProvider, UnixDomainChannel}
import org.midonet.util.AfUnix

import scala.collection.JavaConversions._
import scala.collection.mutable.HashSet
import scala.collection.mutable.Set
import scala.concurrent.duration._


/**
 * Actor that represents the interaction with Haproxy for health monitoring.
 * Creating this actor is basically synonymous with creating a health monitor,
 * so we handle all of the setup in preStart, and all of the cleanup in
 * postStop. Other than that, the only options for interaction with this
 * actor is telling it to stop/start monitoring, and receiving updates.
 */
object HaproxyHealthMonitor {
    def props(config: PoolConfig, manager: ActorRef, routerId: UUID,
              dataClient: DataClient, hostId: UUID):
        Props = Props(new HaproxyHealthMonitor(config, manager, routerId,
                                               dataClient, hostId))

    sealed trait HHMMessage
    // This is a way of alerting the manager that setup has failed
    case object SetupFailure extends HHMMessage
    // This is a way of alerting the manager that it was unable to get
    // health information from Haproxy
    case object SockReadFailure extends HHMMessage
    // Tells this actor that the config for the health monitor has changed.
    case class ConfigUpdate(conf: PoolConfig) extends HHMMessage
    // Tells this actor to poll haproxy once for health info.
    private[HaproxyHealthMonitor] case object CheckHealth extends HHMMessage
    // Tells this actor that its router has been removed
    case object RouterRemoved
    // Tells this actor that it now has a router
    case class RouterAdded(newRouterId: UUID)

    // Constants for linking namespace to router port
    val NameSpaceIp = "169.254.17.45"
    val RouterIp = "169.254.17.44"
    val NetLen = 30
    val NetAddr = "169.254.17.43"
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
                           val dataClient: DataClient,
                           val hostId: UUID)
    extends Actor with ActorLogWithoutPath with Stash {
    implicit def system: ActorSystem = context.system
    implicit def executor = system.dispatcher

    private var currentUpNodes = Set[UUID]()
    private var currentDownNodes = Set[UUID]()
    private val healthMonitorName = config.id.toString.substring(0,8) +
                                    config.nsPostFix
    private var routerPortId: UUID = null
    private var routeId: UUID = null
    private var namespaceName: String = null

    override def preStart(): Unit = {
        try {
            writeConf(config)
            namespaceName = createNamespace(healthMonitorName,
                                                config.vip.ip)
            hookNamespaceToRouter()
            restartHaproxy(healthMonitorName, config.haproxyConfFileLoc,
                           config.haproxyPidFileLoc)
            system.scheduler.scheduleOnce(1 second, self, CheckHealth)
            setPoolMapStatus(PoolHealthMonitorMappingStatus.ACTIVE)
        } catch {
            case e: Exception =>
                log.error("Unable to create Health Monitor for " +
                          config.haproxyConfFileLoc + ": " + e.getMessage)
                setPoolMapStatus(PoolHealthMonitorMappingStatus.ERROR)
                manager ! SetupFailure
        }
    }

    override def postStop(): Unit = {
        unhookNamespaceFromRouter()
        HealthMonitor.cleanAndDeleteNamespace(healthMonitorName,
                                              config.nsPostFix,
                                              config.l4lbFileLocs)
        setPoolMapStatus(PoolHealthMonitorMappingStatus.INACTIVE)
    }

    def receive = {
        case ConfigUpdate(conf) =>
            try {
                writeConf(conf)
                if (conf.isConfigurable){
                    startHaproxy(healthMonitorName)
                } else {
                    killHaproxyIfRunning(healthMonitorName,
                                         conf.haproxyConfFileLoc,
                                         conf.haproxyPidFileLoc)
                }
                // The vip may have changed. If so, we need to change the
                // routes on the router.
                if (config.vip != conf.vip) {
                    if (routeId != null) {
                        dataClient.routesDelete(routeId)
                        deleteIpTableRules(healthMonitorName, config.vip.ip)
                    }
                    if (routerId != null && routerPortId != null) {
                        addVipRoute(conf.vip.ip)
                        createIpTableRules(healthMonitorName, conf.vip.ip)
                    }
                }
                setPoolMapStatus(PoolHealthMonitorMappingStatus.ACTIVE)
            } catch {
                case e: Exception =>
                    log.error("Unable to update Health Monitor for " +
                              config.haproxyConfFileLoc)
                    setPoolMapStatus(PoolHealthMonitorMappingStatus.ERROR)
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

                newUpNodes foreach (id =>
                    dataClient.poolMemberUpdateStatus(id, MemberActive))
                newDownNodes foreach (id =>
                    dataClient.poolMemberUpdateStatus(id, MemberInactive))

                currentUpNodes = upNodes
                currentDownNodes = downNodes
            } catch {
                case e: Exception =>
                    log.error("Unable to retrieve health information for "
                              + config.haproxySockFileLoc)
                    setPoolMapStatus(PoolHealthMonitorMappingStatus.ERROR)
                    manager ! SockReadFailure
            }
            system.scheduler.scheduleOnce(1 second, self, CheckHealth)

        case RouterAdded(newRouterId) =>
            routerId = newRouterId
            hookNamespaceToRouter()
            setPoolMapStatus(PoolHealthMonitorMappingStatus.ACTIVE)

        case RouterRemoved =>
            routerId = null
            unhookNamespaceFromRouter()
            setPoolMapStatus(PoolHealthMonitorMappingStatus.INACTIVE)
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
    private def parseResponse(resp: String): (Set[UUID], Set[UUID]) = {
        if (resp == null) {
            (currentUpNodes, currentDownNodes)
        }

        val upNodes = new HashSet[UUID]()
        val downNodes = new HashSet[UUID]()

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
        (upNodes, downNodes)
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
                setPoolMapStatus(PoolHealthMonitorMappingStatus.ERROR)
                throw fnfe
            case uee: UnsupportedEncodingException =>
                log.error("UnsupportedEncodingException while trying to " +
                          "write " + config.haproxyConfFileLoc)
                setPoolMapStatus(PoolHealthMonitorMappingStatus.ERROR)
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
        IP.execIn(nsName, iptablePre)
        IP.execIn(nsName, iptablePost)
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
            IP.ensureNamespace(name)
            IP.link("add name " + dp + " type veth peer name " + ns)
            IP.link("set " + dp + " up")
            IP.link("set " + ns + " netns " + name)
            IP.execIn(name, "ip link set " + ns + " address " + NameSpaceMAC)
            IP.execIn(name, "ip link set " + ns + " up")
            IP.execIn(name, "ip address add " + NameSpaceIp + "/24 dev " + ns)
            IP.execIn(name, "ip link set dev lo up")
            IP.execIn(name, "route add default gateway " + RouterIp + " " + ns)
        } catch {
            case e: Exception =>
                HealthMonitor.cleanAndDeleteNamespace(name, config.nsPostFix,
                                                      config.l4lbFileLocs)
                log.error("Failed to create Namespace: ", e.getMessage)
                setPoolMapStatus(PoolHealthMonitorMappingStatus.ERROR)
                throw e
        }
        dp
    }

    def haproxyCommandLineWithoutPid = "haproxy -f " +
        config.haproxyConfFileLoc + " -p " + config.haproxyPidFileLoc


    def haproxyCommandLine(): String = {
        HealthMonitor.getHaproxyPid(config.haproxyPidFileLoc) match {
            case Some(pid) => haproxyCommandLineWithoutPid + " -sf " + pid
            case None => haproxyCommandLineWithoutPid
        }
    }

    def killHaproxyIfRunning(name: String, confFileLoc: String,
                             pidFileLoc: String) {
        HealthMonitor.getHaproxyPid(pidFileLoc) match {
            case Some(pid) =>
                HealthMonitor.killHaproxy(name, pid, pidFileLoc, confFileLoc)
            case None =>
        }
    }

    def startHaproxy(name: String) = IP.execIn(name, haproxyCommandLine())

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
          setPoolMapStatus(PoolHealthMonitorMappingStatus.ERROR)
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

    def addVipRoute(ip: String) = {
        val route = new Route()
        route.setRouterId(routerId)
        route.setNextHop(PORT)
        route.setNextHopPort(routerPortId)
        route.setSrcNetworkAddr("0.0.0.0")
        route.setSrcNetworkLength(0)
        route.setDstNetworkAddr(ip)
        route.setDstNetworkLength(32)
        route.setNextHopGateway(NameSpaceIp)
        route.setWeight(100)
        routeId = dataClient.routesCreateEphemeral(route)
    }

    def hookNamespaceToRouter(): Unit = {
        if (routerId == null)
            return
        val ports = dataClient.portsFindByRouter(routerId)
        var portId: UUID = null

        // see if the port already exists, and delete it if it does. This can
        // happen if there was already an haproxy attached to this router.
        for (port <- ports) {
            port match {
                case rpc: RouterPort =>
                    if (rpc.getPortAddr == RouterIp) {
                        dataClient.hostsDelVrnPortMapping(hostId, rpc.getId)
                        portId = rpc.getId
                    }
            }
        }

        if (portId == null) {
            val routerPort = new RouterPort()
            routerPort.setDeviceId(routerId)
            routerPort.setHostId(hostId)
            routerPort.setPortAddr(RouterIp)
            routerPort.setNwAddr(NetAddr)
            routerPort.setNwLength(NetLen)
            routerPort.setInterfaceName(namespaceName)
            portId = dataClient.portsCreate(routerPort)
        }
        routerPortId = portId
        addVipRoute(config.vip.ip)

        dataClient.hostsAddVrnPortMapping(hostId, portId, namespaceName)

        createIpTableRules(healthMonitorName, config.vip.ip)
    }

    def unhookNamespaceFromRouter() = {
        if (routerPortId != null) {
            dataClient.hostsDelVrnPortMapping(hostId, routerPortId)
            dataClient.routesDelete(routeId)
            dataClient.portsDelete(routerPortId)
            deleteIpTableRules(healthMonitorName, config.vip.ip)
            routeId = null
            routerPortId = null
        }
    }

    //wrapping this call so we can mock it in the unit tests
    def setPoolMapStatus(status: PoolHealthMonitorMappingStatus) {
        dataClient.poolSetMapStatus(config.id, status)
    }
}
