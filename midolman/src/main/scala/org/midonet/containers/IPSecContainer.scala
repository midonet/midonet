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
import java.util.UUID
import java.util.concurrent.ExecutorService

import javax.inject.Named

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

import com.google.inject.Inject

import org.apache.commons.io.FileUtils

import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Subscription}

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.IPSecPolicy.{EncapsulationMode, TransformProtocol}
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.{DpdAction, IkePolicy, Initiator}
import org.midonet.cluster.models.Neutron.{IPSecSiteConnection, VpnService}
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.Topology.{Port, Router}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.IPSecContainer.VpnHelperScriptPath
import org.midonet.midolman.containers.{ContainerHandler, ContainerHealth, ContainerPort}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.packets.{IPv4Addr, IPv4Subnet}
import org.midonet.util.concurrent._
import org.midonet.util.functors._

case class IPSecServiceDef(name: String,
                           filepath: String,
                           localEndpointIp: IPv4Addr,
                           localEndpointMac: String,
                           namespaceInterfaceIp: IPv4Subnet,
                           namespaceGatewayIp: IPv4Addr,
                           namespaceGatewayMac: String)

/**
  * Represents a complete configuration of a VPN service, including all of the
  * individual connections. This class contains the functions necessary to
  * generate the config files and vpn-helper script commands.
  */
