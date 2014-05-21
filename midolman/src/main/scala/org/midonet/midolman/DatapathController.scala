/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.lang.{Boolean => JBoolean, Integer => JInteger}
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.{Collection => JCollection, Set => JSet, UUID}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor._
import akka.pattern.{after, pipe}
import akka.event.LoggingAdapter
import akka.event.LoggingReceive
import akka.util.Timeout
import com.google.inject.Inject
import org.slf4j.LoggerFactory

import org.midonet.Subscription
import org.midonet.cluster.client
import org.midonet.cluster.client.Port
import org.midonet.cluster.data.TunnelZone
import org.midonet.cluster.data.TunnelZone.{HostConfig => TZHostConfig}
import org.midonet.cluster.data.zones._
import org.midonet.event.agent.InterfaceEvent
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.io._
import org.midonet.midolman.monitoring.MonitoringActor
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest
import org.midonet.midolman.topology.VirtualToPhysicalMapper.TunnelZoneRequest
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.rcu.Host
import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.flows.{FlowAction, FlowActionOutput}
import org.midonet.odp.ports._
import org.midonet.odp.{DpPort, Datapath, OvsConnectionOps}
import org.midonet.packets.{IntIPv4, IPv4Addr, TCP}
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
    def tunnelGre: Option[DpPort]

    def greOutputAction: Option[FlowActionOutput]

    /** reference to the datapath VxLan tunnel port.
     *  None if port is not available. */
    def tunnelVxLan: Option[DpPort]

    def vxLanOutputAction: Option[FlowActionOutput]

    /** tells if the given portNumber points to the vtep tunnel port. */
    def isVtepPort(portNumber: Short): Boolean

    /** tells if the given portNumber points to the gre tunnel port. */
    def isGrePort(portNumber: Short): Boolean
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

trait DatapathState extends VirtualPortsResolver with UnderlayResolver {

    // TODO(guillermo) - for future use. Decisions based on the datapath state
    // need to be aware of its versioning because flows added by fast-path
    // simulations race with invalidations sent out-of-band by the datapath
    // controller to the flow controller.
    def version: Long

}

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
     * Message to ask a port operation inside the datapath. The operation can
     * either be a create or delete request according to the return value of
     * DpPortRequest#op. The sender will receive a DpPortReply holding the
     * original request and which can be either of type DpPortCreateSuccess to
     * indicate a successful create operation, or of type DpPortDeleteSuccess to
     * indicate a successful delete operation in the datapath, or otherwise a
     * DpPortError indicating an error occured.
     */
    sealed trait DpPortRequest {
        val port: DpPort
        val tag: Option[AnyRef]
    }

    sealed trait DpPortCreate extends DpPortRequest
    sealed trait DpPortDelete extends DpPortRequest

    sealed trait DpPortReply { val request: DpPortRequest }

    case class DpPortCreateSuccess(request: DpPortRequest,
                                   createdPort: DpPort,
                                   uplinkPid: Int) extends DpPortReply

    case class DpPortDeleteSuccess(request: DpPortRequest, createdPort: DpPort)
            extends DpPortReply

    case class DpPortError(request: DpPortRequest, error: Throwable)
            extends DpPortReply

    case class DpPortCreateNetdev(port: NetDevPort, tag: Option[AnyRef])
            extends DpPortCreate

    case class DpPortDeleteNetdev(port: NetDevPort, tag: Option[AnyRef])
            extends DpPortDelete

   /**
    * This message encapsulates a given port stats to the monitoring agent.
    * @param stats port stats
    */
    case class DpPortStats(portID: UUID, stats: DpPort.Stats)

    /**
     * This message requests stats for a given port.
     * @param portID port id
     */
    case class DpPortStatsRequest(portID: UUID)

    object Internal {

        /**
         * This message is sent when the separate thread has successfully
         * retrieved all information about the interfaces.
         */
        case class InterfacesUpdate(interfaces: JSet[InterfaceDescription])

        case class ExistingDatapathPorts(datapath: Datapath, ports: Set[DpPort])

        /** Signals that the ports in the datapath were cleared */
        case object DatapathClear

        /** Signals that the tunnels port have been created. The vxlan tunnel
         *  port is created (case Some(port)) only if a valid udp port value is
         *  set in the config, otherwise it not used (case None). */
        case class TunnelPortsReady(gre: DpPort, vxlanOption: Option[DpPort])
    }

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
class DatapathController extends Actor with ActorLogging with FlowTranslator {

