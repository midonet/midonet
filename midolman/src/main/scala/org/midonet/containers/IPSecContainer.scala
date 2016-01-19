/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.containers

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.ExecutorService

import javax.inject.Named

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.google.common.hash.Hashing
import com.google.inject.Inject

import org.apache.commons.io.FileUtils

import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Subscription}

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.IPSecPolicy.{EncapsulationMode, TransformProtocol}
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.{DpdAction, IkePolicy, Initiator}
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouter, IPSecSiteConnection, VpnService}
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.Topology.{Port, Router}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.IPSecContainer._
import org.midonet.midolman.containers.{ContainerHandler, ContainerHealth, ContainerPort}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.packets.{IPAddr, IPv4Addr, IPv4Subnet}
import org.midonet.util.functors._

case class IPSecServiceDef(name: String,
                           filepath: String,
                           localEndpointIp: IPAddr,
                           localEndpointMac: String,
                           namespaceInterfaceIp: IPv4Subnet,
                           namespaceGatewayIp: IPv4Addr,
                           namespaceGatewayMac: String)

object IPSecConfig {
    val nameHash = Hashing.murmur3_32()
    def sanitizeName(name: String): String =
        name.replaceAll("[^\\w]", "_") + nameHash.hashString(name, UTF_8).toString

    def subnetsString(subnets: java.util.List[Commons.IPSubnet]): String = {
        if (subnets.isEmpty) return ""
        val ss = new StringBuilder(subnets.get(0).asJava.toString)
        for (i <- 1 until subnets.size) ss append s",${subnets.get(i).asJava}"
        ss.toString()
    }
}

/**
  * Represents a complete configuration of a VPN service, including all of the
  * individual connections. This class contains the functions necessary to
  * generate the config files and vpn-helper script commands.
  */
case class IPSecConfig(script: String,
                       ipsecService: IPSecServiceDef,
                       connections: Seq[IPSecSiteConnection]) {
    import IPSecConfig._

    def getSecretsFileContents = {
        val contents = new StringBuilder
        for (c <- connections if isSiteConnectionUp(c)) {
            contents append
            s"""${ipsecService.localEndpointIp} ${c.getPeerAddress} : PSK "${c.getPsk}"
               |""".stripMargin
        }
        contents.toString()
    }

    def initiatorToConfig(initiator: Initiator): String = {
        initiator match {
            case Initiator.BI_DIRECTIONAL => "start"
            case Initiator.RESPONSE_ONLY => "add"
        }
    }

    def ikeVersionToConfig(version: IkePolicy.IkeVersion): String = {
        version match {
            case IkePolicy.IkeVersion.V1 => "never"
            case IkePolicy.IkeVersion.V2 => "insist"
        }
    }

    def encapModeToConfig(encapsulationMode: EncapsulationMode): String = {
        encapsulationMode match {
            case EncapsulationMode.TRANSPORT => "transport"
            case EncapsulationMode.TUNNEL => "tunnel"
        }
    }

    def dpdActionToConfig(dpdAction: DpdAction): String = {
        dpdAction match {
            case DpdAction.CLEAR => "clear"
            case DpdAction.HOLD => "hold"
            case DpdAction.DISABLED => "disabled"
            case DpdAction.RESTART_BY_PEER => "restart-by-peer"
            case DpdAction.RESTART => "restart"
        }
    }

    def transformProtocolToConfig(transformProtocol: TransformProtocol): String = {
        transformProtocol match {
            case TransformProtocol.ESP => "esp"
            case TransformProtocol.AH => "ah"
            case TransformProtocol.AH_ESP => "ah-esp"
        }
    }

    def getConfigFileContents = {
        val contents = new StringBuilder
        contents append
            s"""config setup
               |    nat_traversal=yes
               |conn %default
               |    ikelifetime=480m
               |    keylife=60m
               |    keyingtries=%forever
               |""".stripMargin
        for (c <- connections if isSiteConnectionUp(c)) {
            contents append
                s"""conn ${sanitizeName(c.getName)}
                   |    leftnexthop=%defaultroute
                   |    rightnexthop=%defaultroute
                   |    left=${ipsecService.localEndpointIp}
                   |    leftid=${ipsecService.localEndpointIp}
                   |    auto=${initiatorToConfig(c.getInitiator)}
                   |    leftsubnets={ ${subnetsString(c.getLocalCidrsList)} }
                   |    leftupdown="ipsec _updown --route yes"
                   |    right=${c.getPeerAddress}
                   |    rightid=${c.getPeerAddress}
                   |    rightsubnets={ ${subnetsString(c.getPeerCidrsList)} }
                   |    mtu=${c.getMtu}
                   |    dpdaction=${dpdActionToConfig(c.getDpdAction)}
                   |    dpddelay=${c.getDpdInterval}
                   |    dpdtimeout=${c.getDpdTimeout}
                   |    authby=secret
                   |    ikev2=${ikeVersionToConfig(c.getIkepolicy.getIkeVersion)}
                   |    ike=aes128-sha1;modp1536
                   |    ikelifetime=${c.getIkepolicy.getLifetimeValue}s
                   |    auth=${transformProtocolToConfig(c.getIpsecpolicy.getTransformProtocol)}
                   |    phase2alg=aes128-sha1;modp1536
                   |    type=${encapModeToConfig(c.getIpsecpolicy.getEncapsulationMode)}
                   |    lifetime=${c.getIpsecpolicy.getLifetimeValue}s
                   |""".stripMargin
            }
        contents.toString()
    }

    val prepareHostCmd = s"$script prepare"

    val makeNsCmd =
        s"$script makens " +
        s"-n ${ipsecService.name} " +
        s"-g ${ipsecService.namespaceGatewayIp} " +
        s"-G ${ipsecService.namespaceGatewayMac} " +
        s"-l ${ipsecService.localEndpointIp} " +
        s"-i ${ipsecService.namespaceInterfaceIp} " +
        s"-m ${ipsecService.localEndpointMac}"

    val startServiceCmd =
        s"$script start_service -n ${ipsecService.name} -p ${ipsecService.filepath}"

    def initConnsCmd = {
        val cmd = new StringBuilder(s"$script init_conns " +
                                    s"-n ${ipsecService.name} " +
                                    s"-p ${ipsecService.filepath} " +
                                    s"-g ${ipsecService.namespaceGatewayIp}")
        for (c <- connections if isSiteConnectionUp(c)) {
            cmd append s" -c ${sanitizeName(c.getName)}"
        }
        cmd.toString
    }

    val stopServiceCmd = s"$script stop_service -n ${ipsecService.name} " +
                         s"-p ${ipsecService.filepath}"

    val cleanNsCmd = s"$script cleanns -n ${ipsecService.name}"

    val confDir = s"${ipsecService.filepath}/etc/"

    val confPath = s"${ipsecService.filepath}/etc/ipsec.conf"

    val secretsPath = s"${ipsecService.filepath}/etc/ipsec.secrets"
}

