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
package org.midonet.midolman

import java.lang.{Boolean => JBoolean, Integer => JInteger}
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.{ArrayList, Set => JSet, UUID}

import scala.collection.JavaConverters._
import scala.concurrent.{Promise, Future}

import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.reflect._

import akka.actor._
import akka.pattern.{after, pipe}

import com.google.inject.Inject
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.Subscription
import org.midonet.cluster.client
import org.midonet.cluster.client.Port
import org.midonet.cluster.data.TunnelZone.{HostConfig => TZHostConfig}
import org.midonet.cluster.data.TunnelZone.{Type => TunnelType}
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DatapathPortEntangler
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.io._
import org.midonet.midolman.routingprotocols.RoutingManagerActor
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.state.{FlowStateStorage, FlowStateStorageFactory}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{ZoneChanged,
    ZoneMembers, TunnelZoneRequest}
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.rcu.Host
import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.flows.{FlowAction, FlowActionOutput}
import org.midonet.odp.ports._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.functors.Callback0
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.odp.{DpPort, Datapath, OvsConnectionOps}
import org.midonet.packets.IPv4Addr
import org.midonet.sdn.flows.WildcardFlow
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.util.concurrent._

object UnderlayResolver {
    case class Route(srcIp: Int, dstIp: Int, output: FlowActionOutput)
}

trait UnderlayResolver {

    import UnderlayResolver.Route

    /** object representing the current host */
    def host: Host

    /** pair of IPv4 addresses from current host to remote peer host.
     *  None if information not available or peer outside of tunnel Zone. */

    /** Looks up a tunnel route to a remote peer and return a pair of IPv4
     *  addresses as 4B int from current host to remote peer host.
     *  @param  peer a peer midolman UUID
     *  @return first possible tunnel route, or None if unknown peer or no
     *          route to that peer
     */
    def peerTunnelInfo(peer: UUID): Option[Route]

    /** Return the FlowAction for emitting traffic on the vxlan tunnelling port
     *  towards vtep peers. */
    def vtepTunnellingOutputAction: FlowActionOutput

    /** tells if the given portNumber points to the vtep tunnel port. */
    def isVtepTunnellingPort(portNumber: Short): Boolean

    /** tells if the given portNumber points to the overlay tunnel port. */
    def isOverlayTunnellingPort(portNumber: Short): Boolean
}

trait VirtualPortsResolver {

    /** Returns bounded datapath port or None if port not found */
    def getDpPortNumberForVport(vportId: UUID): Option[JInteger]

    /** Returns bounded datapath port or None if port not found */
    def getDpPortForInterface(itfName: String): Option[DpPort]

    /** Returns vport UUID of bounded datapath port, or None if not found */
    def getVportForDpPortNumber(portNum: JInteger): Option[UUID]

    /** Returns bounded datapath port interface or None if port not found */
    def getDpPortName(num: JInteger): Option[String]

}

trait DatapathState extends VirtualPortsResolver with UnderlayResolver

object DatapathController extends Referenceable {

    val log = LoggerFactory.getLogger(classOf[DatapathController])

    override val Name = "DatapathController"

    /**
     * This will make the Datapath Controller to start the local state
     * initialization process.
     */
    case object Initialize

    /** Java API */
    val initializeMsg = Initialize

    val DEFAULT_MTU: Short = 1500

    /**
     * Message sent to the [[org.midonet.midolman.FlowController]] actor to let
     * it know that it can install the the packetIn hook inside the datapath.
     *
     * @param datapath the active datapath
     */
    case class DatapathReady(datapath: Datapath, state: DatapathState)

    /**
     * This message is sent when the separate thread has successfully
     * retrieved all information about the interfaces.
     */
    case class InterfacesUpdate_(interfaces: JSet[InterfaceDescription])

    case class ExistingDatapathPorts_(datapath: Datapath, ports: Set[DpPort])

    /** Signals that the ports in the datapath were cleared */
    case object DatapathClear_

    // Signals that the tunnel ports have been created
    case object TunnelPortsCreated_

    private var cachedMinMtu: Short = DEFAULT_MTU

    def minMtu = cachedMinMtu
}


