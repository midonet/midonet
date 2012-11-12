/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import akka.actor.{Cancellable, Actor, ActorLogging, ActorRef, SupervisorStrategy}
import akka.dispatch.{Future, Promise}
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._
import host.scanner.InterfaceScanner
import scala.collection.JavaConversions._
import scala.collection.{Set => ROSet, immutable, mutable}
import scala.collection.mutable.ListBuffer
import java.lang.{Boolean => JBoolean, Short => JShort}
import java.util.{HashSet, Set => JSet, UUID}

import com.google.inject.Inject

import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.datapath._
import com.midokura.midolman.monitoring.MonitoringActor
import com.midokura.midolman.rules.{ChainPacketContext, RuleResult}
import com.midokura.midolman.services.HostIdProviderService
import com.midokura.midolman.simulation.{Bridge => RCUBridge, Chain}
import com.midokura.midolman.topology._
import com.midokura.midolman.topology.VirtualTopologyActor.{BridgeRequest,
        ChainRequest, PortRequest}
import com.midokura.midolman.topology.rcu.{Host, PortSet}
import com.midokura.midonet.cluster.client
import com.midokura.midonet.cluster.client.{ExteriorPort, TunnelZones}
import com.midokura.midonet.cluster.data.TunnelZone
import com.midokura.midonet.cluster.data.zones.{CapwapTunnelZone,
        CapwapTunnelZoneHost, GreTunnelZone, GreTunnelZoneHost}
import com.midokura.netlink.Callback
import com.midokura.netlink.exceptions.NetlinkException
import com.midokura.netlink.exceptions.NetlinkException.ErrorCode
import com.midokura.netlink.protos.OvsDatapathConnection
import com.midokura.packets.Ethernet
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch, WildcardMatches}
import com.midokura.sdn.dp.{Flow => KernelFlow, _}
import com.midokura.sdn.dp.flows.{FlowActionUserspace, FlowAction, FlowKeys, FlowActions}
import com.midokura.sdn.dp.ports._
import com.midokura.util.functors.Callback0


/**
 * Holder object that keeps the external message definitions
 */
object PortOperation extends Enumeration {
    val Create, Delete = Value
}

object TunnelChangeEventOperation extends Enumeration {
    val Established, Removed = Value
}

sealed trait PortOp[P <: Port[_ <: PortOptions, P]] {
    val port: P
    val tag: Option[AnyRef]
    val op: PortOperation.Value
}

sealed trait CreatePortOp[P <: Port[_ <: PortOptions, P]] extends {
    val op = PortOperation.Create
} with PortOp[P]

sealed trait DeletePortOp[P <: Port[_ <: PortOptions, P]] extends {
    val op = PortOperation.Delete
} with PortOp[P]

sealed trait PortOpReply[P <: Port[_ <: PortOptions, P]] {
    val port: P
    val tag: Option[AnyRef]
    val op: PortOperation.Value
    val timeout: Boolean
    val error: NetlinkException
}

/**
 * This will make the Datapath Controller to start the local state
 * initialization process.
 */
case class Initialize()

object DatapathController extends Referenceable {

    override val Name = "DatapathController"

    // Java API
    def getInitialize: Initialize = {
        Initialize()
    }

    /**
     * Reply sent back to the sender of the Initialize message when the basic
     * initialization of the datapath is complete.
     */
    case class InitializationComplete()


    /**
     * Message sent to the [[com.midokura.midolman.FlowController]] actor to let
     * it know that it can install the the packetIn hook inside the datapath.
     *
     * @param datapath the active datapath
     */
    case class DatapathReady(datapath: Datapath)

    /**
     * Will trigger an internal port creation operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.PortInternalOpReply]]
     * message in return.
     *
     * @param port the port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class CreatePortInternal(port: InternalPort, tag: Option[AnyRef])
        extends CreatePortOp[InternalPort]

    /**
     * Will trigger an internal port delete operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.PortInternalOpReply]]
     * message when the operation is completed.
     *
     * @param port the port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class DeletePortInternal(port: InternalPort, tag: Option[AnyRef])
        extends DeletePortOp[InternalPort]

    /**
     * Reply message that is sent when a [[com.midokura.midolman.DatapathController.CreatePortInternal]]
     * or [[com.midokura.midolman.DatapathController.DeletePortInternal]]
     * operation completes. It contains the operation type, the port data
     * (updated or the original) and any error or timeout if the operation failed.
     *
     * @param port the internal port data
     * @param op the operation type
     * @param timeout true if the operation timed out
     * @param error non null if the underlying layer has thrown exceptions
     * @param tag is the same value that was passed in the initial operation by
     *            the caller
     */
    case class PortInternalOpReply(port: InternalPort, op: PortOperation.Value,
                                   timeout: Boolean, error: NetlinkException,
                                   tag: Option[AnyRef])
        extends PortOpReply[InternalPort]

    /**
     * Will trigger an netdev port creation operation. The sender will
     * receive an `PortNetdevOpReply` message in return.
     *
     * @param port the port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class CreatePortNetdev(port: NetDevPort, tag: Option[AnyRef])
        extends CreatePortOp[NetDevPort]

    /**
     * Will trigger an netdev port deletion operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.PortNetdevOpReply]]
     * message in return.
     *
     * @param port the port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class DeletePortNetdev(port: NetDevPort, tag: Option[AnyRef])
        extends DeletePortOp[NetDevPort]

    /**
     * Reply message that is sent when a [[com.midokura.midolman.DatapathController.CreatePortNetdev]]
     * or [[com.midokura.midolman.DatapathController.DeletePortNetdev]]
     * operation completes. It contains the operation type, the port data
     * (updated or the original) and any error or timeout if the operation failed.
     *
     * @param port the internal port data
     * @param op the operation type
     * @param timeout true if the operation timed out
     * @param error non null if the underlying layer has thrown exceptions
     * @param tag is the same value that was passed in the initial operation by
     *            the caller
     */
    case class PortNetdevOpReply(port: NetDevPort, op: PortOperation.Value,
                                 timeout: Boolean, error: NetlinkException,
                                 tag: Option[AnyRef])
        extends PortOpReply[NetDevPort]

    /**
     * Will trigger an `patch` tunnel creation operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelPatchOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class CreateTunnelPatch(port: PatchTunnelPort, tag: Option[AnyRef])
        extends CreatePortOp[PatchTunnelPort]

    /**
     * Will trigger an `patch` tunnel deletion operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelPatchOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class DeleteTunnelPatch(port: PatchTunnelPort, tag: Option[AnyRef])
        extends DeletePortOp[PatchTunnelPort]

    /**
     * Reply message that is sent when a [[com.midokura.midolman.DatapathController.CreateTunnelPatch]]
     * or [[com.midokura.midolman.DatapathController.DeleteTunnelPatch]]
     * operation completes. It contains the operation type, the port data
     * (updated or the original) and any error or timeout if the operation failed.
     *
     * @param port the internal port data
     * @param op the operation type
     * @param timeout true if the operation timed out
     * @param error non null if the underlying layer has thrown exceptions
     * @param tag is the same value that was passed in the initial operation by
     *            the caller
     */
    case class TunnelPatchOpReply(port: PatchTunnelPort, op: PortOperation.Value,
                                  timeout: Boolean, error: NetlinkException,
                                  tag: Option[AnyRef])
        extends PortOpReply[PatchTunnelPort]

