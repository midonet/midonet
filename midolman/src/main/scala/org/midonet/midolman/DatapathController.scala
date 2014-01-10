/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.lang.{Boolean => JBoolean, Integer => JInteger}
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.{Collection => JCollection, List => JList, Set => JSet, UUID}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.{Set => ROSet}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import akka.actor._
import akka.event.LoggingAdapter
import akka.util.Timeout
import com.google.inject.Inject

import org.midonet.cluster.client
import org.midonet.cluster.data.TunnelZone
import org.midonet.cluster.data.TunnelZone.{HostConfig => TZHostConfig}
import org.midonet.cluster.data.zones._
import org.midonet.midolman.datapath._
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.monitoring.MonitoringActor
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.rcu.Host
import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.{PortOptions, Port, Ports, Datapath}
import org.midonet.odp.flows.{FlowAction, FlowActionOutput}
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.ports._
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.packets.IPv4Addr
import org.midonet.sdn.flows.WildcardFlow
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.util.collection.Bimap

trait UnderlayResolver {

    /** object representing the current host */
    def host: Host

    /** pair of IPv4 addresses from current host to remote peer host.
     *  None if information not available or peer outside of tunnel Zone. */

    /** looks for a tunnel route to a remote peer and return a pair of IPv4
     *  addresses as 4B int from current host to remote peer host.
     *  @param  peer a peer midolman UUID
     *  @return first possible tunnel route, or None if unknown peer or no
     *          route to that peer
     */
    def peerTunnelInfo(peer: UUID): Option[(Int,Int)]

    /** reference to the datapath GRE tunnel port.
     *  None if port is not available. */
    def tunnelGre: Option[Port[_,_]]

    def greOutputAction: Option[FlowActionOutput]

}

trait VirtualPortsResolver {

    /** Returns bounded datapath port or None if port not found */
    def getDpPortNumberForVport(vportId: UUID): Option[JInteger]

    /** Returns bounded datapath port or None if port not found */
    def getDpPortForInterface(itfName: String): Option[Port[_, _]]

    /** Returns vport UUID of bounded datapath port, or None if not found */
    def getVportForDpPortNumber(portNum: JInteger): Option[UUID]

    /** Returns bounded datapath port interface or None if port not found */
    def getDpPortName(num: JInteger): Option[String]

}

trait DatapathState extends VirtualPortsResolver with UnderlayResolver {

    // TODO(guillermo) - for future use. Decisions based on the datapath state
    // need to be aware of its versioning because flows added by fast-path
    // simulations race with invalidations sent out-of-band by the datapath
    // controller to the flow controller.
    def version: Long

    // provide the netlink pid of the current datapath connection
    def uplinkPid: Int

}

object DatapathController extends Referenceable {

    override val Name = "DatapathController"

    /**
     * This will make the Datapath Controller to start the local state
     * initialization process.
     */
    case object Initialize

    /** Java API */
    val initializeMsg = Initialize

    /**
     * Reply sent back to the sender of the Initialize message when the basic
     * initialization of the datapath is complete.
     */
    case object InitializationComplete

    /**
     * Message sent to the [[org.midonet.midolman.FlowController]] actor to let
     * it know that it can install the the packetIn hook inside the datapath.
     *
     * @param datapath the active datapath
     */
    case class DatapathReady(datapath: Datapath, state: DatapathState)

    /**
     * Message to ask a port operation inside the datapath. The operation can
     * either be a create or delete request according to the return value of
     * DpPortRequest#op. The sender will receive a DpPortReply holding the
     * original request and which can be either of type DpPortSuccess to
     * indicate a successful create/delete operation in the datapath or a
     * DpPortError indicating some kind of error occured.
     */
    sealed trait DpPortRequest {
        type TypedPort <: Port[_ <: PortOptions, TypedPort]
        val port: TypedPort
        val tag: Option[AnyRef]
        def update(p: TypedPort): DpPortRequest
        def successReply(createdPort: Port[_,_]) =
            DpPortSuccess(update(createdPort.asInstanceOf[TypedPort]))
        def errorReply(timeout: Boolean, error: NetlinkException) =
            DpPortError(this, timeout, error)
    }

    sealed trait DpPortCreate extends DpPortRequest
    sealed trait DpPortDelete extends DpPortRequest

    sealed trait DpPortReply { val request: DpPortRequest }

    case class DpPortSuccess(request: DpPortRequest) extends DpPortReply

    case class DpPortError(
        request: DpPortRequest, timeout: Boolean, error: NetlinkException
    ) extends DpPortReply


    trait InternalDpPortHolder { type TypedPort = InternalPort }

    case class DpPortCreateInternal(port: InternalPort, tag: Option[AnyRef])
            extends DpPortCreate with InternalDpPortHolder {
        override def update(p: InternalPort) = DpPortCreateInternal(p, this.tag)
    }

    case class DpPortDeleteInternal(port: InternalPort, tag: Option[AnyRef])
            extends DpPortDelete with InternalDpPortHolder {
        override def update(p: InternalPort) = DpPortDeleteInternal(p, this.tag)
    }


    trait NetDevDpPortHolder { type TypedPort = NetDevPort }

    case class DpPortCreateNetdev(port: NetDevPort, tag: Option[AnyRef])
            extends DpPortCreate with NetDevDpPortHolder {
        override def update(p: NetDevPort) = DpPortCreateNetdev(p, this.tag)
    }

    case class DpPortDeleteNetdev(port: NetDevPort, tag: Option[AnyRef])
            extends DpPortDelete with NetDevDpPortHolder {
        override def update(p: NetDevPort) = DpPortDeleteNetdev(p, this.tag)
    }


    trait GreDpPortHolder { type TypedPort = GreTunnelPort }

    case class DpPortCreateGreTunnel(port: GreTunnelPort, tag: Option[AnyRef])
            extends DpPortCreate with GreDpPortHolder {
        override def update(p: GreTunnelPort) = DpPortCreateGreTunnel(p, this.tag)
    }

    case class DpPortDeleteGreTunnel(port: GreTunnelPort, tag: Option[AnyRef])
            extends DpPortDelete with GreDpPortHolder {
        override def update(p: GreTunnelPort) = DpPortDeleteGreTunnel(p, this.tag)
    }

   /**
    * This message encapsulates a given port stats to the monitoring agent.
    * @param stats port stats
    */
    case class DpPortStats(portID: UUID, stats: Port.Stats)

