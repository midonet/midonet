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
import java.util.concurrent.{ExecutorService, ScheduledExecutorService, TimeUnit}

import javax.inject.Named

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.MoreObjects
import com.google.inject.Inject

import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscription}

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.IPSecPolicy.{EncapsulationMode, TransformProtocol}
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.{DpdAction, IPSecAuthAlgorithm, IPSecEncryptionAlgorithm, IPSecPfs, IPSecPolicy, IkePolicy, Initiator}
import org.midonet.cluster.models.Neutron.{IPSecSiteConnection, VpnService}
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.Topology.{Port, Router}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.IPSecContainer._
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.packets.{IPAddr, IPv4Addr, IPv4Subnet}
import org.midonet.util.concurrent._
import org.midonet.util.functors._
import org.midonet.util.io.Tailer
import org.midonet.util.logging.Logger

case class IPSecServiceDef(name: String,
                           filepath: String,
                           localEndpointIp: IPAddr,
                           localEndpointMac: String,
                           namespaceInterfaceIp: IPv4Subnet,
                           namespaceGatewayIp: IPv4Addr,
                           namespaceGatewayMac: String)

object IPSecConfig {

    @inline
    def vpnName(connectionId: UUID): String = {
        "ipsec-" + connectionId.toString.substring(0, 8)
    }

    @inline
    def sanitizePsk(psk: String): String = {
        psk.replaceAll("[\\n\\r\"]", "")
    }

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

    def getSecretsFileContents(log: Logger) = {
        val contents = new StringBuilder
        for (c <- connections if isSiteConnectionUp(c)) {
            val sanitizedPsk = sanitizePsk(c.getPsk)
            if (log != null && c.getPsk != sanitizedPsk) {
                log.info(s"The PSK cannot contain double quotes nor new lines, " +
                         s"these characters were removed.")
            }
            contents append
            s"""${ipsecService.localEndpointIp} ${c.getPeerId} : PSK "$sanitizedPsk"
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

    def ipsecEncryptionToConfig(encryptionAlgorithm : IPSecEncryptionAlgorithm): String = {
        encryptionAlgorithm match {
            case IPSecEncryptionAlgorithm.DES_3 => "3des"
            case IPSecEncryptionAlgorithm.AES_128 => "aes128"
            case IPSecEncryptionAlgorithm.AES_192 => "aes192"
            case IPSecEncryptionAlgorithm.AES_256 => "aes256"
        }
    }

    def ipsecAuthToConfig(authAlgorithm: IPSecAuthAlgorithm): String = {
        authAlgorithm match {
            case IPSecAuthAlgorithm.SHA1 => "sha1"
        }
    }

    def ipsecPfsToConfig(pfs: IPSecPfs): String = {
        pfs match {
            case IPSecPfs.GROUP2 => "modp1024"
            case IPSecPfs.GROUP5 => "modp1536"
            case IPSecPfs.GROUP14 => "modp2048"
        }
    }

    def ikeParamsToConfig(ikePolicy: IkePolicy): String = {
        val ikeParams = new StringBuilder(s"${ipsecEncryptionToConfig(ikePolicy.getEncryptionAlgorithm)}" +
                                            s"-${ipsecAuthToConfig(ikePolicy.getAuthAlgorithm)}" +
                                            s";${ipsecPfsToConfig(ikePolicy.getPfs)}")
        ikeParams.toString
    }

    def ipsecParamsToConfig(ipsecPolicy: IPSecPolicy): String = {
        val ipsecParams = new StringBuilder(s"${ipsecEncryptionToConfig(ipsecPolicy.getEncryptionAlgorithm)}" +
                                            s"-${ipsecAuthToConfig(ipsecPolicy.getAuthAlgorithm)}" +
                                            s";${ipsecPfsToConfig(ipsecPolicy.getPfs)}")
        ipsecParams.toString
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
                s"""conn ${vpnName(c.getId.asJava)}
                   |    leftnexthop=%defaultroute
                   |    rightnexthop=%defaultroute
                   |    left=${ipsecService.localEndpointIp}
                   |    leftid=${ipsecService.localEndpointIp}
                   |    auto=${initiatorToConfig(c.getInitiator)}
                   |    leftsubnets={ ${subnetsString(c.getLocalCidrsList)} }
                   |    leftupdown="ipsec _updown --route yes"
                   |    right=${c.getPeerAddress}
                   |    rightid=${c.getPeerId}
                   |    rightsubnets={ ${subnetsString(c.getPeerCidrsList)} }
                   |    mtu=${c.getMtu}
                   |    dpdaction=${dpdActionToConfig(c.getDpdAction)}
                   |    dpddelay=${c.getDpdInterval}
                   |    dpdtimeout=${c.getDpdTimeout}
                   |    authby=secret
                   |    ikev2=${ikeVersionToConfig(c.getIkepolicy.getIkeVersion)}
                   |    ike=${ikeParamsToConfig(c.getIkepolicy)}
                   |    ikelifetime=${c.getIkepolicy.getLifetimeValue}s
                   |    auth=${transformProtocolToConfig(c.getIpsecpolicy.getTransformProtocol)}
                   |    phase2alg=${ipsecParamsToConfig(c.getIpsecpolicy)}
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
            cmd append s" -c ${vpnName(c.getId.asJava)}"
        }
        cmd.toString
    }