/**
 * The DP (Datapath) Controller is responsible for managing MidoNet's local
 * kernel datapath. It queries the Virt-Phys mapping to discover (and receive
 * updates about) what virtual ports are mapped to this host's interfaces.
 * It uses the Netlink API to query the local datapaths, create the datapath
 * if it does not exist, create datapath ports for the appropriate host
 * interfaces and learn their IDs (usually a Short), locally track the mapping
 * of datapath port ID to MidoNet virtual port ID. When a locally managed vport
 * has been successfully mapped to a local network interface, the DP Controller
 * notifies the Virtual-Physical Mapping that the vport is ready to receive flows.
 * This allows other Midolman daemons (at other physical hosts) to correctly
 * forward flows that should be emitted from the vport in question.
 * The DP Controller knows when the Datapath is ready to be used and notifies
 * the Flow Controller so that the latter may register for Netlink PacketIn
 * notifications. For any PacketIn that the FlowController cannot handle with
 * the already-installed wildcarded flows, DP Controller receives a PacketIn
 * from the FlowController, translates the arriving datapath port ID to a virtual
 * port UUID and passes the PacketIn to the Simulation Controller. Upon receiving
 * a simulation result from the Simulation Controller, the DP is responsible
 * for creating the corresponding wildcard flow. If the flow is being emitted
 * from a single remote virtual port, this involves querying the Virtual-Physical
 * Mapping for the location of the host responsible for that virtual port, and
 * then building an appropriate tunnel port or using the existing one. If the
 * flow is being emitted from a single local virtual port, the DP Controller
 * recognizes this and uses the corresponding datapath port. Finally, if the
 * flow is being emitted from a PortSet, the DP Controller queries the
 * Virtual-Physical Mapping for the set of hosts subscribed to the PortSet;
 * it must then map each of those hosts to a tunnel and build a wildcard flow
 * description that outputs the flow to all of those tunnels and any local
 * datapath port that corresponds to a virtual port belonging to that PortSet.
 * Finally, the wildcard flow, free of any MidoNet ID references, is pushed to
 * the FlowController.
 *
 * The DP Controller is responsible for managing overlay tunnels (see the
 * previous paragraph).
 *
 * The DP Controller notifies the Flow Validation Engine of any installed
 * wildcard flow so that the FVE may do appropriate indexing of flows (e.g. by
 * the ID of any virtual device that was traversed by the flow). The DP Controller
 * may receive requests from the FVE to invalidate specific wildcard flows; these
 * are passed on to the FlowController.
 */
