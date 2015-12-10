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
import java.util.concurrent.ExecutorService

import javax.inject.Named

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.google.inject.Inject

import org.apache.commons.io.FileUtils

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.IPSecPolicy.{TransformProtocol, EncapsulationMode}
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.{IkePolicy, DpdAction, Initiator}
import org.midonet.cluster.models.Neutron.{VpnService, IPSecSiteConnection}
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.Topology.{Router, Port}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.containers.{ContainerHealth, ContainerPort, ContainerHandler}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.packets.{IPv4Subnet, IPv4Addr}
import org.midonet.util.concurrent._

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
        connections foreach { c =>
            contents append
                s"""${ipsecService.localEndpointIp} ${c.getPeerAddress} : PSK "${c.getPsk}"
                   |""".stripMargin
        }
        contents.toString()
    }

    def subnetsString(subnets: java.util.List[Commons.IPSubnet]): String = {
        if (subnets.isEmpty) return ""
        def subnetStr(sub: Commons.IPSubnet) =
            s"${sub.getAddress}/${sub.getPrefixLength}"
        val ss = new StringBuilder(subnetStr(subnets.get(0)))
        Range(1, subnets.size()) foreach (i => s",${subnetStr(subnets.get(i))}")
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
        connections foreach (c => contents append
            s"""conn ${c.getName}
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
               |    ikev2=${ikeVersionToConfig(c.getIkePolicy.getIkeVersion)}
               |    ike=aes128-sha1;modp1536
               |    ikelifetime=${c.getIkePolicy.getLifetimeValue}s
               |    auth=${transformProtocolToConfig(c.getIpsecPolicy.getTransformProtocol)}
               |    phase2alg=aes128-sha1;modp1536
               |    type=${encapModeToConfig(c.getIpsecPolicy.getEncapsulationMode)}
               |    lifetime=${c.getIpsecPolicy.getLifetimeValue}s
               |""".stripMargin)
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

case class IPSecException(message: String, cause: Throwable)
    extends Exception(message, cause)

/**
  * Implements a [[ContainerHandler]] for a IPSec-based VPN service.
  */
@Container(name = "IPSEC", version = 1)
class IPSecContainer @Inject()(vt: VirtualTopology,
                               @Named("container") executor: ExecutorService)
    extends ContainerHandler with ContainerCommons {

    override def logSource = "org.midonet.containers.ipsec"

    private val timeout = vt.config.zookeeper.sessionTimeout seconds
    private var config: IPSecConfig = null
    private val healthSubject = PublishSubject.create[ContainerHealth]
    private implicit val ec = ExecutionContext.fromExecutor(executor)

    /**
      * Creates a container for the specified exterior port and service
      * container. The port contains the interface name that the container
      * handler should create, and the method returns a future that completes
      * with the namespace name when the container has been created.
      */
    def create(port: ContainerPort): Future[String] = {
        log info s"Create IPSec container for $port"

        try {
            config = createConfig(port)
            setup(config)

            healthSubject onNext ContainerHealth(Code.RUNNING,
                                                 config.ipsecService.name)

            Future.successful(config.ipsecService.name)
        } catch {
            case NonFatal(e) =>
                log.error(s"Failed to create IPSec for $port", e)
                Future.failed(e)
        }
    }

    /**
      * Indicates that the configuration identifier for an existing container
      * has changed. This method is called only when the reference to the
      * configuration changes and not when the data of the existing configuration
      * objects change. It is the responsibility of the classes implementing
      * this interface to monitor their configuration.
      */
    def updated(port: ContainerPort): Future[String] = {
        delete() flatMap { _ => create(port) }
    }

    /**
      * Deletes the container for the specified exterior port and namespace
      * information. The method returns a future that completes when the
      * container has been deleted.
      */
    def delete(): Future[Unit] = {
        if (config eq null) {
            log info s"IPSec container not started: ignoring"
            return Future.successful(())
        }

        log info s"Deleting IPSec container ${config.ipsecService.name}"

        try {
            cleanup(config)
            config = null
            Future.successful(())
        } catch {
            case NonFatal(e) =>
                log.error("Failed to delete IPSec container " +
                          s"${config.ipsecService.name}", e)
                Future.failed(e)
        }
    }

    /**
      * An observable that reports the health status of the container, which
      * includes both the container namespace/interface as well as the
      * service application executing within the container.
      */
    def health: Observable[ContainerHealth] = {
        healthSubject.asObservable()
    }

    /*
     * Sets-up the IPSec service container, and returns true if the container
     * namespace was setup successfully.
     */
    @throws[Exception]
    protected[containers] def setup(config: IPSecConfig): Unit = {
        log info s"Setting up IPSec container ${config.ipsecService.name}"
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

    @throws[Exception]
    private def createConfig(cp: ContainerPort): IPSecConfig = {
        val vpnService = vt.store.get(classOf[VpnService], cp.configurationId)
                                 .await(timeout)

        val port = vt.store.get(classOf[Port], cp.portId).await(timeout)
        val router = vt.store.get(classOf[Router], vpnService.getRouterId)
                             .await(timeout)

        val externalAddress = vpnService.getExternalV4Ip.asIPv4Address
        val externalMac = router.getPortIdsList.asScala
            .map(vt.store.get(classOf[Port], _).await(timeout))
            .find(_.getPortAddress.asIPv4Address == externalAddress)
            .map(_.getPortMac)
            .getOrElse(throw IPSecException(
                s"VPN service ${vpnService.getId.asJava} router " +
                s"${vpnService.getRouterId.asJava} does not have a port " +
                s"that matches the VPN external address $externalAddress", null))

        val portAddress = port.getPortAddress.asIPv4Address
        val namespaceAddress = portAddress.next
        val namespaceSubnet = new IPv4Subnet(namespaceAddress,
                                             port.getPortSubnet.getPrefixLength)

        val path =
            s"${FileUtils.getTempDirectoryPath}/${port.getInterfaceName}"

        val serviceDef = IPSecServiceDef(port.getInterfaceName, path,
                                         externalAddress,
                                         externalMac,
                                         namespaceSubnet,
                                         portAddress,
                                         port.getPortMac)

        val siteConnections = vt.store.getAll(classOf[IPSecSiteConnection],
                                              vpnService.getIpsecSiteConnectionIdsList)
                                      .await(timeout)

        IPSecConfig("/usr/lib/midolman/vpn-helper", serviceDef, siteConnections)
    }

}