    /**
     * Will trigger an `gre` tunnel creation operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelGreOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class CreateTunnelGre(port: GreTunnelPort, tag: Option[AnyRef])
        extends CreatePortOp[GreTunnelPort]

    /**
     * Will trigger an `gre` tunnel deletion operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelGreOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class DeleteTunnelGre(port: GreTunnelPort, tag: Option[AnyRef])
        extends DeletePortOp[GreTunnelPort]

    /**
     * Reply message that is sent when a [[com.midokura.midolman.DatapathController.CreateTunnelGre]]
     * or [[com.midokura.midolman.DatapathController.DeleteTunnelGre]]
     * operation completes. It contains the operation type, the port data
     * (updated or the original) and any error or timeout if the operation failed.
     *
     * @param port the internal port data
     * @param op the operation type
     * @param timeout true if the operation timed out
     * @param error non null if the underlying layer has thrown exceptions
     * @param tag is the same value that was passed in the initial operation by
     *            the caller
     */
    case class TunnelGreOpReply(port: GreTunnelPort, op: PortOperation.Value,
                                timeout: Boolean, error: NetlinkException,
                                tag: Option[AnyRef])
        extends PortOpReply[GreTunnelPort]

    /**
     * Will trigger an `capwap` tunnel creation operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelCapwapOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class CreateTunnelCapwap(port: CapWapTunnelPort, tag: Option[AnyRef])
        extends CreatePortOp[CapWapTunnelPort]

    /**
     * Will trigger an `capwap` tunnel deletion operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelCapwapOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class DeleteTunnelCapwap(port: CapWapTunnelPort, tag: Option[AnyRef])
        extends DeletePortOp[CapWapTunnelPort]

    /**
     * Reply message that is sent when a [[com.midokura.midolman.DatapathController.CreateTunnelCapwap]]
     * or [[com.midokura.midolman.DatapathController.DeleteTunnelCapwap]]
     * operation completes. It contains the operation type, the port data
     * (updated or the original) and any error or timeout if the operation failed.
     *
     * @param port the internal port data
     * @param op the operation type
     * @param timeout true if the operation timed out
     * @param error non null if the underlying layer has thrown exceptions
     * @param tag is the same value that was passed in the initial operation by
     *            the caller
     */
    case class TunnelCapwapOpReply(port: CapWapTunnelPort, op: PortOperation.Value,
                                   timeout: Boolean, error: NetlinkException,
                                   tag: Option[AnyRef])
        extends PortOpReply[CapWapTunnelPort]

    /**
     * This message requests that the DatapathController keep a temporary
     * binding of a virtual port (port in the virtual topology) to a local
     * datapath port. This may be used e.g. by the VPNManager to create
     * VPN ports - VPN ports are not associated with VMs and therefore not
     * in any host's Interface-VPort mappings.
     *
     * The binding will be removed when the datapath port is deleted.
     *
     * @param vportID the virtual port we want to bind to this internal port
     * @param port the internal port we want to bind to
     */
    case class BindToInternalPort(vportID: UUID, port: InternalPort)
    case class BindToNetDevPort(vportID: UUID, port: NetDevPort)

    case class InstallFlow(flow: KernelFlow)

    case class DeleteFlow(flow: KernelFlow)

    /**
     * Upon receiving this message, the DatapathController translates any
     * actions that are not understood by the Netlink layer and then sends the
     * packet to the kernel (who in turn executes the actions on the packet's
     * data).
     *
     * @param ethPkt The Ethernet packet that should be sent to the kernel.
     * @param actions The list of actions the kernel should apply to the data
     */
    case class SendPacket(ethPkt: Ethernet, actions: List[FlowAction[_]])

    case class PacketIn(wMatch: WildcardMatch, pktBytes: Array[Byte],
                        dpMatch: FlowMatch, reason: Packet.Reason,
                        cookie: Option[Int])

   /**
    * This message encapsulates a given port stats to the monitoring agent.
    * @param stats
    */
    case class PortStats(portID: UUID, stats: Port.Stats)

    class DatapathPortChangedEvent(val port: Port[_, _], val op: PortOperation.Value) {}

    class TunnelChangeEvent(val myself: TunnelZone.HostConfig[_, _],
                            val peer: TunnelZone.HostConfig[_, _],
                            val portOption: Option[Short],
                            val op: TunnelChangeEventOperation.Value)

    /**
     * This message requests stats for a given port.
     * @param portID
     */
    case class PortStatsRequest(portID: UUID)

    /**
     * Dummy ChainPacketContext used in egress port set chains.
     * All that is available is the Output Port ID (there's no information
     * on the ingress port or connection tracking at the egress controller).
     * @param outportID UUID for the output port
     */
    class EgressPortSetChainPacketContext(outportID: UUID)
            extends ChainPacketContext {
        override def getInPortId() = null
        override def getOutPortId() = outportID
        override def getPortGroups() = new HashSet[UUID]()
        override def addTraversedElementID(id: UUID) { }
        override def isConnTracked() = false
        override def isForwardFlow() = true
        override def getFlowCookie() = null
        override def addFlowTag(tag: Any) {}
        override def addFlowRemovedCallback(cb: Callback0) {}
    }

    /**
     * This message is sent every 2 seconds to check that the kernel contains exactly the same
     * ports/interfaces as the system. In case that somebody uses a command line tool (for example)
     * to bring down an interface, the system will react to it.
     * TODO this version is constantly checking for changes. It should react to 'netlink' notifications instead.
     */
    case class CheckForPortUpdates(datapathName: String)

    /**
     * This message is only to be sent when testing. This will disable the feature that is constantly
     * inspecting the ports to react to unexpected changes.
     */
    case class DisablePortWatcher()
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
class DatapathController() extends Actor with ActorLogging {

    import DatapathController._
    import VirtualToPhysicalMapper._
    import context._

    implicit val requestReplyTimeout = new Timeout(1 second)

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    @Inject
    val datapathConnection: OvsDatapathConnection = null

    @Inject
    val hostService: HostIdProviderService = null

    @Inject
    val interfaceScanner: InterfaceScanner = null

    var datapath: Datapath = null

    val localToVifPorts: mutable.Map[Short, UUID] = mutable.Map()
    val localTunnelPorts: mutable.Set[JShort] = mutable.Set()
    // Map of vport ID to local interface name - according to ZK.
    val vifPorts: mutable.Map[UUID, String] = mutable.Map()

    // the list of local ports
    val localPorts: mutable.Map[String, Port[_, _]] = mutable.Map()
    val zones = mutable.Map[UUID, TunnelZone[_, _]]()
    val zonesToHosts = mutable.Map[UUID, mutable.Map[UUID, TunnelZones.Builder.HostConfig]]()
    val zonesToTunnels: mutable.Map[UUID, mutable.Set[Port[_, _]]] = mutable.Map()
    val portsDownPool: mutable.Map[String, Port[_,_]] = mutable.Map()