    import DatapathController._
    import DatapathController.Internal._
    import FlowController.AddWildcardFlow
    import PacketWorkflow.AddVirtualWildcardFlow
    import VirtualPortManager.Controller
    import VirtualToPhysicalMapper.TunnelZoneUnsubscribe

    implicit val logger: LoggingAdapter = log

    override implicit val requestReplyTimeout: Timeout = new Timeout(1 second)
    override val cookieStr: String = ""

    override protected implicit val system = context.system

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

    val dpState = new DatapathStateManager(
        new Controller {
            override def addToDatapath(itfName: String): Unit = {
                log.debug("VportManager requested add port {}", itfName)
                val port = new NetDevPort(itfName)
                createDatapathPort(self, DpPortCreateNetdev(port, None))
            }

            override def removeFromDatapath(port: DpPort): Unit = {
                log.debug("VportManager requested remove port {}", port.getName)
                val netdevPort = port.asInstanceOf[NetDevPort]
                deleteDatapathPort(self, DpPortDeleteNetdev(netdevPort, None))
            }

            override def setVportStatus(port: DpPort, vportId: UUID,
                                        isActive: Boolean): Unit = {
                log.info("Port {}/{}/{} became {}",
                          port.getPortNo, port.getName, vportId,
                          if (isActive) "active" else "inactive")
                installTunnelKeyFlow(port, vportId, isActive)
                VirtualToPhysicalMapper ! LocalPortActive(vportId, isActive)
            }
        }
    )

    var recentInterfacesScanned = new java.util.ArrayList[InterfaceDescription]()

    var pendingUpdateCount = 0

    var initializer: ActorRef = system.deadLetters  // only used in tests

    var host: Host = null
    // If a Host message arrives while one is being processed, we stash it
    // in this variable. We don't use Akka's stash here, because we only
    // care about the last Host message (i.e. ignore intermediate messages).
    var nextHost: Host = null

    var portWatcher: Subscription = null
    var portWatcherEnabled = true

    override def preStart() {
        super.preStart()
        context become (DatapathInitializationActor orElse {
            case m =>
                log.info("Not handling {} (behaving as InitializationActor)", m)
        })
    }

    override def receive: Receive = null

    val DatapathInitializationActor: Receive = LoggingReceive {

        case Initialize =>
            initializer = sender
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

        case ExistingDatapathPorts(datapathObj, ports) =>
            this.datapath = datapathObj
            val conn = new OvsConnectionOps(datapathConnection)
            Future.traverse(ports) { deleteExistingPort(_, conn) }
                  .map { _ => DatapathClear } pipeTo self

        case DatapathClear =>
            makeGrePort().zip(makeVxLanPort())
                         .map { case (p,q) => TunnelPortsReady(p,q) }
                         .pipeTo(self)

        case TunnelPortsReady(grePort, vxlanPortOption) =>
            dpState setTunnelGre Some(grePort)
            dpState setTunnelVxLan vxlanPortOption
            completeInitialization()
    }

    def deleteExistingPort(port: DpPort, conn: OvsConnectionOps) = port match {
        case _: InternalPort =>
            log.debug("Keeping {} found during initialization", port)
            dpState dpPortAdded port
            Future successful port
        case _ =>
            log.debug("Deleting {} found during initialization", port)
            ensureDeletePort(port, conn)
    }

    def ensureDeletePort(port: DpPort, conn: OvsConnectionOps): Future[DpPort] =
        conn.delPort(port, datapath) recoverWith {
            case ex: Throwable =>
                log.warning("retrying deletion of {} because of {}", port, ex)
                after(1 second, system.scheduler)(ensureDeletePort(port, conn))
        }