case class IPSecException(message: String, cause: Throwable = null)
    extends Exception(message, cause)

case class IPSecAdminStateDownException(routerId: UUID)
    extends Exception(s"VPN service(s) on router $routerId have admin state " +
                      "down", null)

object IPSecContainer {

    final val VpnHelperScriptPath = "/usr/lib/midolman/vpn-helper"

    def isVpnServiceUp(vpn: VpnService) = {
        !vpn.hasAdminStateUp || vpn.getAdminStateUp
    }

    def isSiteConnectionUp(conn: IPSecSiteConnection) = {
        !conn.hasAdminStateUp || conn.getAdminStateUp
    }

}

/**
  * Implements a [[ContainerHandler]] for a IPSec-based VPN service.
  */
@Container(name = Containers.IPSEC_CONTAINER, version = 1)
class IPSecContainer @Inject()(vt: VirtualTopology,
                               @Named("container") executor: ExecutorService)
    extends ContainerHandler with ContainerCommons {

    override def logSource = "org.midonet.containers.ipsec"

    private val healthSubject = PublishSubject.create[ContainerHealth]
    private implicit val ec = ExecutionContext.fromExecutor(executor)
    private val scheduler = Schedulers.from(executor)
    private val context = Context(vt.store, vt.stateStore, executor,
                                  Schedulers.from(vt.vtExecutor), log)

    @VisibleForTesting
    private[containers] var vpnServiceSubscription: Subscription = _
    private var config: IPSecConfig = null

    private case class VpnServiceUpdateEvent(routerPorts: List[Port],
                                             connections: List[IPSecSiteConnection],
                                             externalIp: IPAddr,
                                             port: Port)

    /**
      * @see [[ContainerHandler.create]]
      */
    override def create(port: ContainerPort): Future[Option[String]] = {
        log info s"Create IPSec container for $port"

        val createPromise = Promise[Option[String]]
        unsubscribeVpnService()
        vpnServiceSubscription = vpnServiceObservable(port)
            .subscribe(makeAction1((e: VpnServiceUpdateEvent) =>
                                       onVpnServiceUpdate(e, createPromise)),
                       makeAction1(createPromise.tryFailure(_)),
                       makeAction0 {
                           createPromise.trySuccess(None)
                           log.error(s"Stream complete for container port " +
                                     s"$port without any update")
                       })
        createPromise.future
    }

    /**
      * @see [[ContainerHandler.updated]]
      */
    override def updated(port: ContainerPort): Future[Option[String]] = {
        delete() flatMap { _ => create(port) }
    }

    /**
      * @see [[ContainerHandler.delete]]
      */
    override def delete(): Future[Unit] = {
        try {
            if (config != null) {
                log info s"Deleting IPSec container ${config.ipsecService.name}"
                cleanup(config)
                config = null
            }
            else log info s"IPSec container not started: ignoring"

            unsubscribeVpnService()
            vpnServiceSubscription = null
            Future.successful(())
        } catch {
            case NonFatal(e) =>
                log.error("Failed to delete IPSec container " +
                          s"${config.ipsecService.name}", e)
                Future.failed(e)
        }
    }

    /**
      * @see [[ContainerHandler.health]]
      */
    override def health: Observable[ContainerHealth] = {
        healthSubject.asObservable()
    }

    @inline
    private def unsubscribeVpnService(): Unit = {
        if (vpnServiceSubscription != null &&
            !vpnServiceSubscription.isUnsubscribed)
            vpnServiceSubscription.unsubscribe()
    }

    /*
     * Sets-up the IPSec service container, and throws an exception if the
     * namespace was not set up successfully.
     */
    @throws[Exception]
    protected[containers] def setup(config: IPSecConfig): Unit = {
        val name = config.ipsecService.name
        log info s"Setting up IPSec container $name"

        // prepare the host
        execCmd(config.prepareHostCmd)

        // Try clean namespace.
        execCmd(config.cleanNsCmd)

        val rootDirectory = new File(config.ipsecService.filepath)
        if (rootDirectory.exists()) {
            log debug s"Directory ${config.ipsecService.filepath} already exists: deleting"
            FileUtils.cleanDirectory(rootDirectory)
        }

        val etcDirectory = new File(config.confDir)
        FileUtils.forceMkdir(etcDirectory)

        log info s"Writing configuration to ${config.confPath}"
        writeFile(config.getConfigFileContents, config.confPath)

        log info s"Writing secrets to ${config.secretsPath}"
        writeFile(config.getSecretsFileContents, config.secretsPath)

        // Execute the first command from each pair of the following sequence,
        try {
            execCmds(Seq((config.makeNsCmd, config.cleanNsCmd),
                         (config.startServiceCmd, config.stopServiceCmd),
                         (config.initConnsCmd, null)))
        } catch {
            case NonFatal(e) => throw IPSecException("Command failed", e)
        }
    }

    /*
     * Cleans-up the IPSec service container, and returns true if the container
     * namespace was cleaned-up successfully.
     */
    @throws[Exception]
    protected[containers] def cleanup(config: IPSecConfig): Unit = {
        if (config eq null)
            return
        log info "Cleaning up IPSec container"
        execCmd(config.stopServiceCmd)
        execCmd(config.cleanNsCmd)
        try {
            FileUtils.deleteDirectory(new File(config.ipsecService.filepath))
        } catch {
            case NonFatal(e) =>
                log.warn(s"Failed to deleted temporary directory " +
                         s"${config.confDir}", e)
        }
    }

    /*
     * Returns an observable of VpnServiceUpdatedEvent that contains the latest
     * updates of the router, port and vpn service associated to this vpn
     * container.
     */
    private def vpnServiceObservable(cp: ContainerPort): Observable[VpnServiceUpdateEvent] = {
        val routerPortTracker = new CollectionStoreTracker[Port](context)
        val routerPortObservable = routerPortTracker.observable
            .map[List[Port]](makeFunc1 { ports =>
                log.debug(s"Router ports updated ${ports.keys}")
                ports.values.toList
            })

        val connectionsTracker =
            new CollectionStoreTracker[IPSecSiteConnection](context)
        val connectionsObservable = connectionsTracker.observable
            .map[List[IPSecSiteConnection]](makeFunc1 { connections =>
                log.debug(s"IPSec site connections updated ${connections.keys}")
                connections.values.toList
            })

        val vpnServiceTracker =
            new CollectionStoreTracker[VpnService](context)
        val vpnServiceObservable = vpnServiceTracker.observable
            .map[Boolean](makeFunc1 { vpnServices =>
                log.debug(s"VPN services updated ${vpnServices.keys}")
                // Update the list of ipsec connections to watch (only for
                // those vpn services with adminStateUp
                var connections = Set.empty[UUID]
                for (vpnService <- vpnServices.values) {
                    if (isVpnServiceUp(vpnService))
                        connections ++= vpnService.getIpsecSiteConnectionIdsList.toSet
                    else
                        log.debug(s"VPN service ${vpnService.getId.asJava} " +
                                  "administrative state is down")
                }
                connectionsTracker watch connections
                true
            })
            .distinctUntilChanged()

        def routerUpdated(router: Router): Unit = {
            val routerPortsIds = router.getPortIdsList.toSet
            log.debug(s"Router ${router.getId.asJava} updated with ports $routerPortsIds")
            routerPortTracker watch routerPortsIds

            if (router.getVpnServiceIdsList.isEmpty) {
                log.debug(s"No VPN service on router ${router.getId.asJava}")
                vpnServiceTracker watch Set()
            }
            else {
                val vpnServicesIds = router.getVpnServiceIdsList.toSet
                log.debug(s"Router ${router.getId.asJava} updated with VPN" +
                          s"services $vpnServicesIds")
                vpnServiceTracker watch vpnServicesIds
            }
        }

        val portObservable = vt.store
            .observable(classOf[Port], cp.portId)
            .observeOn(context.scheduler)
            .distinctUntilChanged()

        val routerObservable = vt.store
            .observable(classOf[Router], cp.configurationId)
            .observeOn(context.scheduler)
            .distinctUntilChanged()
            .doOnNext(makeAction1(routerUpdated))

        val neutronGwPortObservable = vt.store
            .observable(classOf[NeutronRouter], cp.configurationId)
            .observeOn(context.scheduler)
            .flatMap[NeutronPort](makeFunc1 { r: NeutronRouter =>
                log.debug(s"Neutron router updated ${r.getId.asJava}")
                vt.store.observable(classOf[NeutronPort], r.getGwPortId.asJava)
            })
            .map[IPAddr](makeFunc1 { p: NeutronPort =>
                log.debug(s"Neutron gateway port updated ${p.getId.asJava}")
                if (p.getFixedIpsCount > 0)
                    p.getFixedIps(0).getIpAddress.asIPAddress
                else null
            })
            .distinctUntilChanged()

        // Mapper combining midonet and neutron models for vpn
        Observable
            .combineLatest[List[Port], List[IPSecSiteConnection], Boolean,
                          Port, Router, IPAddr, VpnServiceUpdateEvent](
                routerPortObservable,
                connectionsObservable,
                vpnServiceObservable,
                portObservable,
                routerObservable,
                neutronGwPortObservable,
                makeFunc6((routerPorts, connections, vpn, port, router, externalIp) => {
                    VpnServiceUpdateEvent(routerPorts, connections, externalIp, port)
                }))
            .distinctUntilChanged()
            .filter(makeFunc1(event => {
                val ready =
                    routerPortTracker.isReady &&
                    connectionsTracker.isReady &&
                    vpnServiceTracker.isReady &&
                    (connectionsTracker.ids == event.connections.map(_.getId.asJava).toSet) &&
                    event.port.hasInterfaceName
                log.debug(s"VPN service ready: $ready")
                ready
            } ))
            .onBackpressureLatest()
            .observeOn(scheduler)
    }

    /*
     * Handler method called when the current vpnservice associated to the
     * container is updated (e.g. when a new ipsec connection is added, the
     * external ip address changed, etc.).
     */
    private def onVpnServiceUpdate(event: VpnServiceUpdateEvent,
                                   p: Promise[Option[String]]): Unit = {
        try {
            val VpnServiceUpdateEvent(routerPorts, conns, externalIp, port) = event

            if (externalIp eq null) {
                log.warn(s"Router ${port.getRouterId.asJava} does not have " +
                         "an external gateway ip. Shutting down connections.")
                p.trySuccess(None)
                cleanup(config)
                config = null
                return
            }

            val externalMac = routerPorts
                .find(_.getPortAddress.asIPv4Address == externalIp)
                .map(_.getPortMac)
                .getOrElse {
                    log.warn(s"VPN service on router ${port.getRouterId.asJava} " +
                             "does not have a port that matches the router external " +
                             s"address $externalIp. Shutting down connections.")
                    p.trySuccess(None)
                    cleanup(config)
                    config = null
                    return
                }
            val portAddress = port.getPortAddress.asIPv4Address
            val namespaceAddress = portAddress.next
            val namespaceSubnet = new IPv4Subnet(namespaceAddress,
                                                 port.getPortSubnet
                                                     .getPrefixLength)
            val path =
                s"${FileUtils.getTempDirectoryPath}/${port.getInterfaceName}"
            val serviceDef = IPSecServiceDef(port.getInterfaceName,
                                             path,
                                             externalIp,
                                             externalMac,
                                             namespaceSubnet,
                                             portAddress,
                                             port.getPortMac)
             // Cleanup current setup before if existed
            cleanup(config)

            if (conns.forall(!isSiteConnectionUp(_))) {
                log.info("All IPSec connections have administrative state down")
                // So we don't clean up on a non-setup container
                config = null
                p.trySuccess(None)
            } else {
                // Create the new config and setup the new connections
                config = IPSecConfig(VpnHelperScriptPath, serviceDef, conns)
                setup(config)
                p.trySuccess(Some(config.ipsecService.name))
            }
            healthSubject onNext ContainerHealth(Code.RUNNING, port.getInterfaceName)
        } catch {
            case NonFatal(e) => p.tryFailure(e)
        }
    }
}