    // peerHostId -> { ZoneID -> tunnelName }
    val peerPorts = mutable.Map[UUID, mutable.Map[UUID, String]]()

    var pendingUpdateCount = 0

    var initializer: ActorRef = null
    var initialized = false
    var host: Host = null
    // If a Host message arrives while one is being processed, we stash it
    // in this variable. We don't use Akka's stash here, because we only
    // care about the last Host message (i.e. ignore intermediate messages).
    var nextHost: Host = null

    var portWatcher: Cancellable = null
    var portWatcherEnabled = true

    override def preStart() {
        super.preStart()
        context.become(DatapathInitializationActor)
    }

    protected def receive = null

    val DatapathInitializationActor: Receive = {

        /**
         * Initialization request message
         */
        case Initialize() =>
            initializer = sender
            log.info("Initialize from: " + sender)
            VirtualToPhysicalMapper.getRef() ! HostRequest(hostService.getHostId)

        case DisablePortWatcher() =>
            log.info("Disabling the port watching feature.")
            portWatcherEnabled = false

        case h: Host =>
            // If we already had the host info, process this after init.
            this.host match {
                case null =>
                    // Only set it if the datapath is known.
                    if (null != h.datapath) {
                        this.host = h
                        readDatapathInformation(h.datapath)
                    }
                case _ =>
                    this.nextHost = h
            }

        case _SetLocalDatapathPorts(datapathObj, ports) =>
            this.datapath = datapathObj
            ports.foreach { _ match {
                    case p: GreTunnelPort =>
                        deleteDatapathPort(self, p, None)
                    case p: CapWapTunnelPort =>
                        deleteDatapathPort(self, p, None)
                    case p: NetDevPort =>
                        deleteDatapathPort(self, p, None)
                    case p =>
                        log.debug("Keeping port {} found during " +
                            "initialization", p)
                        localPorts.put(p.getName, p)
                }
            }
            log.debug("Finished processing datapath's existing ports. " +
                "Pending updates {}", pendingUpdateCount)
            if (pendingUpdateCount == 0)
                completeInitialization

        /**
        * Handle personal create port requests
        */
        case newPortOp: CreatePortOp[Port[_, _]] if (sender == self) =>
            createDatapathPort(sender, newPortOp.port, newPortOp.tag)

        /**
         * Handle personal delete port requests
         */
        case delPortOp: DeletePortOp[Port[_, _]] if (sender == self) =>
            deleteDatapathPort(sender, delPortOp.port, delPortOp.tag)

        case opReply: PortOpReply[Port[_, _]] if (sender == self) =>
            handlePortOperationReply(opReply)

        case Messages.Ping(value) =>
            sender ! Messages.Pong(value)

        /**
         * Log unhandled messages.
         */
        case m =>
            log.info("(behaving as InitializationActor). Not handling message: " + m)
    }

    /**
     * Complete initialization and notify the actor that requested init.
     */
    private def completeInitialization {
        log.info("Initialization complete. Starting to act as a controller.")
        initialized = true
        become(DatapathControllerActor)
        FlowController.getRef() ! DatapathController.DatapathReady(datapath)
        for ((zoneId, zone) <- host.zones) {
            VirtualToPhysicalMapper.getRef() ! TunnelZoneRequest(zoneId)
        }
        if (portWatcherEnabled) {
            // schedule port requests.
            log.info("Starting to schedule the port link status updates.")
            portWatcher = system.scheduler.schedule(1 second, 2 seconds,
                self, CheckForPortUpdates(datapath.getName))
        }
        initializer ! InitializationComplete()
        log.info("Process the host's zones and vport bindings.")
        doDatapathPortsUpdate
    }

    private def processNextHost {
        if (null != nextHost && pendingUpdateCount == 0) {
            host = nextHost
            nextHost = null
            doDatapathPortsUpdate
            doDatapathZonesReply(host.zones)
        }
    }

    val DatapathControllerActor: Receive = {

        // When we get the initialization message we switch into initialization
        // mode and only respond to some messages.
        // When initialization is completed we will revert back to this Actor
        // loop for general message response
        case m: Initialize =>
            initialized = false
            become(DatapathInitializationActor)
            // In case there were some scheduled port update checks, cancel them.
            if (portWatcher != null) {
                portWatcher.cancel()
            }
            self ! m

        case h: Host =>
            this.nextHost = h
            processNextHost

        case zone: TunnelZone[_, _] =>
            if (!host.zones.contains(zone.getId)) {
                zones.remove(zone.getId)
                zonesToHosts.remove(zone.getId)
                VirtualToPhysicalMapper.getRef() ! TunnelZoneUnsubscribe(zone.getId)
                log.debug("Removing zone {}", zone)
            } else {
                zones.put(zone.getId, zone)
                zonesToHosts.put(zone.getId,
                        mutable.Map[UUID, TunnelZones.Builder.HostConfig]())
                log.debug("Adding zone {}", zone)
            }

        case m: ZoneChanged[_] =>
            log.debug("ZoneChanged: {}", m)
            handleZoneChange(m)

        case newPortOp: CreatePortOp[Port[_, _]] =>
            createDatapathPort(sender, newPortOp.port, newPortOp.tag)

        case delPortOp: DeletePortOp[Port[_, _]] =>
            deleteDatapathPort(sender, delPortOp.port, delPortOp.tag)

        case opReply: PortOpReply[Port[_, _]] =>
            handlePortOperationReply(opReply)

        case AddWildcardFlow(flow, cookie, pktBytes, flowRemovalCallbacks, tags) =>
            handleAddWildcardFlow(flow, cookie, pktBytes, flowRemovalCallbacks,
                                    tags)

        case SendPacket(ethPkt, actions) =>
            handleSendPacket(ethPkt, actions)

        case PacketIn(wMatch, pktBytes, dpMatch, reason, cookie) =>
            handleFlowPacketIn(wMatch, pktBytes, dpMatch, reason, cookie)

        case Messages.Ping(value) =>
            sender ! Messages.Pong(value)

        case PortStatsRequest(portID) =>
            vifPorts.get(portID) match {
                case Some(portName) =>
                    datapathConnection.portsGet(portName, datapath, new Callback[Port[_,_]]{
                    def onSuccess(data: Port[_, _]) {
                        MonitoringActor.getRef() ! PortStats(portID, data.getStats)
                    }

                    def onTimeout() {
                    log.error("Timeout when retrieving port stats")
                }

                def onError(e: NetlinkException) {
                    log.error("Error retrieving port stats for port {}({}): {}", Array(portID, vifPorts.get(portID).get, e))
                }
              })

              case None =>
                  log.debug("Port was not found {}", portID)
            }

        case CheckForPortUpdates(datapathName: String) =>
            checkPortUpdates

    }