case class IPSecConfig(script: String,
                       ipsecService: IPSecServiceDef,
                       connections: Seq[IPSecSiteConnection]) {

    def getSecretsFileContents = {
        val contents = new StringBuilder
        for (c <- connections if (!c.hasAdminStateUp || c.getAdminStateUp)) {
            contents append
            s"""${ipsecService.localEndpointIp} ${c.getPeerAddress} : PSK "${c.getPsk}"
               |""".stripMargin
        }
        contents.toString()
    }

    def subnetString(sub: Commons.IPSubnet) =
        s"${sub.getAddress}/${sub.getPrefixLength}"

    def subnetsString(subnets: java.util.List[Commons.IPSubnet]): String = {
        if (subnets.isEmpty) return ""
        val ss = new StringBuilder(subnetString(subnets.get(0)))
        Range(1, subnets.size()) foreach (i => s",${subnetString(subnets.get(i))}")
        ss.toString()
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
        for (c <- connections if !c.hasAdminStateUp || c.getAdminStateUp) {
            contents append
                s"""conn ${c.getName}
                   |    leftnexthop=%defaultroute
                   |    rightnexthop=%defaultroute
                   |    left=${ipsecService.localEndpointIp}
                   |    leftid=${ipsecService.localEndpointIp}
                   |    auto=${initiatorToConfig(c.getInitiator)}
                   |    leftsubnets={ ${subnetString(c.getLocalCidr)} }
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
        connections foreach (c => cmd append s" -c ${c.getName}")
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
    extends Exception(s"VPNService(s) on router $routerId have admin state " +
                      "DOWN", null)

object IPSecContainer {

    final val VpnHelperScriptPath = "/usr/lib/midolman/vpn-helper"

}

/**
  * Implements a [[ContainerHandler]] for a IPSec-based VPN service.
  */
@Container(name = Containers.IPSEC_CONTAINER, version = 1)
class IPSecContainer @Inject()(vt: VirtualTopology,
                               @Named("container") executor: ExecutorService)
    extends ContainerHandler with ContainerCommons {

    override def logSource = "org.midonet.containers.ipsec"

    private val timeout = vt.config.zookeeper.sessionTimeout seconds
    private val healthSubject = PublishSubject.create[ContainerHealth]
    private implicit val ec = ExecutionContext.fromExecutor(executor)
    private val containerScheduler = Schedulers.from(executor)

    private var vpnServiceSubscription: Subscription = _
    private var config: IPSecConfig = null

    private case class VpnServiceUpdateEvent(port: Port,
                                             router: Router,
                                             vpnService: VpnService)

    /**
      * @see [[ContainerHandler.create]]
      */
    override def create(port: ContainerPort): Future[Option[String]] = {
        log info s"Create IPSec container for $port"

        try {
            val createPromise = Promise[Option[String]]
            if (vpnServiceSubscription != null &&
                    !vpnServiceSubscription.isUnsubscribed)
                vpnServiceSubscription.unsubscribe()

            vpnServiceSubscription = vpnServiceObservable(port)
                .subscribe(makeAction1((e: VpnServiceUpdateEvent) =>
                                           onVpnServiceUpdate(e, createPromise)),
                           makeAction1(createPromise.tryFailure(_)),
                           makeAction0 {
                               createPromise.trySuccess(None)
                               new IPSecException(
                                   "Stream completed for container port " +
                                   s"$port without any update")
                           })
            createPromise.future
        } catch {
            case NonFatal(e) =>
                log.error(s"Failed to create IPSec for $port", e)
                Future.failed(e)
        }
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
        if (config eq null) {
            log info s"IPSec container not started: ignoring"
            return Future.successful(())
        }

        log info s"Deleting IPSec container ${config.ipsecService.name}"

        try {
            cleanup(config)
            config = null
            vpnServiceSubscription.unsubscribe()
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

    /*
     * Sets-up the IPSec service container, and throws an exception if the
     * namespace was not set up successfully.
     */
    @throws[Exception]
    protected[containers] def setup(config: IPSecConfig): Unit = {
        val name = config.ipsecService.name
        log info s"Setting up IPSec container $name"
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
            // Don't clean up anything if config not set yet
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
        val portObservable = vt.store
            .observable(classOf[Port], cp.portId)
            .distinctUntilChanged()

        val routerObservable = vt.store
            .observable(classOf[Router], cp.configurationId)
            .distinctUntilChanged()

        // switch to track multiple vpn services when we support it
        val vpnServiceObservable = routerObservable
            .flatMap(makeFunc1(r => {
                val vpnServiceId = r.getVpnServiceIdsList.headOption.getOrElse(
                    throw IPSecException(
                    s"No VPN services on router ${r.getId.asJava}"))
                vt.store.observable(classOf[VpnService], vpnServiceId)
            }))
            .distinctUntilChanged()

       Observable
           .combineLatest[Port, Router, VpnService, VpnServiceUpdateEvent](
                portObservable,
                routerObservable,
                vpnServiceObservable,
                makeFunc3(buildEvent))
           .distinctUntilChanged()
           .onBackpressureBuffer()
           .observeOn(containerScheduler)
    }

    private def buildEvent(port: Port, router: Router, vpnService: VpnService):
    VpnServiceUpdateEvent = {
        VpnServiceUpdateEvent(port, router, vpnService)
    }

    /*
     * Handler method called when the current vpnservice associated to the
     * container is updated (e.g. when a new ipsec connection is added, the
     * external ip address changed, etc.).
     */
    private def onVpnServiceUpdate(updateEvent: VpnServiceUpdateEvent,
                                   p: Promise[Option[String]]): Unit = {
        updateEvent match {
            case VpnServiceUpdateEvent(port, router, vpn) =>
                try {
                    // TODO: retrieve routerPorts and connections asynchronously
                    val routerPorts = vt.store
                        .getAll(classOf[Port], router.getPortIdsList)
                        .await(timeout).toList
                    val connections = vt.store
                        .getAll(classOf[IPSecSiteConnection],
                                vpn.getIpsecSiteConnectionIdsList)
                        .await(timeout).toList
                    val externalAddress = vpn.getExternalIp.asIPv4Address
                    val externalMac = routerPorts
                        .find(_.getPortAddress.asIPv4Address == externalAddress)
                        .map(_.getPortMac)
                        .getOrElse {
                            p.tryFailure(IPSecException(
                                s"VPN service ${vpn.getId.asJava} router " +
                                s"${vpn.getRouterId.asJava} does not have " +
                                s"a port that matches the VPN external address " +
                                s"$externalAddress", null))
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
                                                     externalAddress,
                                                     externalMac,
                                                     namespaceSubnet,
                                                     portAddress,
                                                     port.getPortMac)

                    // Cleanup current setup before if existed
                    cleanup(config)

                    if (vpn.hasAdminStateUp && !vpn.getAdminStateUp) {
                        log.info(
                            s"VPN service ${makeReadable(vpn)} has admin state DOWN")
                        // So we don't clean up on a non-setup container
                        config = null
                        p.trySuccess(None)
                    }
                    else {
                        // Create the new config and setup the new connections
                        config = IPSecConfig(VpnHelperScriptPath, serviceDef,
                                             connections)
                        setup(config)
                        p.trySuccess(Some(config.ipsecService.name))
                    }
                    healthSubject onNext ContainerHealth(Code.RUNNING,
                                                         port.getInterfaceName)
                } catch {
                    case NonFatal(e) => p.tryFailure(e)
                }
            case _ =>
        }
    }
}