    def makeGrePort(): Future[DpPort] = {
        val grePort = GreTunnelPort.make("tngre-mm")
        upcallConnManager.createAndHookDpPort(datapath, grePort) map {
          case (p, _) => p
        } recoverWith {
            case ex: Throwable =>
                log.warning(
                    "GRE port creation failed: {} => retrying", ex)
                after(1 second, system.scheduler)(makeGrePort())
        }
    }

    def makeVxLanPort(): Future[Option[DpPort]] = {
        val vxlanUdpPort = midolmanConfig.getVxLanUdpPort
        if (TCP.isPortInRange(vxlanUdpPort)) {
            val vxLanPort = VxLanTunnelPort.make("tnvxlan-mm", vxlanUdpPort)
            upcallConnManager.createAndHookDpPort(datapath, vxLanPort) map {
                case (p, _) => Some(p)
            } recoverWith {
                case ex: Throwable =>
                    log.warning(
                        "VxLan port creation failed: {} => retrying", ex)
                    after(1 second, system.scheduler)(makeVxLanPort())
            }
        } else {
            Future successful None
        }
    }

    /**
     * Complete initialization and notify the actor that requested init.
     */
    def completeInitialization() {
        log.info("Initialization complete. Starting to act as a controller.")
        context.become(DatapathControllerActor orElse {
            case m =>
                log.warning("Unhandled message {}", m)
        })

        Seq[ActorRef](FlowController, PacketsEntryPoint, initializer) foreach {
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
                      self ! InterfacesUpdate(data)
                    }
                    def onError(e: NetlinkException) { /* not called */ }
            })
        }

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
                portWatcher.unsubscribe()
            }
            self ! Initialize

        case msg@AddVirtualWildcardFlow(flow, callbacks, tags) =>
            log.debug("Translating and installing wildcard flow: {}", flow)

            translateVirtualWildcardFlow(flow, tags) match {
                case Ready((finalFlow, finalTags)) =>
                    log.debug("flow translated, installing: {}", finalFlow)
                    FlowController ! AddWildcardFlow(finalFlow, null,
                                                     callbacks, finalTags)
                case NotYet(f) => f onComplete {
                    case Success(_) =>
                        self ! msg
                    case Failure(ex) =>
                        log.error(ex, "AddVirtualWildcardFlow failed to complete")
                }
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
                        new Callback[DpPort]{
                            def onSuccess(data: DpPort) {
                                MonitoringActor !
                                        DpPortStats(portID, data.getStats)
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

        case InterfacesUpdate(interfaces) =>
            dpState.updateInterfaces(interfaces)
            setTunnelMtu(interfaces)
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

    private def installTunnelKeyFlow(port: DpPort, exterior: client.Port) {
        val fc = FlowController
        // packets for the port may have arrived before the
        // port came up and made us install temporary drop flows.
        // Invalidate them before adding the new flow
        fc ! FlowController.InvalidateFlowsByTag(
            FlowTagger.invalidateByTunnelKey(exterior.tunnelKey))

        val portNo = port.getPortNo.shortValue
        val wMatch = new WildcardMatch().setTunnelID(exterior.tunnelKey)
        val actions = List[FlowAction](output(portNo))
        val tags = Set[Any](FlowTagger.invalidateDPPort(portNo))
        fc ! AddWildcardFlow(WildcardFlow(wcmatch = wMatch, actions = actions),
                             null, Nil, tags)
        log.debug("Added flow for tunnelkey {}", exterior.tunnelKey)
    }

    private def triggerPortInvalidation(dpPort: DpPort, port: Port,
                                        active: Boolean) {
        if (port.isInterior) {
            log.warning("local port {} active state changed, but it's not " +
                        "Exterior, don't know what to do with it: {}", dpPort)
            return
        }

        // trigger invalidation. This is done regardless of whether we are
        // activating or deactivating:
        //   + The case for invalidating on deactivation is obvious.
        //   + On activation we invalidate flows for this dp port number in case
        //     it has been reused by the dp: we want to start with a clean state
        FlowController ! InvalidateFlowsByTag(
            FlowTagger.invalidateDPPort(dpPort.getPortNo.shortValue()))

        if (active)
            installTunnelKeyFlow(dpPort, port)
    }

    private def installTunnelKeyFlow(port: DpPort, vif: UUID, active: Boolean) {
        VirtualTopologyActor.expiringAsk[Port](vif, log) match {
            case Ready(vPort) =>
                triggerPortInvalidation(port, vPort, active)
            case NotYet(f) => f.mapTo[Port] onComplete {
                    case Success(vPort) =>
                        triggerPortInvalidation(port, vPort, active)
                    case Failure(ex) =>
                        log.error(ex, "failed to install tunnel key flow")
                }
        }
    }

    def handlePortOperationReply(opReply: DpPortReply) {
        log.debug("Port operation reply: {}", opReply)

        opReply match {
            case DpPortCreateSuccess(DpPortCreateNetdev(_, _), newPort, _) =>
                dpState.dpPortAdded(newPort)

            case DpPortDeleteSuccess(DpPortDeleteNetdev(_, _), newPort) =>
                dpState.dpPortRemoved(newPort)

            case DpPortError(DpPortCreateNetdev(p, tag), ex: NetlinkException) =>
                // This will make the vport manager retry the create op
                // the next time the interfaces are scanned (2 secs).
                if (ex.getErrorCodeEnum == ErrorCode.EBUSY)
                    dpState.dpPortForget(p)

            case DpPortError(_: DpPortDelete, _) =>
                log.warning("Failed DpPortDelete {}", opReply)

            case DpPortError(_, _) =>
                log.warning("not handling DpPortError reply {}", opReply)

            case _ =>
                log.debug("not handling port op reply {}", opReply)
        }

        /** used in tests only */
        opReply match {
            case _ : DpPortError =>
                // ignore errors
            case _ =>
                system.eventStream.publish(opReply.request)
        }
    }

    def createDatapathPort(caller: ActorRef, request: DpPortCreate) {
        if (caller == self)
            pendingUpdateCount += 1
        log.info("creating port: {} (by request of: {})", request.port, caller)

        upcallConnManager.createAndHookDpPort(datapath, request.port) onComplete {
            case Success((port, pid)) =>
                caller ! DpPortCreateSuccess(request, port, pid)

            case Failure(ex) =>
                log.warning("Request {} failed: {}", request, ex.getMessage)
                caller ! DpPortError(request, reifyTimeoutException(ex))
        }
    }

    def deleteDatapathPort(caller: ActorRef, request: DpPortDelete) {
        if (request.port.getPortNo == null) {
            log.error("tried to delete a port with a null port number: {} caller: {}",
                request, caller)
            return;
        }

        if (caller == self)
            pendingUpdateCount += 1
        log.info("deleting port: {} (by request of: {})", request.port, caller)

        upcallConnManager.deleteDpPort(datapath, request.port) onComplete {
            case Success(b) =>
                caller ! DpPortDeleteSuccess(request, request.port)

            case Failure(ex) =>
                log.error(ex, "Port deletion failed: {}", request)
                caller ! DpPortError(request, reifyTimeoutException(ex))
        }
    }

    def reifyTimeoutException(ex: Throwable): Throwable = ex match {
        case _: TimeoutException =>
            new NetlinkException(NetlinkException.ErrorCode.ETIMEOUT, ex);
        case other =>
            other
    }

    private def setTunnelMtu(interfaces: JSet[InterfaceDescription]) = {
        def addressesMatch(inetAddress: InetAddress, ip: IntIPv4): Boolean =
            ByteBuffer.wrap(inetAddress.getAddress).getInt == ip.addressAsInt()

        var minMtu = Short.MaxValue
        val overhead = if (dpState.tunnelVxLan.isDefined)
            VxLanTunnelPort.TunnelOverhead
        else
            GreTunnelPort.TunnelOverhead

        for { intf <- interfaces.asScala
              inetAddress <- intf.getInetAddresses.asScala
              if inetAddress.getAddress.length == 4
              zone <- host.zones
              if addressesMatch(inetAddress, zone._2.getIp) &&
                 zone._2.isInstanceOf[GreTunnelZoneHost]
        } {
            val tunnelMtu = (intf.getMtu - overhead).toShort
            minMtu = minMtu.min(tunnelMtu)
        }

        if (minMtu == Short.MaxValue)
            minMtu = DEFAULT_MTU

        if (cachedMinMtu != minMtu) {
            log.debug("Changing MTU from {} to {}", cachedMinMtu, minMtu)
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
                log.error(ex, "Datapath creation failure")
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
                        log.error(ex, "Unexpected error while getting datapath")
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
                    self ! ExistingDatapathPorts(datapath, ports.asScala.toSet)
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

object VirtualPortManager {
    trait Controller {
        def setVportStatus(port: DpPort, vportId: UUID,
                           isActive: Boolean): Unit
        def addToDatapath(interfaceName: String): Unit
        def removeFromDatapath(port: DpPort): Unit

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
        var interfaceToDpPort: Map[String, DpPort] = Map[String, DpPort](),
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

    val interfaceEvent = new InterfaceEvent

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

    private def requestDpPortRemove(port: DpPort) {
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

    private def notifyPortRemoval(port: DpPort) {
        if (port.getPortNo != 0 && dpPortsWeAdded.contains(port.getName) &&
            !dpPortsInProgress.contains(port.getName)) {
             dpPortsWeAdded -= port.getName
             _datapathPortRemoved(port.getName)
        }
    }

    def updateInterfaces(interfaces : JCollection[InterfaceDescription]) =
        copy._updateInterfaces(interfaces)

    private def _newInterface(itf: InterfaceDescription, isUp: Boolean) {
        log.info("New interface found: {} isUp: {}", itf, isUp)
        interfaceEvent.detect(itf.toString);
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
                interfaceEvent.update(itf.toString)
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
            interfaceEvent.delete(name)
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

    def datapathPortForget(port: DpPort) = copy._datapathPortForget(port)

    private def _datapathPortForget(port: DpPort) = {
        dpPortsInProgress -= port.getName
        dpPortsWeAdded -= port.getName
        interfaceToStatus -= port.getName
        this
    }

    def datapathPortAdded(port: DpPort) =
        copy._datapathPortAdded(port)

    private def _datapathPortAdded(port: DpPort) = {
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
    def dpPortAdded(port: DpPort) =
        versionUp { _vportMgr = _vportMgr.datapathPortAdded(port) }

    /** to be called by the DatapathController in reaction to a successful
     *  non-tunnel port deletion operation.
     *  @param  port port which was successfully deleted
     */
    def dpPortRemoved(port: DpPort) =
        versionUp { _vportMgr = _vportMgr.datapathPortRemoved(port.getName) }

    /** to be called by the DatapathController in reaction to a failed
     *  non-tunnel port creation operation so that the DatapathController can
     *  reschedule a try.
     *  @param  port port which could not be created.
     */
    def dpPortForget(port: DpPort) =
        versionUp { _vportMgr = _vportMgr.datapathPortForget(port) }

    var tunnelGre: Option[DpPort] = None

    var greOutputAction: Option[FlowActionOutput] = None

    /** set the DPC reference to the gre tunnel port bound in the datapath */
    def setTunnelGre(p: Option[DpPort]) = versionUp {
        tunnelGre = p
        greOutputAction = tunnelGre.map{ _.toOutputAction }
        log.info("gre tunnel port was assigned to {}", p)
    }

    var tunnelVxLan: Option[DpPort] = None

    var vxLanOutputAction: Option[FlowActionOutput] = None

    /** set the DPC reference to the vxlan tunnel port bound in the datapath */
    def setTunnelVxLan(p: Option[DpPort]) = versionUp {
        tunnelVxLan = p
        vxLanOutputAction = tunnelVxLan.map{ _.toOutputAction }
        log.info("vxlan tunnel port was assigned to {}", p)
    }

    def isVtepPort(dpPortId: Short): Boolean =
      if (tunnelVxLan.isEmpty) false else tunnelVxLan.get.getPortNo == dpPortId

    def isGrePort(dpPortId: Short): Boolean =
      if (tunnelGre.isEmpty) false else tunnelGre.get.getPortNo == dpPortId

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

    override def getDpPortForInterface(itfName: String): Option[DpPort] =
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
}