    def checkPortUpdates() {
        val interfacesSet = new HashSet[String]

        val deletedPorts = new HashSet[String]
        deletedPorts.addAll(localPorts.keySet)

        for (interface <- interfaceScanner.scanInterfaces()) {
            deletedPorts.remove(interface.getName)
            if (interface.isUp) {
                interfacesSet.add(interface.getName)
            }

            // interface went down.
            if (localPorts.contains(interface.getName) && !interface.isUp) {
                log.info("Interface went down: {}", interface.getName)
                localPorts.get(interface.getName).get match {
                    case p: NetDevPort =>
                        updatePort(interface.getName, false);

                    case default =>
                        log.error("port type not matched {}", default)
                }
            }
        }

        // this set contains the ports that have been deleted.
        // the behaviour is the same as if the port had gone down.
        deletedPorts.foreach{
            deletedPort =>
                log.info("Interface was deleted: {} {}", Array(localPorts.get(deletedPort).get.getPortNo,deletedPort))
                localPorts.get(deletedPort).get match {
                case p: NetDevPort =>
                    // delete the dp <-> port link
                    deleteDatapathPort(self, p, None)
                    // set port to inactive.
                    updatePort(p.getName, false);
                case default =>
                    log.error("port type not matched {}", default)
            }
        }

        // remove all the local ports. The rest will be datapath ports that are not known to the system.
        // one of them might be a port that went up again.
        interfacesSet.removeAll(localPorts.keySet)
        interfacesSet.foreach( interface =>
            if (portsDownPool.contains(interface)) {
                val p: Port[_,_] = portsDownPool.get(interface).get
                log.info("Resurrecting a previously deleted port. {} {}", Array(p.getPortNo, p.getName))

                // recreate port in datapath.
                createDatapathPort(self, Ports.newNetDevPort(interface),
                    localToVifPorts.get(p.getPortNo.shortValue()))
                updatePort(p.getName, true);
            }
        );
    }

    def updatePort(portName : String, up: Boolean) {
        var port: Port[_,_] = null
        if (up) {
            port = portsDownPool.get(portName).get
            localPorts.put(portName, port)
            portsDownPool.remove(portName)
        } else {
            port = localPorts.get(portName).get
            portsDownPool.put(portName, port)
            localPorts.remove(portName)
        }
        VirtualToPhysicalMapper.getRef() ! LocalPortActive(localToVifPorts.get(port.getPortNo.shortValue()).get, active = up)
    }


    def newGreTunnelPortName(source: GreTunnelZoneHost,
                             target: GreTunnelZoneHost): String = {
        "tngre%08X" format target.getIp.addressAsInt()
    }

    def newCapwapTunnelPortName(source: CapwapTunnelZoneHost,
                                target: CapwapTunnelZoneHost): String = {
        "tncpw%08X" format target.getIp.addressAsInt()
    }

    def handleZoneChange(m: ZoneChanged[_]) {
        val hostConfig = m.hostConfig.asInstanceOf[TunnelZone.HostConfig[_, _]]

        if (!zones.contains(m.zone) ||
            (hostConfig.getId == host.id &&
                m.op == HostConfigOperation.Deleted)) {
            VirtualToPhysicalMapper.getRef() ! TunnelZoneUnsubscribe(m.zone)
        } else if (hostConfig.getId != host.id) {
            m match {
                case GreZoneChanged(zone, peerConf, HostConfigOperation.Added) =>
                    log.info("Opening a tunnel port to {}", m.hostConfig)
                    val myConfig = host.zones(zone).asInstanceOf[GreTunnelZoneHost]

                    val tunnelName = newGreTunnelPortName(myConfig, peerConf)
                    val tunnelPort = Ports.newGreTunnelPort(tunnelName)

                    tunnelPort.setOptions(
                        tunnelPort
                            .newOptions()
                            .setSourceIPv4(myConfig.getIp.addressAsInt())
                            .setDestinationIPv4(peerConf.getIp.addressAsInt()))
                    createDatapathPort(
                        self, tunnelPort, Some((peerConf, m.zone)))

                case CapwapZoneChanged(zone, peerConf, HostConfigOperation.Added) =>
                    log.info("Opening a tunnel port to {}", m.hostConfig)
                    val myConfig = host.zones(zone).asInstanceOf[CapwapTunnelZoneHost]

                    val tunnelName = newCapwapTunnelPortName(myConfig, peerConf)
                    val tunnelPort = Ports.newCapwapTunnelPort(tunnelName)

                    tunnelPort.setOptions(
                        tunnelPort
                            .newOptions()
                            .setSourceIPv4(myConfig.getIp.addressAsInt())
                            .setDestinationIPv4(peerConf.getIp.addressAsInt()))
                    createDatapathPort(
                        self, tunnelPort, Some((peerConf, m.zone)))

                case GreZoneChanged(zone, peerConf, HostConfigOperation.Deleted) =>
                    log.info("Closing a tunnel port to {}", m.hostConfig)

                    val peerId = peerConf.getId

                    val tunnel = peerPorts.get(peerId) match {
                        case Some(mapping) =>
                            mapping.get(zone) match {
                                case Some(tunnelName) =>
                                    log.debug("Need to close the tunnel with name: {}", tunnelName)
                                    localPorts(tunnelName)
                                case None =>
                                    null
                            }
                        case None =>
                            null
                    }

                    if (tunnel != null) {
                        val greTunnel = tunnel.asInstanceOf[GreTunnelPort]
                        deleteDatapathPort(
                            self, greTunnel, Some((peerConf, zone)))
                    }

                case CapwapZoneChanged(zone, peerConf, HostConfigOperation.Deleted) =>
                    log.info("Closing a tunnel port to {}", m.hostConfig)

                    val peerId = peerConf.getId

                    val tunnel = peerPorts.get(peerId) match {
                        case Some(mapping) =>
                            mapping.get(zone) match {
                                case Some(tunnelName) =>
                                    log.debug("Need to close the tunnel with name: {}", tunnelName)
                                    localPorts(tunnelName)
                                case None =>
                                    null
                            }
                        case None =>
                            null
                    }

                    if (tunnel != null) {
                        val capwapTunnel = tunnel.asInstanceOf[CapWapTunnelPort]
                        deleteDatapathPort(
                            self, capwapTunnel, Some((peerConf, zone)))
                    }

                case _ =>

            }
        }
    }

    def doDatapathZonesReply(newZones: immutable.Map[UUID, TunnelZone.HostConfig[_, _]]) {
        log.debug("Local Zone list updated {}", newZones)
        for (zone <- newZones.keys) {
            VirtualToPhysicalMapper.getRef() ! TunnelZoneRequest(zone)
        }
    }

    def dropTunnelsInZone(zone: TunnelZone[_, _]) {
        zonesToTunnels.get(zone.getId) match {
            case Some(tunnels) =>
                for (port <- tunnels) {
                    port match {
                        case p: GreTunnelPort =>
                            zone match {
                                case z: GreTunnelZone =>
                                    deleteDatapathPort(self, p, Some(z))
                            }
                        case p: CapWapTunnelPort =>
                            zone match {
                                case z: CapwapTunnelZone =>
                                    deleteDatapathPort(self, p, Some(z))
                            }
                    }
                }

            case None =>
        }
    }