    /**
     * This message requests stats for a given port.
     * @param portID port id
     */
    case class DpPortStatsRequest(portID: UUID)

    /**
     * This message is sent every 2 seconds to check that the kernel contains
     * exactly the same ports/interfaces as the system. In case that somebody
     * uses a command line tool (for example) to bring down an interface, the
     * system will react to it.
     * TODO this version is constantly checking for changes. It should react to
     * 'netlink' notifications instead.
     */
    case class _CheckForPortUpdates(datapathName: String)

    /**
     * This message is sent when the separate thread has succesfully retrieved
     * all information about the interfaces.
     */
    case class _InterfacesUpdate(interfaces: JList[InterfaceDescription])

    /**
     * This message is sent when the DHCP handler needs to get information
     * on local interfaces that are used for tunnels, what it returns is
     * { interface description , list of {tunnel type} } where the
     * interface description contains various information (including MTU)
     */
    case object LocalTunnelInterfaceInfo

    /**
     * This message is sent when the LocalTunnelInterfaceInfo handler
     * completes the interface scan and pass the result as well as
     * original sender info
     */
    case class _LocalTunnelInterfaceInfoFinal(caller : ActorRef,
                                        interfaces: JList[InterfaceDescription])

    case class _SetLocalDatapathPorts(datapath: Datapath, ports: Set[Port[_, _]])

    def calculateMinMtu()(implicit sys: ActorSystem, ec: ExecutionContext)
    : Future[Option[Short]] = {
        implicit val timeout =  new Timeout(3 second)
        (DatapathController ? LocalTunnelInterfaceInfo)
            .mapTo[mutable.MultiMap[InterfaceDescription, TunnelZone.Type]]
            .map { minMtu }
    }

    /**
     * Choose the min MTU based on all the given interface descriptions, if
     * there are no interfaces or there is no way of figuring out, will
     * return 1500 as default value.
     */
    private def minMtu(ifcTunnelList: mutable.MultiMap[InterfaceDescription,
                                                       TunnelZone.Type]) = {
        var minMtu: Short = 0
        ifcTunnelList foreach {
            case (interfaceDesc, tunnelTypeList) =>
                tunnelTypeList foreach { tunnelType => {
                    val overhead = tzTypeOverheads(tunnelType)
                    val intfMtu = interfaceDesc.getMtu.toShort
                    val tunnelMtu = (intfMtu - overhead).toShort
                    minMtu = if (minMtu == 0) tunnelMtu
                             else minMtu.min(tunnelMtu)
                 }}
            case _ => // unexpected
        }
        Some(if (minMtu == 0) 1500.toShort else minMtu)
    }

    // TODO: this belongs in TunnelZone
    private val tzTypeOverheads = Map(
        TunnelZone.Type.Gre -> GreTunnelZone.TUNNEL_OVERHEAD,
        TunnelZone.Type.Capwap -> CapwapTunnelZone.TUNNEL_OVERHEAD,
        TunnelZone.Type.Ipsec -> IpsecTunnelZone.TUNNEL_OVERHEAD)

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
class DatapathController extends Actor with ActorLogging with FlowTranslator {
    import DatapathController._
    import FlowController.AddWildcardFlow
    import PacketWorkflow.AddVirtualWildcardFlow
    import VirtualPortManager.Controller
    import VirtualToPhysicalMapper._
    import VirtualTopologyActor.PortRequest

    implicit val logger: LoggingAdapter = log

    override implicit val requestReplyTimeout: Timeout = new Timeout(1 second)
    override val cookieStr: String = ""

    override protected implicit val system = context.system

    @Inject
    val datapathConnection: OvsDatapathConnection = null

    @Inject
    val hostService: HostIdProviderService = null

    @Inject
    val interfaceScanner: InterfaceScanner = null

    var datapath: Datapath = null

    val dpState = new DatapathStateManager(
        new Controller {
            override def addToDatapath(itfName: String): Unit = {
                log.debug("VportManager requested add port {}", itfName)
                val port = Ports.newNetDevPort(itfName)
                createDatapathPort(self, DpPortCreateNetdev(port, None))
            }

            override def removeFromDatapath(port: Port[_, _]): Unit = {
                log.debug("VportManager requested remove port {}", port.getName)
                val netdevPort = port.asInstanceOf[NetDevPort]
                deleteDatapathPort(self, DpPortDeleteNetdev(netdevPort, None))
            }

            override def setVportStatus(port: Port[_, _], vportId: UUID,
                                        isActive: Boolean): Unit = {
                log.debug("Port {}/{}/{} became {}",
                          port.getPortNo, port.getName, vportId,
                          if (isActive) "active" else "inactive")
                installTunnelKeyFlow(port, vportId, isActive)
                VirtualToPhysicalMapper ! LocalPortActive(vportId, isActive)
            }
        }
    )

    var recentInterfacesScanned = new java.util.ArrayList[InterfaceDescription]()

    var pendingUpdateCount = 0

    var initializer: ActorRef = null

    var host: Host = null
    // If a Host message arrives while one is being processed, we stash it
    // in this variable. We don't use Akka's stash here, because we only
    // care about the last Host message (i.e. ignore intermediate messages).
    var nextHost: Host = null

    var portWatcher: Cancellable = null
    var portWatcherEnabled = true

    override def preStart() {
        super.preStart()
        context become (DatapathInitializationActor orElse {
            case m =>
                log.info("Not handling {} (behaving as InitializationActor)", m)
        })
        if (datapathConnection != null)
          dpState.uplinkPid =
            datapathConnection.getChannel.getLocalAddress.getPid
        else
          log.warning("preStart(): OvsDatapathConnection not yet initialized.")
    }

    def receive = null