    val stopServiceCmd = s"$script stop_service -n ${ipsecService.name} " +
                         s"-p ${ipsecService.filepath}"

    val cleanNsCmd = s"$script cleanns -n ${ipsecService.name}"

    val confDir = s"${ipsecService.filepath}/etc"

    val confPath = s"$confDir/ipsec.conf"

    val secretsPath = s"$confDir/ipsec.secrets"

    val runDir = s"${ipsecService.filepath}/var/run"

    val plutoDir = s"$runDir/pluto"

    val logPath = s"$runDir/pluto.log"

    val createLogCmd = s"mkfifo -m 0600 $logPath"

    val statusCmd = s"ip netns exec ${ipsecService.name} ipsec whack " +
                    s"--status --ctlbase $plutoDir"
}

case class IPSecException(message: String, cause: Throwable = null)
    extends Exception(message, cause)

case class IPSecAdminStateDownException(routerId: UUID)
    extends Exception(s"VPN service(s) on router $routerId have admin state " +
                      "down", null)

object IPSecContainer {

    final val VpnHelperScriptPath = "/usr/lib/midolman/vpn-helper"
    final val VpnDirectoryPath = FileUtils.getTempDirectoryPath

    @inline
    def vpnPath(name: String): String = {
        s"$VpnDirectoryPath/$name"
    }

    @inline
    def isVpnServiceUp(vpn: VpnService) = {
        !vpn.hasAdminStateUp || vpn.getAdminStateUp
    }

    @inline
    def isSiteConnectionUp(conn: IPSecSiteConnection) = {
        !conn.hasAdminStateUp || conn.getAdminStateUp
    }

}

/**
  * Implements a [[ContainerHandler]] for a IPSec-based VPN service.
  */