    def handleAddWildcardFlow(flow: WildcardFlow,
                              cookie: Option[Int],
                              pktBytes: Array[Byte],
                              flowRemovalCallbacks: ROSet[Callback0],
                              tags: ROSet[Any]) {
        val flowMatch = flow.getMatch
        val inPortUUID = flowMatch.getInputPortUUID

        // tags can be null
        val dpTags = new mutable.HashSet[Any]
        if (tags != null)
            dpTags ++= tags


        vifToLocalPortNumber(inPortUUID) match {
            case Some(portNo: Short) =>
                flowMatch
                    .setInputPortNumber(portNo)
                    .unsetInputPortUUID()
                // tag flow with short inPort to be able to perform
                // invalidation
                dpTags += FlowTagger.invalidateDPPort(portNo)
            case None =>
        }

        var flowActions = flow.getActions
        if (flowActions == null)
            flowActions = Nil

        translateActions(flowActions, Option(inPortUUID),
                         Option(dpTags), flow.getMatch) onComplete {
            case Right(actions) =>
                flow.setActions(actions.toList)
                FlowController.getRef() ! AddWildcardFlow(flow, cookie,
                    pktBytes,flowRemovalCallbacks, dpTags)
            case _ =>
                // TODO(pino): should we push a temporary drop flow instead?
                FlowController.getRef() ! AddWildcardFlow(flow, cookie,
                    pktBytes, flowRemovalCallbacks, dpTags)
        }
    }

    def translateActions(actions: Seq[FlowAction[_]],
                         inPortUUID: Option[UUID],
                         dpTags: Option[mutable.Set[Any]],
                         wMatch: WildcardMatch): Future[Seq[FlowAction[_]]] = {
        val translated = Promise[Seq[FlowAction[_]]]()

        // check for VRN port or portSet
        var vrnPort: Option[Either[UUID, UUID]] = None
        for (action <- actions) {
            action match {
                case s: FlowActionOutputToVrnPortSet if (vrnPort == None ) =>
                    vrnPort = Some(Right(s.portSetId))
                case p: FlowActionOutputToVrnPort if (vrnPort == None) =>
                    vrnPort = Some(Left(p.portId))
                case u: FlowActionUserspace =>
                    u.setUplinkPid(datapathConnection.getChannel.getLocalAddress.getPid)
                case _ =>
            }
        }

        vrnPort match {
            case Some(Right(portSet)) =>
                // we need to expand a port set

                val portSetFuture = ask(
                    VirtualToPhysicalMapper.getRef(),
                    PortSetRequest(portSet, update = false)).mapTo[PortSet]

                val bridgeFuture = ask(
                    VirtualTopologyActor.getRef(),
                    BridgeRequest(portSet, update = false)).mapTo[RCUBridge]

                portSetFuture map {
                    set => bridgeFuture onSuccess {
                        case br =>
                            // Don't include the input port in the expanded
                            // port set.
                            var outPorts = set.localPorts
                            inPortUUID foreach { p => outPorts -= p }
                            log.info("inPort: {}", inPortUUID)
                            log.info("local ports: {}", set.localPorts)
                            log.info("local ports minus inPort: {}", outPorts)
                            // add tag for flow invalidation
                            dpTags foreach { tags =>
                                tags += FlowTagger.invalidateBroadcastFlows(
                                    br.id, br.id)
                            }
                            val localPortFutures =
                                outPorts.toSeq map {
                                    portID => ask(VirtualTopologyActor.getRef(),
                                                  PortRequest(portID, false))
                                              .mapTo[client.Port[_]]
                                }
                            Future.sequence(localPortFutures) onComplete {
                                case Right(localPorts) =>
                                    applyOutboundFilters(localPorts,
                                        portSet, wMatch,
                                        { portIDs => translated.success(
                                            translateToDpPorts(
                                                actions, portSet,
                                                portsForLocalPorts(portIDs),
                                                Some(br.tunnelKey),
                                                tunnelsForHosts(set.hosts.toSeq),
                                                dpTags.orNull))
                                        })

                                case _ => log.error("Error getting " +
                                    "configurations of local ports of " +
                                    "PortSet {}", portSet)
                            }
                    }
                }

            case Some(Left(port)) =>
                // we need to translate a single port
                vifToLocalPortNumber(port) match {
                    case Some(localPort) =>
                        translated.success(
                            translateToDpPorts(actions, port, List(localPort),
                                None, Nil, dpTags.orNull))
                    case None =>
                        ask(VirtualTopologyActor.getRef(), PortRequest(port,
                            update = false)).mapTo[client.Port[_]] map {
                                case p: ExteriorPort[_] =>
                                    translated.success(translateToDpPorts(
                                            actions, port, Nil,
                                            Some(p.tunnelKey),
                                            tunnelsForHosts(List(p.hostID)),
                                            dpTags.orNull))
                        }
                }
            case None =>
                translated.success(actions)
        }
        translated.future
    }

    def translateToDpPorts(acts: Seq[FlowAction[_]], port: UUID,
                           localPorts: Seq[Short],
                           tunnelKey: Option[Long], tunnelPorts: Seq[Short],
                           dpTags: mutable.Set[Any]): Seq[FlowAction[_]] = {
        log.debug("Translating port {}, ports in the same set {}, tunnelkey {}, " +
                  "tunnelports {}", port, localPorts, tunnelKey, tunnelPorts)
        val newActs = ListBuffer[FlowAction[_]]()
        var newTags = new mutable.HashSet[Any]

        var translatablePort = port

        var translatedActions = localPorts.map { id =>
            FlowActions.output(id).asInstanceOf[FlowAction[_]]
        }
        // add tag for flow invalidation
        localPorts.foreach{id =>
            newTags += FlowTagger.invalidateDPPort(id)
        }

        if (null != tunnelPorts && tunnelPorts.length > 0) {
            translatedActions = translatedActions ++ tunnelKey.map { key =>
                FlowActions.setKey(FlowKeys.tunnelID(key))
                    .asInstanceOf[FlowAction[_]]
            } ++ tunnelPorts.map { id =>
                FlowActions.output(id).asInstanceOf[FlowAction[_]]
            }
            tunnelPorts.foreach{id => newTags += FlowTagger.invalidateDPPort(id)}
        }

        for (act <- acts) {
            act match {
                case p: FlowActionOutputToVrnPort if (p.portId == translatablePort) =>
                    newActs ++= translatedActions
                    translatablePort = null
                    if (dpTags != null)
                        dpTags ++= newTags

                case p: FlowActionOutputToVrnPortSet if (p.portSetId == translatablePort) =>
                    newActs ++= translatedActions
                    translatablePort = null
                    if (dpTags != null)
                        dpTags ++= newTags

                // we only translate the first ones.
                case x: FlowActionOutputToVrnPort =>
                case x: FlowActionOutputToVrnPortSet =>

                case a => newActs += a
            }
        }

        newActs
    }

    def tunnelsForHosts(hosts: Seq[UUID]): Seq[Short] = {
        val tunnels = mutable.ListBuffer[Short]()

        def tunnelForHost(host: UUID): Option[Short] = {
            peerPorts.get(host) match {
                case None =>
                case Some(zoneTunnels) =>
                    zoneTunnels.values.head match {
                        case tunnelName: String =>
                            localPorts.get(tunnelName) match {
                                case Some(port) =>
                                    return Some(port.getPortNo.shortValue())
                                case None =>
                            }
                    }
            }

            None
        }

        for ( host <- hosts ) {
            tunnelForHost(host) match {
                case None =>
                case Some(localTunnelValue) => tunnels += localTunnelValue
            }
        }

        tunnels
    }