    val DatapathInitializationActor: Receive = {

        // Initialization request message
        case Initialize =>
            initializer = sender
            log.info("Initialize from: " + sender)
            VirtualToPhysicalMapper ! HostRequest(hostService.getHostId)

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

        case _SetLocalDatapathPorts(datapathObj, ports) =>
            this.datapath = datapathObj
            ports.foreach {
                //TODO: check we can safely recycle existing ports (MN-128)
                // not the case for port created by the BGP actor !
                case p: GreTunnelPort =>
                    deleteDatapathPort(self, DpPortDeleteGreTunnel(p, None))
                case p: NetDevPort =>
                    deleteDatapathPort(self, DpPortDeleteNetdev(p, None))
                case p =>
                    log.debug("Keeping port {} found during initialization", p)
                    dpState.dpPortAdded(p)
            }
            log.debug("Finished processing datapath's existing ports. " +
                "Pending updates {}", pendingUpdateCount)

            log.debug("Initial creation of GRE tunnel port")

            createDatapathPort(
                self, DpPortCreateGreTunnel(GreTunnelPort.make("tngre-mm"), null))

            if (checkInitialization)
                completeInitialization()


        // Handle personal create/delete/reply port message
        case req: DpPortCreate if sender == self =>
            log.debug("Received {} message from myself", req)
            createDatapathPort(self, req)

        case req: DpPortDelete if sender == self =>
            log.debug("Received {} message from myself", req)
            deleteDatapathPort(self, req)

        case opReply: DpPortReply if sender == self =>
            pendingUpdateCount -= 1
            log.debug("Pending update(s) {}", pendingUpdateCount)
            handlePortOperationReply(opReply)
            if (checkInitialization)
                completeInitialization()
    }

    /** checks if the DPC can switch to regular Receive loop */
    private def checkInitialization: Boolean =
        pendingUpdateCount == 0 && dpState.tunnelGre.isDefined

    /**
     * Complete initialization and notify the actor that requested init.
     */
    private def completeInitialization() {
        log.info("Initialization complete. Starting to act as a controller.")
        context.become(DatapathControllerActor orElse {
            case m =>
                log.warning("Unhandled message {}", m)
        })
        FlowController ! DatapathReady(datapath, dpState)
        DeduplicationActor ! DatapathReady(datapath, dpState)
        for ((zoneId, zone) <- host.zones) {
            VirtualToPhysicalMapper ! TunnelZoneRequest(zoneId)
        }
        if (portWatcherEnabled) {
            // schedule port requests.
            log.info("Starting to schedule the port link status updates.")
            portWatcher = system.scheduler.schedule(1 second, 2 seconds,
                self, _CheckForPortUpdates(datapath.getName))
        }
        initializer ! InitializationComplete
        log.info("Process the host's zones and vport bindings. {}", host)
        dpState.updateVPortInterfaceBindings(host.ports)
    }