class DatapathController extends Actor
                         with ActorLogWithoutPath
                         with SingleThreadExecutionContextProvider {

    import DatapathController._
    import FlowController.AddWildcardFlow
    import VirtualToPhysicalMapper.TunnelZoneUnsubscribe
    import context.system

    override def logSource = "org.midonet.datapath-control"

    implicit val logger: Logger = log
    implicit protected def executor = context.dispatcher

    @Inject
    val dpConnPool: DatapathConnectionPool = null

    def datapathConnection = if (dpConnPool != null) dpConnPool.get(0) else null

    @Inject
    val hostService: HostIdProviderService = null

    @Inject
    val interfaceScanner: InterfaceScanner = null

    @Inject
    var midolmanConfig: MidolmanConfig = null

    var datapath: Datapath = null

    @Inject
    var upcallConnManager: UpcallDatapathConnectionManager = null

    @Inject
    var _storageFactory: FlowStateStorageFactory = null

    protected def storageFactory = _storageFactory

    var storage: FlowStateStorage = _

    val dpState = new DatapathStateManager(
        new DatapathPortEntangler.Controller {
            override def addToDatapath(port: String): Future[(DpPort, Int)] = {
                log.debug(s"Creating port $port")
                upcallConnManager.createAndHookDpPort(datapath,
                                                      new NetDevPort(port),
                                                      VirtualMachine)
            }

            override def removeFromDatapath(port: DpPort): Future[_] = {
                log.debug(s"Removing port ${port.getName}")
                upcallConnManager.deleteDpPort(datapath, port)
            }

            override def setVportStatus(port: DpPort, vportId: UUID,
                                        isActive: Boolean): Future[_] = {
                log.info(s"Port ${port.getPortNo}/${port.getName}/$vportId " +
                         s"became ${if (isActive) "active" else "inactive"}")
                VirtualToPhysicalMapper ! LocalPortActive(vportId, isActive)
                invalidateAndInstallTunnelKeyFlow(port, vportId, isActive)
            }
        }
    )(singleThreadExecutionContext, log)

    var recentInterfacesScanned = new java.util.ArrayList[InterfaceDescription]()

    var initializer: ActorRef = system.deadLetters  // only used in tests

    var host: Host = null
    // If a Host message arrives while one is being processed, we stash it
    // in this variable. We don't use Akka's stash here, because we only
    // care about the last Host message (i.e. ignore intermediate messages).
    var nextHost: Host = null

    var portWatcher: Subscription = null
    var portWatcherEnabled = true

    override def preStart(): Unit = {
        super.preStart()
        storage = storageFactory.create()
        context become (DatapathInitializationActor orElse {
            case m =>
                log.info(s"Not handling $m (still initializing)")
        })
    }

    private def subscribeToHost(id: UUID): Unit = {
        val props = Props(classOf[HostRequestProxy], id, storage, self)
                        .withDispatcher(context.props.dispatcher)
        context.actorOf(props, s"HostRequestProxy-$id")
    }

    val DatapathInitializationActor: Receive = {

        case Initialize =>
            initializer = sender()
            subscribeToHost(hostService.getHostId)

        case h: Host =>
            // If we already had the host info, process this after init.
            this.host match {
                case null =>
                    // Only set it if the datapath is known.
                    if (null != h.datapath) {
                        this.host = h
                        dpState.host = h
                        readDatapathInformation(h.datapath)
                    }
                case _ =>
                    this.nextHost = h
            }

        case ExistingDatapathPorts_(datapathObj, ports) =>
            this.datapath = datapathObj
            val conn = new OvsConnectionOps(datapathConnection)
            Future.traverse(ports) { deleteExistingPort(_, conn) } map { _ =>
                DatapathClear_ } pipeTo self

        case DatapathClear_ =>
            makeTunnelPort(OverlayTunnel) { () =>
                GreTunnelPort make "tngre-overlay"
            } flatMap { gre =>
                dpState setTunnelOverlayGre gre
                makeTunnelPort(OverlayTunnel) { () =>
                    val overlayUdpPort = midolmanConfig.getVxLanOverlayUdpPort
                    VxLanTunnelPort make("tnvxlan-overlay", overlayUdpPort)
                }
            } flatMap { vxlan =>
                dpState setTunnelOverlayVxLan vxlan
                makeTunnelPort(VtepTunnel) { () =>
                    val vtepUdpPort = midolmanConfig.getVxLanVtepUdpPort
                    VxLanTunnelPort make("tnvxlan-vtep", vtepUdpPort)
               }
            } map { vtep =>
                dpState setTunnelVtepVxLan vtep
                TunnelPortsCreated_
            } pipeTo self

        case TunnelPortsCreated_ =>
            completeInitialization()
    }

    def deleteExistingPort(port: DpPort, conn: OvsConnectionOps) = port match {
        case internalPort: InternalPort =>
            log.debug("Keeping {} found during initialization", port)
            dpState registerInternalPort internalPort
            Future successful port
        case _ =>
            log.debug("Deleting {} found during initialization", port)
            ensureDeletePort(port, conn)
    }

    def ensureDeletePort(port: DpPort, conn: OvsConnectionOps): Future[DpPort] =
        conn.delPort(port, datapath) recoverWith {
            case ex: Throwable =>
                log.warn("retrying deletion of " + port.getName +
                         " because of {}", ex)
                after(1 second, system.scheduler)(ensureDeletePort(port, conn))
        }

    def makeTunnelPort[P <: DpPort](t: ChannelType)(portFact: () => P)
                                   (implicit tag: ClassTag[P]): Future[P] =
        upcallConnManager.createAndHookDpPort(datapath, portFact(), t) map {
            case (p, _) => p.asInstanceOf[P]
        } recoverWith {
            case ex: Throwable =>
                log.warn(tag + " creation failed: => retrying", ex)
                after(1 second, system.scheduler)(makeTunnelPort(t)(portFact))
        }

    /**
     * Complete initialization and notify the actor that requested init.
     */
    def completeInitialization() {
        log.info("Initialization complete. Starting to act as a controller.")
        context become receive

        Seq[ActorRef](FlowController, PacketsEntryPoint, RoutingManagerActor,
                      initializer) foreach {
            _ ! DatapathReady(datapath, dpState)
        }

        for ((zoneId, _) <- host.zones) {
            VirtualToPhysicalMapper ! TunnelZoneRequest(zoneId)
        }

        if (portWatcherEnabled) {
            log.info("Starting to schedule the port link status updates.")
            portWatcher = interfaceScanner.register(
                new Callback[JSet[InterfaceDescription]] {
                    def onSuccess(data: JSet[InterfaceDescription]) {
                      self ! InterfacesUpdate_(data)
                    }
                    def onError(e: NetlinkException) { /* not called */ }
            })
        }

        log.info(s"Process the host's interface-vport bindings. $host")
        dpState.updateVPortInterfaceBindings(host.ports)
    }

    private def processNextHost() {
        if (null != nextHost) {
            val oldZones = host.zones
            val newZones = nextHost.zones

            host = nextHost
            dpState.host = host
            nextHost = null

            dpState.updateVPortInterfaceBindings(host.ports)
            doDatapathZonesUpdate(oldZones, newZones)
        }
    }

    private def doDatapathZonesUpdate(
            oldZones: Map[UUID, TZHostConfig],
            newZones: Map[UUID, TZHostConfig]) {
        val dropped = oldZones.keySet.diff(newZones.keySet)
        for (zone <- dropped) {
            VirtualToPhysicalMapper ! TunnelZoneUnsubscribe(zone)
            for (tag <- dpState.removePeersForZone(zone)) {
                FlowController ! FlowController.InvalidateFlowsByTag(tag)
            }
        }

        val added = newZones.keySet.diff(oldZones.keySet)
        for (zone <- added) {
            VirtualToPhysicalMapper ! TunnelZoneRequest(zone)
        }
    }

    override def receive: Receive = super.receive orElse {

        // When we get the initialization message we switch into initialization
        // mode and only respond to some messages.
        // When initialization is completed we will revert back to this Actor
        // loop for general message response
        case Initialize =>
            context.become(DatapathInitializationActor)
            // In case there were some scheduled port update checks, cancel them.
            if (portWatcher != null) {
                portWatcher.unsubscribe()
            }
            self ! Initialize

        case h: Host =>
            this.nextHost = h
            processNextHost()

        case m@ZoneMembers(zone, zoneType, members) =>
            log.debug("ZoneMembers event: {}", m)
            if (dpState.host.zones contains zone) {
                for (m <- members) {
                    handleZoneChange(zone, zoneType, m, HostConfigOperation.Added)
                }
            }

        case m@ZoneChanged(zone, zoneType, hostConfig, op) =>
            log.debug("ZoneChanged event: {}", m)
            if (dpState.host.zones contains zone)
                handleZoneChange(zone, zoneType, hostConfig, op)


        case InterfacesUpdate_(interfaces) =>
            dpState.updateInterfaces(interfaces)
            setTunnelMtu(interfaces)
    }

    def handleZoneChange(zone: UUID, t: TunnelType, config: TZHostConfig,
                         op: HostConfigOperation.Value) {

        if (config.getId == dpState.host.id)
            return

        val peerUUID = config.getId

        op match {
            case HostConfigOperation.Added => processAddPeer()
            case HostConfigOperation.Deleted => processDelPeer()
        }

        def processTags(tags: TraversableOnce[FlowTag]): Unit = tags.foreach {
            FlowController ! FlowController.InvalidateFlowsByTag(_)
        }

        def processDelPeer(): Unit =
            processTags(dpState.removePeer(peerUUID, zone))

        def processAddPeer() =
            host.zones.get(zone) map { _.getIp.toInt } match {
                case Some(srcIp) =>
                    val dstIp = config.getIp.toInt
                    processTags(dpState.addPeer(peerUUID, zone, srcIp, dstIp, t))
                case None =>
                    log.info("Could not find this host's ip for zone {}", zone)
            }

    }

    private def installTunnelKeyFlow(port: DpPort, exterior: client.Port): Unit = {
        val fc = FlowController

        val wMatch = new WildcardMatch().setTunnelKey(exterior.tunnelKey)
        val actions = List[FlowAction](port.toOutputAction)
        val tags = Set(FlowTagger.tagForDpPort(port.getPortNo.shortValue))
        fc ! AddWildcardFlow(WildcardFlow(wcmatch = wMatch, actions = actions),
                             null, new ArrayList[Callback0](), tags)

        // Packets for the port may have arrived before the port came up and
        // made us install temporary drop flows, so we invalidate those flows.
        // The order of these two messages to the FlowController is strict, as
        // we want to invalidate the flows after the new WildcarFlow is in place.
        fc ! FlowController.InvalidateFlowsByTag(
                FlowTagger.tagForTunnelKey(exterior.tunnelKey))

        log.info(s"Added flow for tunnelkey ${exterior.tunnelKey}")
    }

    private def updateTunnelKeyFlow(dpPort: DpPort, port: Port,
                                    active: Boolean): Unit = {
        if (port.isInterior) {
            log.warn(s"local port $port active state changed, but it's not " +
                      "Exterior, don't know what to do with it")
            return
        }

        // Trigger invalidation. This is done regardless of whether we are
        // activating or deactivating:
        //   - The case for invalidating on deactivation is obvious.
        //   - On activation we invalidate flows for this dp port number in case
        //     it has been reused by the dp: we want to start with a clean state
        FlowController ! InvalidateFlowsByTag(
            FlowTagger.tagForDpPort(dpPort.getPortNo.shortValue()))

        if (active) {
            installTunnelKeyFlow(dpPort, port)
        }
    }

    private def invalidateAndInstallTunnelKeyFlow(port: DpPort, vif: UUID,
                                                  active: Boolean): Future[_] = {
        val p = Promise[Any]()
        try {
            val vPort = VirtualTopologyActor.tryAsk[Port](vif)
            updateTunnelKeyFlow(port, vPort, active)
            p success null
        } catch { case NotYetException(f, _) =>
            f.mapTo[Port] onComplete {
                case Success(vPort) =>
                    updateTunnelKeyFlow(port, vPort, active)
                    p success null
                case Failure(ex) =>
                    log.error("failed to install tunnel key flow", ex)
            }
        }
        p.future
    }

    private def setTunnelMtu(interfaces: JSet[InterfaceDescription]) = {
        def addressesMatch(inetAddress: InetAddress, ip: IPv4Addr): Boolean =
            ByteBuffer.wrap(inetAddress.getAddress).getInt == ip.toInt

        var minMtu = Short.MaxValue
        val overhead = VxLanTunnelPort.TunnelOverhead

        for { intf <- interfaces.asScala
              inetAddress <- intf.getInetAddresses.asScala
              if inetAddress.getAddress.length == 4
              zone <- host.zones
              if addressesMatch(inetAddress, zone._2.getIp) &&
                 zone._2.isInstanceOf[TZHostConfig]
        } {
            val tunnelMtu = (intf.getMtu - overhead).toShort
            minMtu = minMtu.min(tunnelMtu)
        }

        if (minMtu == Short.MaxValue)
            minMtu = DEFAULT_MTU

        if (cachedMinMtu != minMtu) {
            log.info(s"Changing MTU from $cachedMinMtu to $minMtu")
            cachedMinMtu = minMtu
        }
    }

    /*
     * ONLY USE THIS DURING INITIALIZATION.
     */
    private def readDatapathInformation(wantedDatapath: String) {
        def handleExistingDP(dp: Datapath) {
            log.info("The datapath already existed. Flushing the flows.")
            datapathConnection.flowsFlush(dp,
                new Callback[JBoolean] {
                    def onSuccess(data: JBoolean) {}
                    def onError(ex: NetlinkException) {
                        log.error("Failed to flush the Datapath's flows!")
                    }
                }
            )
            // Query the datapath ports without waiting for the flush to exit.
            queryDatapathPorts(dp)
        }
        log.info("Wanted datapath: {}", wantedDatapath)

        val retryTask = new Runnable {
            def run() {
                readDatapathInformation(wantedDatapath)
            }
        }

        val dpCreateCallback = new Callback[Datapath] {
            def onSuccess(data: Datapath) {
                log.info("Datapath created {}", data)
                queryDatapathPorts(data)
            }
            def onError(ex: NetlinkException) {
                log.error("Datapath creation failure", ex)
                system.scheduler.scheduleOnce(100 millis, retryTask)
            }
        }

        val dpGetCallback = new Callback[Datapath] {
            def onSuccess(dp: Datapath) {
                handleExistingDP(dp)
            }
            def onError(ex: NetlinkException) {
                ex.getErrorCodeEnum match {
                    case ErrorCode.ENODEV =>
                        log.info("Datapath is missing. Creating.")
                        datapathConnection.datapathsCreate(
                            wantedDatapath, dpCreateCallback)
                    case ErrorCode.ETIMEOUT =>
                        log.error("Timeout while getting the datapath")
                        system.scheduler.scheduleOnce(100 millis, retryTask)
                    case other =>
                        log.error("Unexpected error while getting datapath", ex)
                }
            }
        }

        datapathConnection.datapathsGet(wantedDatapath, dpGetCallback)
    }

    /*
     * ONLY USE THIS DURING INITIALIZATION.
     */
    private def queryDatapathPorts(datapath: Datapath) {
        log.debug("Enumerating ports for datapath: " + datapath)
        datapathConnection.portsEnumerate(datapath,
            new Callback[JSet[DpPort]] {
                def onSuccess(ports: JSet[DpPort]) {
                    self ! ExistingDatapathPorts_(datapath, ports.asScala.toSet)
                }
                // WARN: this is ugly. Normally we should configure
                // the message error handling inside the router
                def onError(ex: NetlinkException) {
                    system.scheduler.scheduleOnce(100 millis, new Runnable {
                        def run() {
                            queryDatapathPorts(datapath)
                        }
                    })
                }
            }
        )
    }
}