    def portsForLocalPorts(localVrnPorts: Seq[UUID]): Seq[Short] = {
        localVrnPorts map {
            vifToLocalPortNumber(_) match {
                case Some(value) => value
                case None => null.asInstanceOf[Short]
            }
        }
    }

    def translateToLocalPort(acts: Seq[FlowAction[_]], port: UUID, localPort: Short): Seq[FlowAction[_]] = {
        val translatedActs = mutable.ListBuffer[FlowAction[_]]()

        for (act <- acts) {
            act match {
                case port: FlowActionOutputToVrnPort if (port.portId == port) =>
                    translatedActs += FlowActions.output(localPort)

                case port: FlowActionOutputToVrnPort =>
                    // this should not happen so we drop it
                case set: FlowActionOutputToVrnPortSet =>
                    // this should not happen so we drop it
                case action =>
                    translatedActs += action

            }
        }

        translatedActs
    }

    def vifToLocalPortNumber(vif: UUID): Option[Short] = {
        vifPorts.get(vif) match {
            case Some(tapName: String) =>
                localPorts.get(tapName) match {
                    case Some(p: Port[_, _]) => Some[Short](p.getPortNo.shortValue())
                    case _ => None
                }
            case _ => None
        }
    }

    /**
     * Once a port has been created/removed from the datapath, this method
     * adds/removes the port to the DatapathController's map of ports,
     * tells the VirtualToPhysicalMapper and installs/invalidates a flow to
     * match the port's tunnelKey.
     */
    private def finalizePortActivation(port: Port[_,_], vifId: UUID,
                                       active: Boolean) {
        def tellVtpm() {
            VirtualToPhysicalMapper.getRef() ! LocalPortActive(vifId, active)
        }

        if (active) {
            localToVifPorts.put(port.getPortNo.shortValue, vifId)
            log.debug("Port {} became active", port.getPortNo.shortValue())
        }
        else {
            localToVifPorts.remove(port.getPortNo.shortValue)
            log.debug("Port {} became inactive", port.getPortNo.shortValue())
        }
        port match {
            case netdev: NetDevPort =>
                val clientPortFuture = VirtualTopologyActor.getRef() ?
                    PortRequest(vifId, update = false)

                clientPortFuture.mapTo[client.ExteriorPort[_]] onComplete {
                    case Right(exterior) =>
                        // trigger invalidation. This is done regardless of
                        // whether we are activating or deactivating:
                        //
                        //   + The case for invalidating on deactivation is
                        //     obvious.
                        //   + On activation we invalidate flows for this dp port
                        //     number in case it has been reused by the dp: we
                        //     want to start with a clean state.
                        FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
                            FlowTagger.invalidateDPPort(port.getPortNo.shortValue()))

                        if (active) {
                            // packets for the port may have arrived before the
                            // port came up and made us install temporary drop flows.
                            // Invalidate them before adding the new flow
                            FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
                                FlowTagger.invalidateByTunnelKey(exterior.tunnelKey))

                            addTaggedFlow(new WildcardMatch().setTunnelID(exterior.tunnelKey),
                                    List(FlowActions.output(port.getPortNo.shortValue)),
                                    tags = Set(FlowTagger.invalidateDPPort(port.getPortNo.shortValue())),
                                    expiration = 0)
                            log.debug("Added flow for tunnelkey {}", exterior.tunnelKey)
                        }
                        tellVtpm()
                    case _ =>
                        log.warning("local port activated, but it's not an " +
                            "ExteriorPort, I don't know what to do with it: {}",
                            port)
                        tellVtpm()
                }
            case _ =>
                log.warning("local port activated, but it's not a " +
                    "NetDevPort, I don't know what to do with it: {}", port)
                tellVtpm()
        }
    }

    private def addDropFlow(wMatch: WildcardMatch,
                        cookie: Option[Int] = None,
                        expiration: Long = 3000) {
        log.debug("adding drop flow for PacketIn match {}",
            wMatch)
        FlowController.getRef().tell(
            AddWildcardFlow(new WildcardFlow().setMatch(wMatch)
                .setIdleExpirationMillis(expiration),
                None, null, null, null))
    }

    private def addTaggedFlow(wMatch: WildcardMatch,
                        actions: Seq[FlowAction[_]],
                        tags: ROSet[Any],
                        cookie: Option[Int] = None,
                        pktBytes: Array[Byte] = null,
                        expiration: Long = 3000,
                        priority: Short = 0) {
        log.debug("adding flow with match {} with actions {}",
                  wMatch, actions)

        FlowController.getRef().tell(
                AddWildcardFlow(new WildcardFlow().setMatch(wMatch)
                                        .setIdleExpirationMillis(expiration)
                                        .setActions(actions)
                                        .setPriority(priority),
                                cookie,
                                if (actions == Nil) null else pktBytes,
                                null, tags))
    }

    def handleFlowPacketIn(wMatch: WildcardMatch, pktBytes: Array[Byte],
                           dpMatch: FlowMatch, reason: Packet.Reason,
                           cookie: Option[Int]) {

        wMatch.getInputPortNumber match {
            case port: JShort =>
                log.debug("PacketIn on port #{}", port)
                if (localToVifPorts.contains(port)) {
                    wMatch.setInputPortUUID(localToVifPorts(port))
                    SimulationController.getRef().tell(
                        PacketIn(wMatch, pktBytes, dpMatch, reason, cookie))
                    return
                } else if (localTunnelPorts.contains(port)) {
                    log.debug("PacketIn came from a tunnel port")
                    if (wMatch.getTunnelID == null) {
                        log.error("SCREAM: got a PacketIn on a tunnel port " +
                                  "and a wildcard match with no tunnel ID; " +
                                  "dropping all flows from tunnel port #{}",
                                  port)
                        addDropFlow(new WildcardMatch().setInputPort(port),
                                    cookie)
                        return
                    }

                    val portSetFuture = VirtualToPhysicalMapper.getRef() ?
                        PortSetForTunnelKeyRequest(wMatch.getTunnelID)

                    portSetFuture.mapTo[PortSet] onComplete {
                        case Right(portSet) if (portSet != null) =>
                            val action = new FlowActionOutputToVrnPortSet(portSet.id)
                            log.debug("tun => portSet, action: {}, portSet: {}",
                                action, portSet)
                            // egress port filter simulation
                            val localPortFutures =
                                portSet.localPorts.toSeq map {
                                    portID => ask(VirtualTopologyActor.getRef(),
                                                  PortRequest(portID, false))
                                              .mapTo[client.Port[_]]
                                }
                            Future.sequence(localPortFutures) onComplete {
                                // Take the outgoing filter for each port
                                // and apply it, checking for Action.ACCEPT.
                                case Right(localPorts) =>
                                    applyOutboundFilters(localPorts,
                                        portSet.id, wMatch,
                                        { portIDs =>
                                          val tags = mutable.Set[Any]()
                                          addTaggedFlow(new WildcardMatch()
                                                .setTunnelID(wMatch.getTunnelID)
                                                .setInputPort(port),
                                             translateToDpPorts(List(action),
                                                portSet.id,
                                                portsForLocalPorts(portIDs),
                                                None, Nil, tags),
                                             tags, cookie, pktBytes)
                                        })
                                case _ => log.error("Error getting " +
                                    "configurations of local ports of " +
                                    "PortSet {}", portSet)
                            }

                        case _ =>
                            // for now, install a drop flow. We will invalidate
                            // it if the port comes up later on.
                            log.debug("PacketIn came from a tunnel port but " +
                                "the key does not map to any PortSet")
                            addTaggedFlow(new WildcardMatch().
                                            setTunnelID(wMatch.getTunnelID).
                                            setInputPort(port),
                                actions = Nil,
                                tags = Set(FlowTagger.invalidateByTunnelKey(
                                               wMatch.getTunnelID)),
                                cookie = cookie)
                    }

                } else {
                    // Otherwise, drop the flow. There's a port on the DP that
                    // doesn't belong to us and is receiving packets.
                    addTaggedFlow(new WildcardMatch().setInputPort(port),
                        actions = Nil,
                        tags = Set(FlowTagger.invalidateDPPort(port)),
                        cookie = cookie,
                        priority = 1000) // TODO(abel) use a constant here
                }

            case _ =>
                // Missing InputPortNumber. This should never happen.
                log.error("SCREAM: got a PacketIn that has no inPort number.",
                    wMatch)
        }

    }

    private def applyOutboundFilters(
                    localPorts: Seq[client.Port[_]],
                    portSetID: UUID,
                    pktMatch: WildcardMatch, thunk: Sequence[UUID] => Unit) {
        // Fetch all of the chains.
        val chainFutures = localPorts map { port =>
                if (port.outFilterID == null)
                    Promise.successful(null)
                else
                    ask(VirtualTopologyActor.getRef,
                        ChainRequest(port.outFilterID, false)).mapTo[Chain]
            }
        // Apply the chains.
        Future.sequence(chainFutures) onComplete {
            case Right(chains) =>
                val egressPorts = (localPorts zip chains) filter { portchain =>
                    val port = portchain._1
                    val chain = portchain._2
                    val fwdInfo = new EgressPortSetChainPacketContext(port.id)

                    // apply chain and check result is ACCEPT.
                    val result =
                        Chain.apply(chain, fwdInfo, pktMatch, port.id, true)
                            .action
                    if (result != RuleResult.Action.ACCEPT &&
                            result != RuleResult.Action.DROP &&
                            result != RuleResult.Action.REJECT)
                        log.error("Applying chain {} produced {}, not " +
                                  "ACCEPT, DROP, or REJECT", chain.id, result)
                    result == RuleResult.Action.ACCEPT
                }

                thunk(egressPorts map {portchain => portchain._1.id})

            case _ => log.error("Error getting chains for PortSet {}",
                                portSetID)
        }
    }

    def handleSendPacket(ethPkt: Ethernet, origActions: List[FlowAction[_]]) {
        log.debug("Sending packet {} with action list {}", ethPkt, origActions)
        if (null == origActions || origActions.size == 0) {
            // Empty action list drops the packet. No need to send to DP.
            return
        }
        translateActions(origActions, None, None,
                         WildcardMatches.fromEthernetPacket(ethPkt)) onComplete {
            case Right(actions) =>
                log.debug("Translated actions to action list {}", actions)
                val packet = new Packet().
                    setMatch(FlowMatches.fromEthernetPacket(ethPkt)).
                    setData(ethPkt.serialize).setActions(actions)
                datapathConnection.packetsExecute(datapath, packet,
                    new ErrorHandlingCallback[JBoolean] {
                        def onSuccess(data: JBoolean) {}

                        def handleError(ex: NetlinkException, timeout: Boolean) {
                            log.error(ex,
                                "Failed to send a packet {} due to {}", packet,
                                if (timeout) "timeout" else "error")
                        }
                    }
                )
            case _ =>
                log.error("Failed to translate actions {}", origActions)
        }
    }

    def handlePortOperationReply(opReply: PortOpReply[_]) {
        log.debug("Port operation reply: {}", opReply)

        pendingUpdateCount -= 1
        log.debug("Pending count for handlePortOperationReply {}", pendingUpdateCount)

        def _handleTunnelCreate(port: Port[_,_],
                                hConf: TunnelZone.HostConfig[_,_], zone: UUID) {
            peerPorts.get(hConf.getId) match {
                case Some(tunnels) =>
                    tunnels.put(zone, port.getName)
                    log.debug("handleTunnelCreate - added zone {} port {} to" +
                        "tunnels map", zone, port.getName)
                case None =>
                    peerPorts.put(hConf.getId, mutable.Map(zone -> port.getName))
                    log.debug("handleTunnelCreate - added peer port {}", hConf.getId)

            }
            // trigger invalidation
            val tunnelPortNum: JShort = port.getPortNo.shortValue
            FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
                FlowTagger.invalidateDPPort(tunnelPortNum))
            localTunnelPorts.add(tunnelPortNum)
            log.debug("Adding tunnel with port #{}", tunnelPortNum)
            context.system.eventStream.publish(
                new TunnelChangeEvent(this.host.zones(zone), hConf,
                    Some(tunnelPortNum),
                    TunnelChangeEventOperation.Established))
        }

        def _handleTunnelDelete(port: Port[_,_],
                                hConf: TunnelZone.HostConfig[_,_], zone: UUID) {
            peerPorts.get(hConf.getId) match {
                case Some(zoneTunnelMap) =>
                    zoneTunnelMap.remove(zone)
                    if (zoneTunnelMap.size == 0) {
                        peerPorts.remove(hConf.getId)
                    }
                    // trigger invalidation
                    FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
                        FlowTagger.invalidateDPPort(port.getPortNo.shortValue())
                    )

                case None =>
            }
            localTunnelPorts.remove(port.getPortNo.shortValue)
            log.debug("Removing tunnel with port #{}",
                      port.getPortNo.shortValue)
            context.system.eventStream.publish(
                new TunnelChangeEvent(
                    host.zones(zone), hConf,
                    None, TunnelChangeEventOperation.Removed))
        }

        opReply match {

            case TunnelGreOpReply(p, PortOperation.Create, false, null,
                    Some((hConf: GreTunnelZoneHost, zone: UUID))) =>
                _handleTunnelCreate(p, hConf, zone)

            case TunnelCapwapOpReply(p, PortOperation.Create, false, null,
                    Some((hConf: CapwapTunnelZoneHost, zone: UUID))) =>
                _handleTunnelCreate(p, hConf, zone)

            case TunnelCapwapOpReply(p, PortOperation.Delete, false, null,
                    Some((hConf: CapwapTunnelZoneHost, zone: UUID))) =>
                _handleTunnelDelete(p, hConf, zone)

            case TunnelGreOpReply(p, PortOperation.Delete, false, null,
                    Some((hConf: GreTunnelZoneHost, zone: UUID))) =>
                _handleTunnelDelete(p, hConf, zone)

            case PortNetdevOpReply(p, PortOperation.Create, false, null, Some(vifId: UUID)) =>
                log.info("DP port created. Mapping created: {} -> {}", vifId,
                    p.getPortNo)
                finalizePortActivation(p, vifId, active = true)

            case PortNetdevOpReply(p, PortOperation.Delete, false, null, None) =>
                localToVifPorts.get(p.getPortNo.shortValue()) match {
                    case None =>
                    case Some(vif) =>
                        log.info("Mapping removed: {} -> {}", vif, p.getPortNo)
                        finalizePortActivation(p, vif, active = false)
                }

            //            case PortInternalOpReply(_,_,_,_,_) =>
            //            case TunnelPatchOpReply(_,_,_,_,_) =>
            case reply =>
        }

        opReply.port match {
            case p: Port[_, _] if opReply.error == null && !opReply.timeout =>
                context.system.eventStream.publish(new DatapathPortChangedEvent(p, opReply.op))
                opReply.op match {
                    case PortOperation.Create =>
                        localPorts.put(p.getName, p)
                    case PortOperation.Delete =>
                        localPorts.remove(p.getName)
                }

            case value =>
                log.error("No match {}", value)
        }

        if (pendingUpdateCount == 0) {
            if (!initialized)
                completeInitialization
            else
                processNextHost
        }
    }

    /**
     * Avoid calling this when there are already pending updates.
     * Don't call this during initialization.
     */
    private def doDatapathPortsUpdate {
        val ports: Map[UUID, String] = host.ports
        log.info("Migrating local datapath to configuration {}", ports)
        log.info("Current known local ports: {}", localPorts)

        vifPorts.clear()
        // post myself messages to force the creation of missing ports
        val newTaps: mutable.Set[String] = mutable.Set()
        for ((vifId, tapName) <- ports) {
            vifPorts.put(vifId, tapName)
            newTaps.add(tapName)
            // new port
            if (!localPorts.contains(tapName)) {
                createDatapathPort(
                    self, Ports.newNetDevPort(tapName), Some(vifId))
            }
            // port is already tracked.
            else {
                val p = localPorts(tapName)
                val shortPortNum = p.getPortNo.shortValue()
                if(!localToVifPorts.contains(shortPortNum)) {
                    // The dpPort already existed but hadn't been mapped to a
                    // virtual port UUID. Map it now and notify that the
                    // vport is now active.
                    log.info("DP port exists. Mapping created: {} -> {}",
                        vifId, shortPortNum)
                    finalizePortActivation(p, vifId, active = true)
                }
            }
        }

        // find ports that need to be removed and post myself messages to
        // remove them
        for ((portName, portData) <- localPorts) {
            log.info("Looking at {} -> {}", portName, portData)
            if (!newTaps.contains(portName) && portName != datapath.getName) {
                portData match {
                    case p: NetDevPort =>
                        deleteDatapathPort(self, p, None)
                    case p: InternalPort =>
                        if (p.getPortNo != 0) {
                            deleteDatapathPort(self, p, None)
                        }
                    case default =>
                        log.error("port type not matched {}", default)
                }
            }
        }

        log.info("Pending updates {}", pendingUpdateCount)
        if (pendingUpdateCount == 0)
                processNextHost
    }

    def createDatapathPort(caller: ActorRef, port: Port[_, _], tag: Option[AnyRef]) {
        if (caller == self)
            pendingUpdateCount += 1
        log.info("creating port: {} (by request of: {})", port, caller)

        datapathConnection.portsCreate(datapath, port,
            new ErrorHandlingCallback[Port[_, _]] {
                def onSuccess(data: Port[_, _]) {
                    sendOpReply(caller, data, tag, PortOperation.Create, null, timeout = false)
                }

                def handleError(ex: NetlinkException, timeout: Boolean) {
                    sendOpReply(caller, port, tag, PortOperation.Create, ex, timeout)
                }
            })
    }

    def deleteDatapathPort(caller: ActorRef, port: Port[_, _], tag: Option[AnyRef]) {
        if (caller == self)
            pendingUpdateCount += 1
        log.info("deleting port: {} (by request of: {})", port, caller)

        datapathConnection.portsDelete(port, datapath, new ErrorHandlingCallback[Port[_, _]] {
            def onSuccess(data: Port[_, _]) {
                sendOpReply(caller, data, tag, PortOperation.Delete, null, timeout = false)
            }

            def handleError(ex: NetlinkException, timeout: Boolean) {
                sendOpReply(caller, port, tag, PortOperation.Delete, ex, timeout = false)
            }
        })
    }

    private def sendOpReply(actor: ActorRef, port: Port[_, _], tag: Option[AnyRef],
                            op: PortOperation.Value,
                            ex: NetlinkException, timeout: Boolean) {
        port match {
            case p: InternalPort =>
                actor ! PortInternalOpReply(p, op, timeout, ex, tag)
            case p: NetDevPort =>
                actor ! PortNetdevOpReply(p, op, timeout, ex, tag)
            case p: PatchTunnelPort =>
                actor ! TunnelPatchOpReply(p, op, timeout, ex, tag)
            case p: GreTunnelPort =>
                actor ! TunnelGreOpReply(p, op, timeout, ex, tag)
            case p: CapWapTunnelPort =>
                actor ! TunnelCapwapOpReply(p, op, timeout, ex, tag)
        }
    }

    /**
     * ONLY USE THIS DURING INITIALIZATION.
     * @param wantedDatapath
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
                context.system.scheduler.scheduleOnce(100 millis, retryTask)
            }
        }

        val dpGetCallback = new ErrorHandlingCallback[Datapath] {
            def onSuccess(dp: Datapath) {
                handleExistingDP(dp)
            }

            def handleError(ex: NetlinkException, timeout: Boolean) {
                if (timeout) {
                    log.error("Timeout while getting the datapath", timeout)
                    context.system.scheduler.scheduleOnce(100 millis, retryTask)
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

    /**
     * ONLY USE THIS DURING INITIALIZATION.
     * @param datapath
     */
    private def queryDatapathPorts(datapath: Datapath) {
        log.info("Enumerating ports for datapath: " + datapath)
        datapathConnection.portsEnumerate(datapath,
            new ErrorHandlingCallback[JSet[Port[_, _]]] {
                def onSuccess(ports: JSet[Port[_, _]]) {
                    self ! _SetLocalDatapathPorts(datapath, ports.toSet)
                }

                // WARN: this is ugly. Normally we should configure the message error handling
                // inside the router
                def handleError(ex: NetlinkException, timeout: Boolean) {
                    context.system.scheduler.scheduleOnce(100 millis, new Runnable {
                        def run() {
                            queryDatapathPorts(datapath)
                        }
                    })
                }
            }
        )
    }

    /**
     * Called when the netlink library receives a packet in
     *
     * @param packet the received packet
     */
    private case class _PacketIn(packet: Packet)

    private case class _SetLocalDatapathPorts(datapath: Datapath, ports: Set[Port[_, _]])

}