    private def processNextHost() {
        if (null != nextHost && pendingUpdateCount == 0) {
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
            oldZones: Map[UUID, TZHostConfig[_, _]],
            newZones: Map[UUID, TZHostConfig[_, _]]) {
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

    val DatapathControllerActor: Receive = {

        // When we get the initialization message we switch into initialization
        // mode and only respond to some messages.
        // When initialization is completed we will revert back to this Actor
        // loop for general message response
        case Initialize =>
            context.become(DatapathInitializationActor)
            // In case there were some scheduled port update checks, cancel them.
            if (portWatcher != null) {
                portWatcher.cancel()
            }
            self ! Initialize

        case AddVirtualWildcardFlow(flow, callbacks, tags) =>
            log.debug("Translating and installing wildcard flow: {}", flow)
            translateVirtualWildcardFlow(flow, tags) onSuccess {
                case (finalFlow, finalTags) =>
                    log.debug("flow translated, installing: {}", finalFlow)
                    FlowController !
                        AddWildcardFlow(finalFlow, None, callbacks, finalTags)
            }

        case h: Host =>
            this.nextHost = h
            processNextHost()

        case zoneMembers: ZoneMembers[_] =>
            log.debug("ZoneMembers event: {}", zoneMembers)
            if (dpState.host.zones contains zoneMembers.zone) {
                val zone = zoneMembers.zone
                for (member <- zoneMembers.members) {
                    val config = member.asInstanceOf[TZHostConfig[_,_]]
                    handleZoneChange(zone, config, HostConfigOperation.Added)
                }
            }

        case m: ZoneChanged[_] =>
            log.debug("ZoneChanged: {}", m)
            val config = m.hostConfig.asInstanceOf[TZHostConfig[_,_]]
            if (dpState.host.zones contains m.zone)
                handleZoneChange(m.zone, config, m.op)

        case req: DpPortCreate =>
            log.debug("Got {} from {}", req, sender)
            createDatapathPort(sender, req)

        case req: DpPortDelete =>
            deleteDatapathPort(sender, req)

        case opReply: DpPortReply =>
            pendingUpdateCount -= 1
            log.debug("Pending update(s) {}", pendingUpdateCount)
            handlePortOperationReply(opReply)
            if(pendingUpdateCount == 0)
                processNextHost()

        case DpPortStatsRequest(portID) =>
            dpState.getInterfaceForVport(portID) match {
                case Some(portName) =>
                    datapathConnection.portsGet(portName, datapath,
                        new Callback[Port[_,_]]{
                            def onSuccess(data: Port[_, _]) {
                                MonitoringActor !
                                        DpPortStats(portID, data.getStats)
                            }

                            def onTimeout() {
                                log.error("Timeout when retrieving port stats")
                            }

                            def onError(e: NetlinkException) {
                                log.error("Error retrieving port stats for " +
                                    "port {}({}): {}",
                                    Array(portID, portName, e))
                            }
                        }
                    )

                case None =>
                    log.debug("Port was not found {}", portID)
            }

        case _CheckForPortUpdates(datapathName: String) =>
            checkPortUpdates()

        case _InterfacesUpdate(interfaces) =>
            dpState.updateInterfaces(interfaces)

        case LocalTunnelInterfaceInfo =>
            localTunnelInterfaceInfoPhaseOne(sender)

        case _LocalTunnelInterfaceInfoFinal(caller, interfaces) =>
            localTunnelInterfaceInfoPhaseTwo(caller, interfaces)
    }

    def checkPortUpdates() {
        interfaceScanner.scanInterfaces(new Callback[JList[InterfaceDescription]] {
            def onError(e: NetlinkException) {
                log.error("Error while retrieving the interface status:" + e.getMessage)
            }

            def onTimeout() {
                log.error("Timeout while retrieving the interface status.")
            }

            def onSuccess(data: JList[InterfaceDescription]) {
                self ! _InterfacesUpdate(data)
            }
        })
    }

    def handleZoneChange(zone: UUID, config: TZHostConfig[_,_],
                         op: HostConfigOperation.Value) {

        if (config.getId == dpState.host.id)
            return

        log.debug("Zone {} member {} change events: {}", zone, config, op)

        val peerUUID = config.getId

        op match {
            case HostConfigOperation.Added => processAddPeer()
            case HostConfigOperation.Deleted => processDelPeer()
        }

        def processTags(tags: TraversableOnce[Any]): Unit = tags.foreach {
            FlowController ! FlowController.InvalidateFlowsByTag(_)
        }

        def processDelPeer(): Unit =
            processTags(dpState.removePeer(peerUUID, zone))

        def processAddPeer() =
            host.zones.get(zone) map { _.getIp.addressAsInt() } match {
                case Some(srcIp) =>
                    val ipPair = (srcIp, config.getIp.addressAsInt)
                    processTags(dpState.addPeer(peerUUID, zone, ipPair))
                case None =>
                    log.info("Could not find this host's ip for zone {}", zone)
            }

    }

    private def installTunnelKeyFlow(
            port: Port[_, _], exterior: client.Port[_]): Unit = {
        val fc = FlowController
        // packets for the port may have arrived before the
        // port came up and made us install temporary drop flows.
        // Invalidate them before adding the new flow
        fc ! FlowController.InvalidateFlowsByTag(
            FlowTagger.invalidateByTunnelKey(exterior.tunnelKey))

        val wMatch = new WildcardMatch().setTunnelID(exterior.tunnelKey)
        val actions = List[FlowAction[_]](output(port.getPortNo.shortValue))
        val tags = Set[Any](FlowTagger.invalidateDPPort(port.getPortNo.shortValue()))
        fc ! AddWildcardFlow(WildcardFlow(wcmatch = wMatch, actions = actions),
                             None, ROSet.empty, tags)
        log.debug("Added flow for tunnelkey {}", exterior.tunnelKey)
    }

    private def installTunnelKeyFlow(
            port: Port[_, _], vifId: UUID, active: Boolean): Unit =
        VirtualTopologyActor.expiringAsk(PortRequest(vifId), log) onSuccess {
            case p if !p.isInterior =>
                // trigger invalidation. This is done regardless of
                // whether we are activating or deactivating:
                //
                //   + The case for invalidating on deactivation is
                //     obvious.
                //   + On activation we invalidate flows for this dp port
                //     number in case it has been reused by the dp: we
                //     want to start with a clean state.
                FlowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateDPPort(port.getPortNo.shortValue()))
                if (active)
                    installTunnelKeyFlow(port, p)
            case _ =>
                log.warning("local port {} activated, but it's not an " +
                    "ExteriorPort: I don't know what to do with it: {}", port)
        }

    def handlePortOperationReply(opReply: DpPortReply) {
        log.debug("Port operation reply: {}", opReply)

        opReply match {

            case DpPortSuccess(DpPortCreateGreTunnel(p, _)) =>
                dpState.setTunnelGre(Some(p))

            case DpPortError(req @ DpPortCreateGreTunnel(p, tags), _, _) =>
                log.warning(
                    "GRE port creation failed: {} => scheduling retry", opReply)
                system.scheduler.scheduleOnce(5 second, self, req)

            case DpPortSuccess(DpPortCreateNetdev(p, _)) =>
                dpState.dpPortAdded(p)

            case DpPortSuccess(DpPortDeleteNetdev(p, _)) =>
                dpState.dpPortRemoved(p)

            case DpPortError(DpPortCreateNetdev(p, tag), false, ex) =>
                if (ex != null) {
                    log.warning("port {} creation failed: OVS returned {}",
                        p, ex.getErrorCodeEnum)
                    // This will make the vport manager retry the create op
                    // the next time the interfaces are scanned (2 secs).
                    if (ex.getErrorCodeEnum == ErrorCode.EBUSY)
                        dpState.dpPortForget(p)
                }

            case DpPortError(_: DpPortDelete, _, _) =>
                log.warning("Failed DpPortDelete {}", opReply)

            case DpPortError(_, _, _) =>
                log.warning("not handling DpPortError reply {}", opReply)

            case _ =>
                log.debug("not handling port op reply {}", opReply)
        }

        /** used in tests only */
        opReply match {
            case DpPortSuccess(req) =>
                system.eventStream.publish(req)
            case _ => // ignore, but explicitly to avoid warning
        }

    }

    def createDatapathPort(caller: ActorRef, request: DpPortCreate) {
        if (caller == self)
            pendingUpdateCount += 1
        log.info("creating port: {} (by request of: {})", request.port, caller)

        datapathConnection.portsCreate(datapath, request.port,
            new ErrorHandlingCallback[Port[_, _]] {
                def onSuccess(data: Port[_, _]) {
                    caller ! request.successReply(data)
                }

                def handleError(ex: NetlinkException, timeout: Boolean) {
                    caller ! request.errorReply(timeout, ex)
                }
            })
    }

    def deleteDatapathPort(caller: ActorRef, request: DpPortDelete) {
        if (caller == self)
            pendingUpdateCount += 1
        log.info("deleting port: {} (by request of: {})", request.port, caller)

        datapathConnection.portsDelete(request.port, datapath,
            new ErrorHandlingCallback[Port[_, _]] {
                def onSuccess(data: Port[_, _]) {
                    caller ! request.successReply(data)
                }

                def handleError(ex: NetlinkException, timeout: Boolean) {
                    // check if the port has already been removed, if that's the
                    // case we can consider that the delete operation succeeded
                    if (ex.getErrorCodeEnum ==
                        NetlinkException.ErrorCode.ENOENT) {
                        caller ! request.errorReply(timeout = false, null)
                    } else {
                        caller ! request.errorReply(timeout = false, ex)
                    }
                }
        })
    }

    private def localTunnelInterfaceInfoPhaseOne(caller: ActorRef) {
        if (!recentInterfacesScanned.isEmpty) {
            log.debug("Interface Scanning took place and cache is hot")
            localTunnelInterfaceInfoPhaseTwo(caller, recentInterfacesScanned)
        } else {
            log.debug("Interface Scanning has not taken place, trigger scan")
            interfaceScanner.scanInterfaces(new Callback[JList[InterfaceDescription]] {
                def onError(e: NetlinkException) {
                    log.error("Error while retrieving interface status {}", e)
                }

                def onTimeout() {
                    log.error("Timeout while retrieving interface status")
                }

                def onSuccess(data: JList[InterfaceDescription]) {
                    self ! _LocalTunnelInterfaceInfoFinal(caller, data)
                }
            })
        }
    }

    /* Deep nesting of function literals in getLocalInterfaceTunnelInfo
     * results in the class file "DatapathController$$anonfun$org$midonet$midolman$DatapathController$$getLocalInterfaceTunnelInfo$3$$anonfun$apply$14$$anonfun$apply$15$$anonfun$apply$16.class"
     * which is too long for ecryptfs, so alias it to "gliti" to save some
     * room.
     */
    private val localTunnelInterfaceInfoPhaseTwo:
        (ActorRef, JList[InterfaceDescription]) => Unit = gliti

    private def gliti(caller: ActorRef,
                      interfaces: JList[InterfaceDescription]) {
        // First we would populate the data structure with tunnel info
        // on all local interfaces
        val addrTunnelMapping: mutable.MultiMap[Int, TunnelZone.Type] =
            new mutable.HashMap[Int, mutable.Set[TunnelZone.Type]]
                with mutable.MultiMap[Int, TunnelZone.Type]
        // This next variable is the structure for return message
        val retInterfaceTunnelMap: mutable.MultiMap[InterfaceDescription,TunnelZone.Type] =
            new mutable.HashMap[InterfaceDescription,
                mutable.Set[TunnelZone.Type]]
                with mutable.MultiMap[InterfaceDescription, TunnelZone.Type]

        def toInt(addr: InetAddress) = ByteBuffer.wrap(addr.getAddress).getInt

        host.zones.foreach {
            case (_: UUID, zoneConfig: GreTunnelZoneHost) =>
                addrTunnelMapping.addBinding(zoneConfig.getIp.addressAsInt,
                                             TunnelZone.Type.Gre)
        }

        if (!addrTunnelMapping.isEmpty) {
            log.debug("Host has some tunnel zone(s) configured")
            for (
                interface <- interfaces.asScala;
                inetAddress <- interface.getInetAddresses.asScala;
                tunnelTypes <- addrTunnelMapping.get(toInt(inetAddress));
                tunnelType <- tunnelTypes
                if inetAddress.getAddress.length == 4
            ) {
                retInterfaceTunnelMap.addBinding(interface, tunnelType)
            }
        }
        caller ! retInterfaceTunnelMap
    }

    /*
     * ONLY USE THIS DURING INITIALIZATION.
     */
    private def readDatapathInformation(wantedDatapath: String) {
        def handleExistingDP(dp: Datapath) {
            log.info("The datapath already existed. Flushing the flows.")
            datapathConnection.flowsFlush(dp,
                new ErrorHandlingCallback[JBoolean] {
                    def onSuccess(data: JBoolean) {}
                    def handleError(ex: NetlinkException, timeout: Boolean) {
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

        val dpCreateCallback = new ErrorHandlingCallback[Datapath] {
            def onSuccess(data: Datapath) {
                log.info("Datapath created {}", data)
                queryDatapathPorts(data)
            }

            def handleError(ex: NetlinkException, timeout: Boolean) {
                log.error(ex, "Datapath creation failure {}", timeout)
                system.scheduler.scheduleOnce(100 millis, retryTask)
            }
        }

        val dpGetCallback = new ErrorHandlingCallback[Datapath] {
            def onSuccess(dp: Datapath) {
                handleExistingDP(dp)
            }

            def handleError(ex: NetlinkException, timeout: Boolean) {
                if (timeout) {
                    log.error("Timeout while getting the datapath", timeout)
                    system.scheduler.scheduleOnce(100 millis, retryTask)
                } else if (ex != null) {
                    val errorCode: ErrorCode = ex.getErrorCodeEnum

                    if (errorCode != null &&
                        errorCode == NetlinkException.ErrorCode.ENODEV) {
                        log.info("Datapath is missing. Creating.")
                        datapathConnection.datapathsCreate(
                            wantedDatapath, dpCreateCallback)
                    }
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
            new ErrorHandlingCallback[JSet[Port[_, _]]] {
                def onSuccess(ports: JSet[Port[_, _]]) {
                    self ! _SetLocalDatapathPorts(datapath, ports.asScala.toSet)
                }

                // WARN: this is ugly. Normally we should configure the message error handling
                // inside the router
                def handleError(ex: NetlinkException, timeout: Boolean) {
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

object VirtualPortManager {
    trait Controller {
        def setVportStatus(port: Port[_, _], vportId: UUID,
                           isActive: Boolean): Unit
        def addToDatapath(interfaceName: String): Unit
        def removeFromDatapath(port: Port[_, _]): Unit

    }
}

/* *IMMUTABLE* safe class to manage relationships between interfaces, datapath,
 * and virtual ports. This class DOES NOT manage tunnel ports.
 *
 * Immutable means that write-operations work on, and return, a copy of this
 * object. Which means that the caller MUST keep the returned object or be left
 * with an out-of-date copy of the VirtualPortManager.
 *
 * The rationale for this is (as opposed to just making the data it maintains
 * immutable/concurrent) achieving lock-less atomicity of changes.
 *
 * In practice, the above means that the DatatapathController is reponsible
 * of maintaining a private VirtualPortManager reference exposed to clients
 * through the DatapathStateManager class, which offers a read-only view of the
 * VPortManager thanks to the fact that the VirtualPortManager extends from the
 * VirtualPortResolver trait. And clients use this read-only view at will. In
 * other words:
 *
 *    + The DatapathController performs modifications on the VPortManager,
 *    and, being an immutable object, updates the volatile reference when done.
 *
 *    + Clients access the reference as a read-only object through a
 *    DatapathStateManager object and are guaranteed that every time they read
 *    the reference they get a consistent (thanks to atomic updates), immutable
 *    and up to date view of the state of the virtual ports.
 */
class VirtualPortManager(
        val controller: VirtualPortManager.Controller,
        // Map the interfaces this manager knows about to their status.
        var interfaceToStatus: Map[String, Boolean] = Map[String, Boolean](),
        // The interfaces that are ports on the datapath, and their
        // corresponding port numbers.
        // The datapath's non-tunnel ports, regardless of their
        // status (up/down)
        var interfaceToDpPort: Map[String, Port[_,_]] = Map[String, Port[_,_]](),
        var dpPortNumToInterface: Map[JInteger, String] = Map[JInteger, String](),
        // Bi-directional map for interface-vport bindings.
        var interfaceToVport: Bimap[String,UUID] = new Bimap[String, UUID](),
        // Track which dp ports this module added. When interface-vport bindings
        // are removed, this module only removes the dp port if it originally
        // requested its creation.
        var dpPortsWeAdded: Set[String] = Set[String](),
        // Track which dp ports have add/remove in flight because while we wait
        // for a change to complete, the binding may be deleted or re-created.
        var dpPortsInProgress: Set[String] = Set[String]()
    )(implicit val log: LoggingAdapter) {

    private def copy = new VirtualPortManager(controller,
                                              interfaceToStatus,
                                              interfaceToDpPort,
                                              dpPortNumToInterface,
                                              interfaceToVport,
                                              dpPortsWeAdded,
                                              dpPortsInProgress)

    /*
    This note explains the life-cycle of the datapath's non-tunnel ports.
    Before there is a port there must be a network interface. The
    DatapathController does not create network interfaces (except in the
    case of internal ports, where the network interface is created
    automatically when the datapath port is created). Also, the
    DatapathController does not change the status of network interfaces.

    The datapath's non-tunnel ports correspond to one of the following:
    - port 0, the datapath's 'local' interface, whose name is the same as
      that of the datapath itself. It cannot be deleted, even if unused.
    - ports corresponding to interface-to-virtual-port bindings. Port 0 may
      be bound to a virtual port.
    - ports created by request of other modules - e.g. by the RoutingHandler.

    The DatapathController must be the only software controlling its
    datapath. Therefore, the datapath may not be deleted or manipulated in
    any way by other components, inside or outside Midolman.

    However, the DatapathController is able to cope with other components
    creating, deleting, or modifying the status of network interfaces.

    The DatapathController scans the host's network interfaces periodically
    to track creations, deletions, and status changes:
    - when a new network interface is created, if it corresponds to an
      interface-vport binding, then the DC adds it as a port on the datapath
      and records the correspondence of the resulting port's Short port number
      to the virtual port. However, it does not consider the virtual port to
      be active unless the interface's status is UP, in which case it also
      sends a LocalPortActive(vportID, active=true) message to the
      VirtualToPhysicalMapper.
    - when a network interface is deleted, if it corresponds to a datapath
      port, then the datapath port is removed and the port number reclaimed.
      If the interface was bound to a virtual port, then the DC also sends a
      LocalPortActive(vportID, active=false) message to the
      VirtualToPhysicalMapper.
    - when a network interface status changes from UP to DOWN, if it was bound
      to a virtual port, the DC sends a LocalPortActive(vportID, active=false)
      message to the VirtualToPhysicalMapper.
    - when a network interface status changes from DOWN to UP, if it was bound
      to a virtual port, the DC sends a LocalPortActive(vportID, active=true)
      message to the VirtualToPhysicalMapper.

    The DatapathController receives updates to the host's interface-vport
    bindings:
    - when a new binding is discovered, if the interface already exists then
      the DC adds it as a port on the datapath and records the correspondence
      of the resulting Short port number to the virtual port. However, it
      does not consider the virtual port to be active unless the interface's
      status is UP, in which case it also sends a
      LocalPortActive(vportID, active=true) message to the
      VirtualToPhysicalMapper.
    - when a binding is removed, if a corresponding port already exists on
      the datapath, then the datatapath port is removed and the port number
      reclaimed. If the interface was bound to a virtual port,
      then the DC also sends a LocalPortActive(vportID, active=false)
      message to the VirtualToPhysicalMapper.
    */


    private def requestDpPortAdd(itfName: String) {
        log.debug("requestDpPortAdd {}", itfName)
        // Only one port change in flight at a time.
        if (!dpPortsInProgress.contains(itfName)) {
            // Immediately track this is a port we requested. If the binding
            // is removed before the port is added, then when we're notified
            // of the port's addition we'll know it's our port to delete.
            dpPortsWeAdded += itfName
            dpPortsInProgress += itfName
            controller.addToDatapath(itfName)
        }
    }

    private def requestDpPortRemove(port: Port[_, _]) {
        log.debug("requestDpPortRemove {}", port)
        // Only request the port removal if:
        // - it's not port zero.
        // - it's a port we added.
        // - there isn't already an operation in flight for this port name.
        if (port.getPortNo != 0 && dpPortsWeAdded.contains(port.getName) &&
                !dpPortsInProgress.contains(port.getName)) {
            dpPortsWeAdded -= port.getName
            dpPortsInProgress += port.getName
            controller.removeFromDatapath(port)
        }
    }

    private def notifyPortRemoval(port: Port[_, _]) {
        if (port.getPortNo != 0 && dpPortsWeAdded.contains(port.getName) &&
            !dpPortsInProgress.contains(port.getName)) {
             dpPortsWeAdded -= port.getName
             _datapathPortRemoved(port.getName)
        }
    }

    def updateInterfaces(interfaces : JCollection[InterfaceDescription]) =
        copy._updateInterfaces(interfaces)

    private def _newInterface(itf: InterfaceDescription, isUp: Boolean) {
        log.info("New interface found: {}", itf)
        interfaceToStatus += ((itf.getName, isUp))

        // Is there a vport binding for this interface?
        if (!interfaceToVport.contains(itf.getName))
            return

        interfaceToDpPort.get(itf.getName) match {
            case None => // Request that it be added to the datapath.
                requestDpPortAdd(itf.getName)
            case Some(port) if isUp => // The virtual port is now active.
                val vPort = interfaceToVport.get(itf.getName).getOrElse(null)
                controller.setVportStatus(port, vPort, isActive = true)
            case _ =>
        }
    }

    private def _updateInterface(itf: InterfaceDescription,
                                 isUp: Boolean, wasUp: Boolean) {
        // The NetlinkInterfaceSensor sets the endpoint for all the
        // ports of the dp to DATAPATH. If the endpoint is not DATAPATH
        // it means that this is a dangling tap. We need to recreate
        // the dp port. Use case: add tap, bind it to a vport, remove
        // the tap. The dp port gets destroyed.
        if (itf.getEndpoint != InterfaceDescription.Endpoint.UNKNOWN &&
            itf.getEndpoint != InterfaceDescription.Endpoint.DATAPATH &&
            interfaceToDpPort.contains(itf.getName) && isUp) {
            requestDpPortAdd(itf.getName)
            log.debug("Recreating port {} because was removed and the dp" +
                      " didn't request the removal", itf.getName)
        } else {
            if (isUp != wasUp) {
                interfaceToStatus += ((itf.getName, isUp))
                log.info("Updating interface={} isUp status to {}", itf, isUp)
                for (
                    vportId <- interfaceToVport.get(itf.getName);
                    dpPort <- interfaceToDpPort.get(itf.getName)
                ) {
                    controller.setVportStatus(dpPort, vportId, isUp)
                }
            }
        }
    }

    private def _updateInterfaces(itfs: JCollection[InterfaceDescription]) = {
        val currentInterfaces = mutable.Set[String]()

        itfs.asScala foreach { itf =>
            currentInterfaces.add(itf.getName)
            val isUp = itf.hasLink && itf.isUp
            interfaceToStatus.get(itf.getName) match {
                case None => _newInterface(itf, isUp)
                case Some(wasUp) => _updateInterface(itf, isUp, wasUp)
            }
        }

        // Now deal with any interface that has been deleted.
        val deletedInterfaces = interfaceToStatus -- currentInterfaces
        deletedInterfaces.keys.foreach { name =>
            log.info("Deleting interface name={}", name)
            interfaceToStatus -= name
            // we don't have to remove the binding, the interface was deleted
            // but the binding is still valid
            interfaceToVport.get(name) foreach { vportId =>
                interfaceToDpPort.get(name) foreach { dpPort =>
                    controller.setVportStatus(dpPort, vportId, isActive = false)
                }
            }
            interfaceToDpPort.get(name) foreach { port =>
                // if the interface is not present the port has already been
                // removed, we just need to notify the dp
                notifyPortRemoval(port)
            }
        }
        this
    }

    def updateVPortInterfaceBindings(vportToInterface: Map[UUID, String]) =
        copy._updateVPortInterfaceBindings(vportToInterface)

    // Aux method for _updateVPortInterfaceBindings
    private def _newInterfaceVportBinding(vportId: UUID, itfName: String) {
        if (interfaceToVport.contains(itfName))
            return

        interfaceToVport += (itfName, vportId)
        // This is a new binding. Does the interface exist?
        if (!interfaceToStatus.contains(itfName))
            return

        // Has the interface been added to the datapath?
        interfaceToDpPort.get(itfName) match {
            case Some(dpPort) if interfaceToStatus(itfName) =>
                // The vport is active if the interface is up.
                controller.setVportStatus(dpPort, vportId, isActive = true)
            case None =>
                requestDpPortAdd(itfName)
            case _ =>
        }
    }

    // Aux method for _udpateVPortInterfaceBindings
    private def _deletedInterfaceVportBinding(vport: UUID, ifname: String) {
        interfaceToVport -= ifname
        // This binding was removed. Was there a datapath port for it?
        interfaceToDpPort.get(ifname) match {
            case None =>
                /* no port were added, it could still be marked as in progress
                   so we untrack it as such if it was */
                dpPortsInProgress -= ifname
            case Some(port) =>
                requestDpPortRemove(port)
                if (interfaceToStatus.get(ifname).getOrElse(false)) {
                    // If the port was up, the vport just became inactive.
                    controller.setVportStatus(port, vport, isActive = false)
                }
        }
    }

    // We do not support remapping a vportId to a different
    // interface or vice versa. We assume each vportId and
    // interface will occur in at most one binding.
    private def _updateVPortInterfaceBindings(vportToInterface: Map[UUID, String]) = {

        log.debug("updateVPortInterfaceBindings {}", vportToInterface)

        // First, deal with new bindings.
        for ((vportId: UUID, itfName: String) <- vportToInterface) {
            _newInterfaceVportBinding(vportId, itfName)
        }

        // Now, deal with deleted bindings.
        for (
            (ifname: String, vport: UUID) <- interfaceToVport
            if !vportToInterface.contains(vport)
        ) {
            _deletedInterfaceVportBinding(vport, ifname)
        }
        this
    }

    def datapathPortForget(port: Port[_,_]) = copy._datapathPortForget(port)

    private def _datapathPortForget(port: Port[_, _]) = {
        dpPortsInProgress -= port.getName
        dpPortsWeAdded -= port.getName
        interfaceToStatus -= port.getName
        this
    }

    def datapathPortAdded(port: Port[_, _]) =
        copy._datapathPortAdded(port)

    private def _datapathPortAdded(port: Port[_, _]) = {
        log.debug("datapathPortAdded {}", port)
        // First clear the in-progress operation
        dpPortsInProgress -= port.getName

        interfaceToDpPort = interfaceToDpPort.updated(port.getName, port)
        dpPortNumToInterface += ((port.getPortNo, port.getName))

        // Vport bindings may have changed while we waited for the port change:
        // If the itf is not bound to a vport, try to remove the dpPort.
        // If the itf is up and still bound to a vport, then the vport is UP.
        interfaceToVport.get(port.getName) match {
            case None =>
                requestDpPortRemove(port)
            case Some(vportId) =>
                interfaceToStatus.get(port.getName) match {
                    case None => // Do nothing. Don't know the status.
                    case Some(false) => // Do nothing. The interface is down.
                    case Some(true) =>
                        controller.setVportStatus(port, vportId, isActive=true)
                }
        }
        this
    }

    def datapathPortRemoved(itfName: String) =
        copy._datapathPortRemoved(itfName)

    private def _datapathPortRemoved(itfName: String) = {
        log.debug("datapathPortRemoved {}", itfName)
        // Clear the in-progress operation
        val requestedByMe = dpPortsInProgress.contains(itfName)
        dpPortsInProgress -= itfName

        interfaceToDpPort.get(itfName) match {
            case None =>
                log.warning("Unknown DP port removed, interface: {}", itfName)
            case Some(port) =>
                interfaceToDpPort -= itfName
                dpPortNumToInterface -= port.getPortNo

                // Is there a binding for this interface name?
                for (
                    vportId <- interfaceToVport.get(itfName);
                    isUp <- interfaceToStatus.get(itfName)
                ) {
                    // If we didn't request this removal, and the interface is
                    // up, then notify that the vport is now down. Also, if the
                    // interface exists, request that the dpPort be re-added.
                    requestDpPortAdd(itfName)
                    if (isUp && !requestedByMe)
                        controller.setVportStatus(port, vportId, isActive=false)
                }
        }
        this
    }

}

/** class which manages the state changes triggered by message receive by
 *  the DatapathController. It also exposes the DatapathController managed
 *  data to clients for WilcardFlow translation. */
class DatapathStateManager(val controller: VirtualPortManager.Controller)(
                  implicit val log: LoggingAdapter) extends DatapathState {

    @scala.volatile private var _vportMgr = new VirtualPortManager(controller)
    @scala.volatile private var _version: Long = 0

    /** update the internal version number of the DatapathStateManager.
     *  @param  sideEffect block of code with side effect on this object.
     *  @return the return valpue of the block of code passed as argument.
     */
    private def versionUp[T](sideEffect: => T): T = {
        _version += 1
        sideEffect
    }

    override def version = _version

    /** used internally by the DPC on InterfaceUpdate msg.*/
    def updateInterfaces(itfs: JCollection[InterfaceDescription]) =
        versionUp { _vportMgr = _vportMgr.updateInterfaces(itfs) }

    /** used internally by the DPC when processing host info.*/
    def updateVPortInterfaceBindings(bindings: Map[UUID, String]) =
        versionUp { _vportMgr = _vportMgr.updateVPortInterfaceBindings(bindings) }

    /** to be called by the DatapathController in reaction to a successful
     *  non-tunnel port creation operation.
     *  @param  port port which was successfully created
     */
    def dpPortAdded(port: Port[_,_]) =
        versionUp { _vportMgr = _vportMgr.datapathPortAdded(port) }

    /** to be called by the DatapathController in reaction to a successful
     *  non-tunnel port deletion operation.
     *  @param  port port which was successfully deleted
     */
    def dpPortRemoved(port: Port[_,_]) =
        versionUp { _vportMgr = _vportMgr.datapathPortRemoved(port.getName) }

    /** to be called by the DatapathController in reaction to a failed
     *  non-tunnel port creation operation so that the DatapathController can
     *  reschedule a try.
     *  @param  port port which could not be created.
     */
    def dpPortForget(port: Port[_,_]) =
        versionUp { _vportMgr = _vportMgr.datapathPortForget(port) }

    var tunnelGre: Option[Port[_,_]] = None

    var greOutputAction: Option[FlowActionOutput] = None

    /** updates the DPC reference to the tunnel port bound in the datapath */
    def setTunnelGre(p: Option[Port[_,_]]) = versionUp {
        tunnelGre = p
        greOutputAction = tunnelGre.map{ _.toOutputAction }
        log.info("gre tunnel port was assigned to {}", p)
    }

    /** reference to the current host information. Used to query this host ip
     *  when adding tunnel routes to peer host for given zone uuid. */
    var _host: Option[Host] = None

    override def host = _host.getOrElse {
        log.info("request for host reference but _host was not set yet")
        null
    }

    /** updates the DPC reference to the current host info */
    def host_=(h: Host) = versionUp { _host = Option(h) }

    /** 2D immutable map of peerUUID -> zoneUUID -> (srcIp, dstIp)
     *  this map stores all the possible underlay routes from this host
     *  to remote midolman host.
     */
    var _peersRoutes: Map[UUID,Map[UUID,(Int,Int)]] =
        Map[UUID,Map[UUID,(Int, Int)]]()

    override def peerTunnelInfo(peer: UUID) =
        _peersRoutes.get(peer).flatMap{ _.values.headOption }

    /** helper for route string formating. */
    private def routeStr(route: (Int,Int)): (String,String) =
        (IPv4Addr.intToString(route._1), IPv4Addr.intToString(route._2))

    /** add route info about peer for given zone and retrieve ip for this host
     *  and for this zone from dpState.
     *  @param  peer  remote host UUID
     *  @param  zone  zone UUID the underlay route to add is associated to.
     *  @param  ipPair the underlay ip of the remote host
     *  @return possible tags to send to the FlowController for invalidation
     */
    def addPeer(peer: UUID, zone: UUID, ipPair: (Int, Int)): Seq[Any] =
       versionUp {
            log.info("new tunnel route {} to peer {}", routeStr(ipPair), peer)

            val routes = _peersRoutes.getOrElse(peer, Map[UUID,(Int,Int)]())
            // invalidate the old route if overwrite
            val oldRouteTag = routes.get(zone)

            _peersRoutes += ( peer -> ( routes + (zone -> ipPair) ) )
            val tags = FlowTagger.invalidateTunnelPort(ipPair) :: Nil

            oldRouteTag.map{ FlowTagger.invalidateTunnelPort(_) :: tags }
                       .getOrElse(tags)
        }

    /** delete a tunnel route info about peer for given zone.
     *  @param  peer  remote host UUID
     *  @param  zone  zone UUID the underlay route to remove is associated to.
     *  @return possible tag to send to the FlowController for invalidation
     */
    def removePeer(peer: UUID, zone: UUID): Option[Any] =
        _peersRoutes.get(peer).flatMap(_.get(zone)).map { ipPair =>
            log.info(
                "removing tunnel route {} to peer {}", routeStr(ipPair), peer)
            versionUp {
                // TODO(hugo): remove nested map if becomes empty (mem leak)
                _peersRoutes += (peer -> (_peersRoutes(peer) - zone))
                FlowTagger.invalidateTunnelPort(ipPair)
            }
        }

    /** delete all tunnel routes associated with a given zone
     *  @param  zone zone uuid
     *  @return sequence of tags to send to the FlowController for invalidation
     */
    def removePeersForZone(zone: UUID): Seq[Any] =
        _peersRoutes.keys.toSeq.flatMap{ removePeer(_, zone) }

    /** used internally by the Datapath Controller to answer DpPortStatsRequest */
    def getInterfaceForVport(vportId: UUID): Option[String] =
        _vportMgr.interfaceToVport.inverse.get(vportId)

    override def getDpPortForInterface(itfName: String): Option[Port[_,_]] =
        _vportMgr.interfaceToDpPort.get(itfName)

    override def getDpPortNumberForVport(vportId: UUID): Option[JInteger] =
        _vportMgr.interfaceToVport.inverse.get(vportId) flatMap { itfName =>
            _vportMgr.interfaceToDpPort.get(itfName) map { _.getPortNo }
        }

    override def getVportForDpPortNumber(portNum: JInteger): Option[UUID] =
        _vportMgr.dpPortNumToInterface.get(portNum)
                 .flatMap { _vportMgr.interfaceToVport.get }

    override def getDpPortName(num: JInteger): Option[String] =
        _vportMgr.dpPortNumToInterface.get(num)

    var uplinkPid: Int = 0
}