/** class which manages the state changes triggered by message receive by
 *  the DatapathController. It also exposes the DatapathController managed
 *  data to clients for WilcardFlow translation. */
class DatapathStateManager(val controller: DatapathPortEntangler.Controller)
                          (implicit val ec: SingleThreadExecutionContext,
                                    val log: Logger)
                          extends DatapathState with DatapathPortEntangler {

    import UnderlayResolver.Route

    var tunnelOverlayGre: GreTunnelPort = _
    var tunnelOverlayVxLan: VxLanTunnelPort = _
    var tunnelVtepVxLan: VxLanTunnelPort = _

    var greOverlayTunnellingOutputAction: FlowActionOutput = _
    var vxlanOverlayTunnellingOutputAction: FlowActionOutput = _
    var vtepTunnellingOutputAction: FlowActionOutput = _

    /** set the DPC reference to the gre tunnel port bound in the datapath */
    def setTunnelOverlayGre(port: GreTunnelPort) = {
        tunnelOverlayGre = port
        greOverlayTunnellingOutputAction = port.toOutputAction
        log.info(s"gre overlay tunnel port was assigned to $port")
    }

    /** set the DPC reference to the vxlan tunnel port bound in the datapath */
    def setTunnelOverlayVxLan(port: VxLanTunnelPort) = {
        tunnelOverlayVxLan = port
        vxlanOverlayTunnellingOutputAction = port.toOutputAction
        log.info(s"vxlan overlay tunnel port was assigned to $port")
    }

    /** set the DPC reference to the vxlan tunnel port bound in the datapath */
    def setTunnelVtepVxLan(port: VxLanTunnelPort) = {
        tunnelVtepVxLan = port
        vtepTunnellingOutputAction = port.toOutputAction
        log.info(s"vxlan vtep tunnel port was assigned to $port")
    }

    def isVtepTunnellingPort(portNumber: Short) =
        tunnelVtepVxLan.getPortNo == portNumber

    def isOverlayTunnellingPort(portNumber: Short) =
        tunnelOverlayGre.getPortNo == portNumber ||
        tunnelOverlayVxLan.getPortNo == portNumber

    /** reference to the current host information. Used to query this host ip
     *  when adding tunnel routes to peer host for given zone uuid. */
    var host: Host = _

    /** 2D immutable map of peerUUID -> zoneUUID -> (srcIp, dstIp, outputAction)
     *  this map stores all the possible underlay routes from this host
     *  to remote midolman host, with the tunnelling output action.
     */
    var _peersRoutes = Map[UUID,Map[UUID,Route]]()

    override def peerTunnelInfo(peer: UUID) =
        _peersRoutes get peer flatMap { _.values.headOption }

    /** add route info about peer for given zone and retrieve ip for this host
     *  and for this zone from dpState.
     *  @param  peer  remote host UUID
     *  @param  zone  zone UUID the underlay route to add is associated to.
     *  @param  srcIp the underlay ip of this host
     *  @param  dstIp the underlay ip of the remote host
     *  @param  t the tunnelling protocol type
     *  @return possible tags to send to the FlowController for invalidation
     */
    def addPeer(peer: UUID, zone: UUID,
                srcIp: Int, dstIp: Int, t: TunnelType): Seq[FlowTag] = {
        val outputAction = t match {
            case TunnelType.vtep => return Seq.empty[FlowTag]
            case TunnelType.gre => greOverlayTunnellingOutputAction
            case TunnelType.vxlan => vxlanOverlayTunnellingOutputAction
        }

        val newRoute = Route(srcIp, dstIp, outputAction)
        log.info(s"new tunnel route $newRoute to peer $peer")

        val routes = _peersRoutes getOrElse (peer, Map[UUID,Route]())
        // invalidate the old route if overwrite
        val oldRoute = routes get zone

        _peersRoutes += ( peer -> ( routes + (zone -> newRoute) ) )
        val tags = FlowTagger.tagForTunnelRoute(srcIp, dstIp) :: Nil

        oldRoute.fold(tags) { case Route(src, dst, _) =>
            FlowTagger.tagForTunnelRoute(src, dst) :: tags
        }
    }

    /** delete a tunnel route info about peer for given zone.
     *  @param  peer  remote host UUID
     *  @param  zone  zone UUID the underlay route to remove is associated to.
     *  @return possible tag to send to the FlowController for invalidation
     */
    def removePeer(peer: UUID, zone: UUID): Option[FlowTag] =
        (_peersRoutes get peer) flatMap { _ get zone } map {
            case r@Route(srcIp,dstIp,_) =>
                log.info(s"removing tunnel route $r to peer $peer")
                    // TODO(hugo): remove nested map if becomes empty (mem leak)
                    _peersRoutes += (peer -> (_peersRoutes(peer) - zone))
                    FlowTagger.tagForTunnelRoute(srcIp,dstIp)
                }

    /** delete all tunnel routes associated with a given zone
     *  @param  zone zone uuid
     *  @return sequence of tags to send to the FlowController for invalidation
     */
    def removePeersForZone(zone: UUID): Seq[FlowTag] =
        _peersRoutes.keys.toSeq.flatMap{ removePeer(_, zone) }

    override def getDpPortForInterface(itfName: String): Option[DpPort] =
        interfaceToDpPort.get(itfName)

    // FIXME: these methods are called from the fast-path; they allocate
    //        closures and Options.
    override def getDpPortNumberForVport(vportId: UUID): Option[JInteger] =
        interfaceToVport.inverse.get(vportId) flatMap { itfName =>
            interfaceToDpPort.get(itfName) map { _.getPortNo }
        }

    override def getVportForDpPortNumber(portNum: JInteger): Option[UUID] =
        dpPortNumToInterface get portNum flatMap { interfaceToVport.get }

    override def getDpPortName(num: JInteger): Option[String] =
        dpPortNumToInterface.get(num)
}