@Container(name = Containers.IPSEC_CONTAINER, version = 1)
class IPSecContainer @Inject()(@Named("id") id: UUID,
                               vt: VirtualTopology,
                               @Named("container") containerExecutor: ExecutorService,
                               @Named("io") ioExecutor: ScheduledExecutorService)
    extends ContainerHandler with ContainerCommons {

    override def logSource = "org.midonet.containers.ipsec"
    override def logMark = s"ipsec:$id"

    private val statusSubject = PublishSubject.create[ContainerStatus]
    private implicit val ec = ExecutionContext.fromExecutor(containerExecutor)
    private val scheduler = Schedulers.from(containerExecutor)
    private val context = Context(vt.store, vt.stateStore, containerExecutor,
                                  Schedulers.from(vt.vtExecutor), log)

    @VisibleForTesting
    private[containers] var vpnServiceSubscription: Subscription = null
    private var config: IPSecConfig = null

    private val ipsecLog =
        Logger(LoggerFactory.getLogger("org.midonet.containers.ipsec.ipsec-pluto"))
    private var logTailer: Tailer = null
    private val logObserver = new Observer[String] {
        override def onNext(line: String): Unit = {
            ipsecLog debug line
        }
        override def onCompleted(): Unit = { }
        override def onError(e: Throwable): Unit = {
            log warn s"Reading IPSec log failed: ${e.getMessage}"
        }
    }

    private val statusInterval = vt.config.containers.ipsec.statusUpdateInterval
    private val logPollInterval = vt.config.containers.ipsec.loggingPollInterval
    private val logTimeout = vt.config.containers.ipsec.loggingTimeout

    private val statusObservable =
        Observable.interval(statusInterval.toMillis, statusInterval.toMillis,
                            TimeUnit.MILLISECONDS, scheduler)
    private val statusObserver = new Observer[java.lang.Long] {
        override def onNext(tick: java.lang.Long): Unit = {
            if (config eq null) {
                return
            }
            try {
                val status = checkStatus()
                log trace s"IPSec container health: $status"
                statusSubject onNext status
            } catch {
                case NonFatal(e) =>
                    log.warn("IPSec status check failed", e)
                    val message = if (e.getMessage eq null) "" else e.getMessage
                    statusSubject onNext ContainerHealth(
                        Code.ERROR, config.ipsecService.name, message)
            }
        }

        override def onCompleted(): Unit = {
            log warn "Container health scheduling completed unexpectedly"
        }

        override def onError(e: Throwable): Unit = {
            log.error("Container health scheduling error", e)
        }
    }
    private var statusSubscription: Subscription = null

    private case class VpnServiceUpdateEvent(routerPorts: List[Port],
                                             connections: List[IPSecSiteConnection],
                                             externalIp: IPAddr,
                                             port: Port) {
        override def toString = MoreObjects.toStringHelper(this).omitNullValues()
            .add("routerPorts", routerPorts.map(_.getId.asJava))
            .add("connections", connections.map(_.getId.asJava))
            .add("externalIp", externalIp)
            .add("port", port.getId.asJava)
            .toString
    }

    /**
      * @see [[ContainerHandler.create]]
      */
    override def create(port: ContainerPort): Future[Option[String]] = {
        log info s"Create IPSec container for $port"

        val createPromise = Promise[Option[String]]
        unsubscribeVpnService()
        vpnServiceSubscription = vpnServiceObservable(port)
            .subscribe(new Observer[VpnServiceUpdateEvent] {
                override def onNext(event: VpnServiceUpdateEvent): Unit = {
                    onVpnServiceUpdate(event, createPromise)
                }
                override def onError(e: Throwable): Unit = {
                    createPromise tryFailure e
                }
                override def onCompleted(): Unit = {
                    createPromise trySuccess None
                }
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
            } else {
                log info s"IPSec container not started: ignoring"
            }

            unsubscribeVpnService()
            Future.successful(())
        } catch {
            case NonFatal(e) =>
                val message =
                    if (config ne null) "Failed to delete IPSec container " +
                                        s"{config.ipsecService.name}"
                    else "Failed to delete IPSec container"
                log.error(message, e)
                Future.failed(e)
        }
    }

    /**
      * @see [[ContainerHandler.cleanup]]
      */
    override def cleanup(name: String): Future[Unit] = {
        try {
            cleanup(IPSecConfig(VpnHelperScriptPath,
                                IPSecServiceDef(name = name,
                                                filepath = vpnPath(name),
                                                localEndpointIp = null,
                                                localEndpointMac = null,
                                                namespaceInterfaceIp = null,
                                                namespaceGatewayIp = null,
                                                namespaceGatewayMac = null),
                                Seq.empty))
            Future.successful(())
        } catch {
            case NonFatal(e) =>
                log.warn("Failed to cleanup IPSec container", e)
                Future.failed(e)
        }
    }

    /**
      * @see [[ContainerHandler.status]]
      */
    override def status: Observable[ContainerStatus] = {
        statusSubject.asObservable()
    }

    /*
     * Sets up the IPSec service container, and throws an exception if the
     * namespace was not set up successfully.
     */
    @throws[Exception]
    protected[containers] def setup(config: IPSecConfig): Unit = {
        val name = config.ipsecService.name
        log info s"Setting up IPSec container $name"

        // Prepare the host.
        execute(config.prepareHostCmd)

        // Try to clean namespace.
        execute(config.cleanNsCmd)

        val rootDirectory = new File(config.ipsecService.filepath)
        if (rootDirectory.exists()) {
            log debug s"Directory ${config.ipsecService.filepath} already " +
                      "exists: deleting"
            FileUtils.cleanDirectory(rootDirectory)
        }

        val etcDirectory = new File(config.confDir)
        FileUtils.forceMkdir(etcDirectory)

        val plutoDirectory = new File(config.plutoDir)
        FileUtils.forceMkdir(plutoDirectory)

        log info s"Writing configuration to ${config.confPath}"
        writeFile(config.getConfigFileContents, config.confPath)

        log info s"Writing secrets to ${config.secretsPath}"
        writeFile(config.getSecretsFileContents(log), config.secretsPath)

        // Create the log FIFO file.
        execute(config.createLogCmd)

        // Create the tailer to read the log file.
        if (logTailer ne null) {
            logTailer.close()
        }
        logTailer = new Tailer(new File(config.logPath), ioExecutor, logObserver,
                               logPollInterval.toMillis, TimeUnit.MILLISECONDS)
        logTailer.start()

        // Schedule the status check.
        if (statusSubscription ne null) {
            statusSubscription.unsubscribe()
        }
        statusSubscription = statusObservable subscribe statusObserver

        // Execute the first command from each pair of the following sequence,
        try {
            statusSubject onNext ContainerOp(ContainerFlag.Created,
                                             config.ipsecService.name)
            executeCommands(Seq((config.makeNsCmd, config.cleanNsCmd),
                                (config.startServiceCmd, config.stopServiceCmd),
                                (config.initConnsCmd, null)))
        } catch {
            case NonFatal(e) =>
                statusSubject onNext ContainerOp(ContainerFlag.Deleted,
                                                 config.ipsecService.name)
                throw IPSecException("Command failed", e)
        }
    }

    /*
     * Cleans-up the IPSec service container, and returns true if the container
     * namespace was cleaned-up successfully.
     */
    @throws[Exception]
    protected[containers] def cleanup(config: IPSecConfig): Unit = {
        if (statusSubscription ne null) {
            statusSubscription.unsubscribe()
            statusSubscription = null
        }
        if (config eq null) {
            return
        }
        log info s"Cleaning up IPSec container ${config.ipsecService.name}"
        statusSubject onNext ContainerOp(ContainerFlag.Deleted,
                                         config.ipsecService.name)
        execute(config.stopServiceCmd)
        execute(config.cleanNsCmd)
        try {
            if (logTailer ne null) {
                logTailer.close().await(logTimeout)
            }
        } catch {
            case NonFatal(e) =>
                log.info("Failed to close the IPSec log reader", e)
        } finally {
            logTailer = null
        }
        try {
            FileUtils.deleteDirectory(new File(config.ipsecService.filepath))
        } catch {
            case NonFatal(e) =>
                log.warn("Failed to delete temporary directory " +
                         s"${config.ipsecService.filepath}", e)
        }
    }

    /**
      * Unsubscribes from the current VPN service observable.
      */
    @inline
    private def unsubscribeVpnService(): Unit = {
        if (vpnServiceSubscription ne null) {
            vpnServiceSubscription.unsubscribe()
            vpnServiceSubscription = null
        }
    }

    /*
     * Returns an observable of VpnServiceUpdatedEvent that contains the latest
     * updates of the router, port and vpn service associated to this vpn
     * container.
     */
    private def vpnServiceObservable(cp: ContainerPort)
    : Observable[VpnServiceUpdateEvent] = {
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
            .map[IPAddr](makeFunc1 { vpnServices =>
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
                // All VPN services have the same connectivity information
                // except their associated site connections. Just pick the head
                // and emit the IPv4Address.
                if (vpnServices.nonEmpty) {
                    val externalIps = vpnServices.values.flatMap(value => {
                        if (value.hasExternalIp) Seq(value.getExternalIp.asIPAddress)
                        else Seq.empty
                    }).toSet
                    if (externalIps.isEmpty) {
                        log warn s"VPN service(s) for port ${cp.portId} do " +
                                 "not have an external IP addresses set: " +
                                 "VPN is left unconfigured"
                        null
                    } else if (externalIps.size > 1) {
                        log warn s"VPN services for port ${cp.portId} have " +
                                 "more than one external IP address (" +
                                 s"$externalIps): VPN is left unconfigured"
                        null
                    } else {
                        externalIps.head
                    }
                }
                else null
            })
            .distinctUntilChanged()

        def routerUpdated(router: Router): Unit = {
            val routerPortsIds = router.getPortIdsList.toSet
            log.debug(s"Router ${router.getId.asJava} updated with ports " +
                      s"$routerPortsIds")
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

        Observable
            .combineLatest[List[Port], List[IPSecSiteConnection], IPAddr,
                          Port, Router, VpnServiceUpdateEvent](
                routerPortObservable,
                connectionsObservable,
                vpnServiceObservable,
                portObservable,
                routerObservable,
                makeFunc5((routerPorts, connections, externalIp, port, router) => {
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
                                   promise: Promise[Option[String]]): Unit = {
        try {
            val VpnServiceUpdateEvent(routerPorts, conns, externalIp, port) = event

            log debug s"VPN service updated $event"

            if (externalIp eq null) {
                // No external IP means no VPN service whatsoever.
                promise.trySuccess(None)
                cleanup(config)
                config = null
                return
            }

            val externalMac = routerPorts
                .find(_.getPortAddress.asIPv4Address == externalIp)
                .map(_.getPortMac)
                .getOrElse {
                    promise.tryFailure(IPSecException(
                        s"VPN service on router ${port.getRouterId.asJava} " +
                        s"does not have a port that matches the VPN external " +
                        s"address $externalIp", null))
                    cleanup(config)
                    config = null
                    return
                }
            val portAddress = port.getPortAddress.asIPv4Address
            val namespaceAddress = portAddress.next
            val namespaceSubnet = new IPv4Subnet(namespaceAddress,
                                                 port.getPortSubnet(0)
                                                     .getPrefixLength)
            val serviceDef = IPSecServiceDef(port.getInterfaceName,
                                             vpnPath(port.getInterfaceName),
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
                promise.trySuccess(None)
            } else {
                // Create the new config and setup the new connections
                config = IPSecConfig(VpnHelperScriptPath, serviceDef, conns)
                setup(config)
                promise.trySuccess(Some(config.ipsecService.name))
            }
            statusSubject onNext checkStatus()
        } catch {
            case NonFatal(e) =>
                log.warn("Error handling vpn service update", e)
                try { cleanup(config) }
                catch {
                    case NonFatal(t) =>
                        log.warn("VPN service container cleanup failed", t)
                }
                promise.tryFailure(e)
        }
    }

    /**
      * Synchronous method that checks the current status of the IPSec process,
      * and returns the status as a string.
      */
    private def checkStatus(): ContainerHealth = {
        if (null == config) {
            return ContainerHealth(Code.STOPPED, "", "")
        }
        val (code, out) = executeWithOutput(config.statusCmd)
        if (code == 0) {
            ContainerHealth(Code.RUNNING, config.ipsecService.name, out)
        } else {
            ContainerHealth(Code.ERROR, config.ipsecService.name, out)
        }
    }

}
