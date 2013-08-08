/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.JavaConverters._
import scala.collection.{Set => ROSet, Map => ROMap}
import scala.collection.mutable.{ConcurrentMap => ConcMap}
import scala.collection.mutable
import akka.actor._
import akka.dispatch.ExecutionContext
import akka.event.LoggingAdapter
import akka.util.Timeout
import akka.util.duration._
import java.lang.{Boolean => JBoolean, Integer => JInteger, Short => JShort}
import java.util.{Collection, Collections, List => JList, Set => JSet, UUID}
import java.util.concurrent.{ConcurrentHashMap => ConcHashMap, TimeUnit}
import java.nio.ByteBuffer

import com.google.inject.Inject

import org.midonet.cluster.client
import org.midonet.cluster.client.ExteriorPort
import org.midonet.cluster.data.TunnelZone
import org.midonet.cluster.data.TunnelZone.{HostConfig => TZHostConfig}
import org.midonet.cluster.data.zones.{IpsecTunnelZoneHost,
        CapwapTunnelZoneHost, GreTunnelZoneHost}
import org.midonet.midolman.FlowController.AddWildcardFlow
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.datapath._
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.monitoring.MonitoringActor
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.rcu.Host
import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.flows.{FlowActions, FlowAction}
import org.midonet.odp.ports._
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.{Flow => KernelFlow, _}
import org.midonet.sdn.flows.WildcardFlow
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.util.collection.Bimap


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

trait VirtualPortsResolver {

    def getDpPortNumberForVport(vportId: UUID): Option[JInteger]

    def getVportForDpPortNumber(portNum: JInteger): Option[UUID]

    def getDpPortName(num: JInteger): Option[String]

}

trait TunnelPortsResolver {

    def localTunnelPorts: ROSet[JInteger]

    def peerToTunnels: ROMap[UUID, ROMap[UUID, Port[_,_]]]

}

trait DatapathState extends VirtualPortsResolver with TunnelPortsResolver {

    // TODO(guillermo) - for future use. Decisions based on the datapath state
    // need to be aware of its versioning bacause flows added by fast-path
    // simulations race with invalidations sent out-of-band by the datapath
    // controller to the flow controller.
    def version: Long

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
     * Message sent to the [[org.midonet.midolman.FlowController]] actor to let
     * it know that it can install the the packetIn hook inside the datapath.
     *
     * @param datapath the active datapath
     */
    case class DatapathReady(datapath: Datapath, state: DatapathState)

    /**
     * Will trigger an internal port creation operation. The sender will
     * receive an [[org.midonet.midolman.DatapathController.PortInternalOpReply]]
     * message in return.
     *
     * @param port the port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class CreatePortInternal(port: InternalPort, tag: Option[AnyRef])
        extends CreatePortOp[InternalPort]

    /**
     * Will trigger an internal port delete operation. The sender will
     * receive an [[org.midonet.midolman.DatapathController.PortInternalOpReply]]
     * message when the operation is completed.
     *
     * @param port the port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class DeletePortInternal(port: InternalPort, tag: Option[AnyRef])
        extends DeletePortOp[InternalPort]

    /**
     * Reply message that is sent when a [[org.midonet.midolman.DatapathController.CreatePortInternal]]
     * or [[org.midonet.midolman.DatapathController.DeletePortInternal]]
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
     * receive an [[org.midonet.midolman.DatapathController.PortNetdevOpReply]]
     * message in return.
     *
     * @param port the port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class DeletePortNetdev(port: NetDevPort, tag: Option[AnyRef])
        extends DeletePortOp[NetDevPort]

    /**
     * Reply message that is sent when a [[org.midonet.midolman.DatapathController.CreatePortNetdev]]
     * or [[org.midonet.midolman.DatapathController.DeletePortNetdev]]
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
     * receive an [[org.midonet.midolman.DatapathController.TunnelPatchOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class CreateTunnelPatch(port: PatchTunnelPort, tag: Option[AnyRef])
        extends CreatePortOp[PatchTunnelPort]

    /**
     * Will trigger an `patch` tunnel deletion operation. The sender will
     * receive an [[org.midonet.midolman.DatapathController.TunnelPatchOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class DeleteTunnelPatch(port: PatchTunnelPort, tag: Option[AnyRef])
        extends DeletePortOp[PatchTunnelPort]

    /**
     * Reply message that is sent when a [[org.midonet.midolman.DatapathController.CreateTunnelPatch]]
     * or [[org.midonet.midolman.DatapathController.DeleteTunnelPatch]]
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
     * receive an [[org.midonet.midolman.DatapathController.TunnelGreOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class CreateTunnelGre(port: GreTunnelPort, tag: Option[AnyRef])
        extends CreatePortOp[GreTunnelPort]

    /**
     * Will trigger an `gre` tunnel deletion operation. The sender will
     * receive an [[org.midonet.midolman.DatapathController.TunnelGreOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class DeleteTunnelGre(port: GreTunnelPort, tag: Option[AnyRef])
        extends DeletePortOp[GreTunnelPort]

    /**
     * Reply message that is sent when a [[org.midonet.midolman.DatapathController.CreateTunnelGre]]
     * or [[org.midonet.midolman.DatapathController.DeleteTunnelGre]]
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
     * receive an [[org.midonet.midolman.DatapathController.TunnelCapwapOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class CreateTunnelCapwap(port: CapWapTunnelPort, tag: Option[AnyRef])
        extends CreatePortOp[CapWapTunnelPort]

    /**
     * Will trigger an `capwap` tunnel deletion operation. The sender will
     * receive an [[org.midonet.midolman.DatapathController.TunnelCapwapOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     * @param tag a value that is going to be copied to the reply message
     */
    case class DeleteTunnelCapwap(port: CapWapTunnelPort, tag: Option[AnyRef])
        extends DeletePortOp[CapWapTunnelPort]

    /**
     * Reply message that is sent when a [[org.midonet.midolman.DatapathController.CreateTunnelCapwap]]
     * or [[org.midonet.midolman.DatapathController.DeleteTunnelCapwap]]
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
    * This message encapsulates a given port stats to the monitoring agent.
    * @param stats
    */
    case class PortStats(portID: UUID, stats: Port.Stats)

    class DatapathPortChangedEvent(val port: Port[_, _], val op: PortOperation.Value) {}

    class TunnelChangeEvent(val myself: Option[TZHostConfig[_,_]],
                            val peer: TZHostConfig[_, _],
                            val portOption: Option[Short],
                            val op: TunnelChangeEventOperation.Value)

    /**
     * This message requests stats for a given port.
     * @param portID
     */
    case class PortStatsRequest(portID: UUID)

    /**
     * This message is sent every 2 seconds to check that the kernel contains exactly the same
     * ports/interfaces as the system. In case that somebody uses a command line tool (for example)
     * to bring down an interface, the system will react to it.
     * TODO this version is constantly checking for changes. It should react to 'netlink' notifications instead.
     */
    case class CheckForPortUpdates(datapathName: String)

    /**
     * This message is sent when the separate thread has succesfully retrieved all information about the interfaces.
     */
    case class InterfacesUpdate(interfaces: JList[InterfaceDescription])

    /**
     * This message is sent when the DHCP handler needs to get information
     * on local interfaces that are used for tunnels, what it returns is
     * { interface description , list of {tunnel type} } where the
     * interface description contains various information (including MTU)
     */
    case class LocalTunnelInterfaceInfo()

    /**
     * This message is sent when the LocalTunnelInterfaceInfo handler
     * completes the interface scan and pass the result as well as
     * original sender info
     */
    private case class LocalInterfaceTunnelInfoFinal(caller : ActorRef,
                                             interfaces: JList[InterfaceDescription])

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
class DatapathController() extends Actor with ActorLogging with
                                              FlowTranslator {

    import DatapathController._
    import VirtualToPhysicalMapper._
    import VirtualPortManager.Controller
    import context._

    implicit val executor: ExecutionContext = this.context.dispatcher
    implicit val system = this.context.system

    override implicit val requestReplyTimeout: Timeout = new Timeout(1 second)

    @Inject
    val datapathConnection: OvsDatapathConnection = null

    @Inject
    val hostService: HostIdProviderService = null

    @Inject
    val interfaceScanner: InterfaceScanner = null

    var datapath: Datapath = null

    val dpState = new DatapathStateManager(
        new VirtualPortManager(new Controller {
            override def addToDatapath(itfName: String): Unit = {
                log.debug("VportManager requested add port {}", itfName)
                createDatapathPort(self, Ports.newNetDevPort(itfName), None)
            }

            override def removeFromDatapath(port: Port[_, _]): Unit = {
                log.debug("VportManager requested remove port {}", port.getName)
                deleteDatapathPort(self, port, None)
            }

            override def setVportStatus(port: Port[_, _], vportId: UUID,
                                        isActive: Boolean): Unit = {
                log.debug("Port {}/{}/{} became {}", port.getPortNo,
                    port.getName, vportId, if (isActive) "active" else "inactive")
                installTunnelKeyFlow(port, vportId, isActive)
                VirtualToPhysicalMapper.getRef() !
                    LocalPortActive(vportId, isActive)
            }
        }, log))

    val zonesToTunnels: mutable.MultiMap[UUID, Port[_,_]] =
        new mutable.HashMap[UUID, mutable.Set[Port[_,_]]] with
            mutable.MultiMap[UUID, Port[_,_]]
    val tunnelsToHosts = mutable.Map[JInteger, TZHostConfig[_,_]]()

    var recentInterfacesScanned = new java.util.ArrayList[InterfaceDescription]()

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

    def vifToLocalPortNumber(vportId: UUID): Option[Short] =
        dpState.getDpPortNumberForVport(vportId) map { _.shortValue }

    def ifaceNameToDpPort(itfName: String): Port[_, _] =
        dpState.getDpPortForInterface(itfName).getOrElse(null)

    val DatapathInitializationActor: Receive = {

        /**
         * Initialization request message
         */
        case Initialize() =>
            initializer = sender
            log.info("Initialize from: " + sender)
            VirtualToPhysicalMapper.getRef() ! HostRequest(hostService.getHostId)

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
                        dpState.dpPortAdded(p)
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
            log.debug("Got CreatePortOp {} message from myself", newPortOp)
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
    private def completeInitialization() {
        log.info("Initialization complete. Starting to act as a controller.")
        initialized = true
        become(DatapathControllerActor)
        FlowController.getRef() ! DatapathController.DatapathReady(datapath, dpState)
        DeduplicationActor.getRef() ! DatapathController.DatapathReady(datapath, dpState)
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
        log.info("Process the host's zones and vport bindings. {}", host)
        dpState.updateVPortInterfaceBindings(host.ports)
    }

    private def processNextHost() {
        if (null != nextHost && pendingUpdateCount == 0) {
            val oldZones = host.zones
            val newZones = nextHost.zones

            host = nextHost
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
            VirtualToPhysicalMapper.getRef() ! TunnelZoneUnsubscribe(zone)
            dropTunnelsInZone(zone)
        }

        val added = newZones.keySet.diff(oldZones.keySet)
        for (zone <- added) {
            VirtualToPhysicalMapper.getRef() ! TunnelZoneRequest(zone)
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

        case AddVirtualWildcardFlow(flow, callbacks, tags) =>
            log.debug("Translating and installing wildcard flow: {}", flow)
            translateVirtualWildcardFlow(flow, tags) onSuccess {
                case (finalFlow, finalTags) =>
                    log.debug("flow translated, installing: {}", finalFlow)
                    FlowController.getRef() !
                        AddWildcardFlow(finalFlow, None, callbacks, finalTags)
            }

        case h: Host =>
            this.nextHost = h
            processNextHost()

        case zoneMembers: ZoneMembers[_] =>
            if (!host.zones.contains(zoneMembers.zone)) {
                log.debug("Got ZoneMembers for zone:{} but I'm no " +
                    "longer subscribed", zoneMembers.zone)
            } else {
                log.debug("ZoneMembers: {}", zoneMembers)
                for (member <- zoneMembers.members) {
                    handleZoneChange(zoneMembers.zone,
                                     member.asInstanceOf[TZHostConfig[_,_]],
                                     HostConfigOperation.Added)
                }
            }

        case m: ZoneChanged[_] =>
            log.debug("ZoneChanged: {}", m)
            handleZoneChange(m)

        case newPortOp: CreatePortOp[Port[_, _]] =>
            log.debug("Got CreatePortOp {} from {}", newPortOp, sender)
            createDatapathPort(sender, newPortOp.port, newPortOp.tag)

        case delPortOp: DeletePortOp[Port[_, _]] =>
            deleteDatapathPort(sender, delPortOp.port, delPortOp.tag)

        case opReply: PortOpReply[Port[_, _]] =>
            handlePortOperationReply(opReply)

        case Messages.Ping(value) =>
            sender ! Messages.Pong(value)

        case PortStatsRequest(portID) =>
            dpState.getInterfaceForVport(portID) match {
                case Some(portName) =>
                    datapathConnection.portsGet(portName, datapath,
                        new Callback[Port[_,_]]{
                            def onSuccess(data: Port[_, _]) {
                                MonitoringActor.getRef() !
                                        PortStats(portID, data.getStats)
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

        case CheckForPortUpdates(datapathName: String) =>
            checkPortUpdates()

        case InterfacesUpdate(interfaces: JList[InterfaceDescription]) =>
            log.debug("Updating interfaces to {}", interfaces)
            dpState.updateInterfaces(interfaces)

        case LocalTunnelInterfaceInfo() =>
            getLocalInterfaceTunnelPhaseOne(sender)

        case LocalInterfaceTunnelInfoFinal(caller : ActorRef,
                interfaces: JList[InterfaceDescription]) =>
            getLocalInterfaceTunnelInfo(caller, interfaces)

        case m => log.error("Unhandled message {}", m)
    }

    def checkPortUpdates() {
        log.debug("Scanning interfaces for status changes.")
        interfaceScanner.scanInterfaces(new Callback[JList[InterfaceDescription]] {
            def onError(e: NetlinkException) {
                log.error("Error while retrieving the interface status:" + e.getMessage)
            }

            def onTimeout() {
                log.error("Timeout while retrieving the interface status.")
            }

            def onSuccess(data: JList[InterfaceDescription]) {
                self ! InterfacesUpdate(data)
            }
        })
    }

    def newTunnelPort[HostType <: TZHostConfig[_,_]](
            source: HostType, target: HostType): Port[_,_] = {
        source match {
            case capwap: CapwapTunnelZoneHost =>
                val name = "tncpw%08X" format target.getIp.addressAsInt()
                Ports.newCapwapTunnelPort(name)
            case gre: GreTunnelZoneHost =>
                val name = "tngre%08X" format target.getIp.addressAsInt()
                Ports.newGreTunnelPort(name)
            case ipsec: IpsecTunnelZoneHost =>
                val name = "ipsec%08X" format target.getIp.addressAsInt()
                log.error("Tunnel host type not implemented: {}", source)
                null
            case _ =>
                log.error("Tunnel host config did not match: {}", source)
                null
        }
    }

    def handleZoneChange(m: ZoneChanged[_]) {
        val hostConfig = m.hostConfig.asInstanceOf[TZHostConfig[_, _]]
        handleZoneChange(m.zone, hostConfig, m.op)
    }

    def handleZoneChange(zone: UUID,
                         hostConfig: TZHostConfig[_,_],
                         op: HostConfigOperation.Value) {
        def _closeTunnel[HostType <: TZHostConfig[_,_]](peerConf: HostType) {
            dpState.peerToTunnels.get(peerConf.getId).foreach {
                mapping => mapping.get(zone).foreach {
                    case tunnelPort: Port[_,_] =>
                        log.debug("Need to close the tunnel with name: {}",
                            tunnelPort.getName)
                        deleteDatapathPort(self, tunnelPort, Some((peerConf, zone)))
                }
            }
        }

        def _openTunnel[HostType <: TZHostConfig[_,_]](peerConf: HostType) {
            val myConfig = host.zones(zone)
            val tunnelPort = newTunnelPort(myConfig, peerConf)
            tunnelPort.setOptions()
            val options = tunnelPort.getOptions.asInstanceOf[TunnelPortOptions[_]]
            options.setSourceIPv4(myConfig.getIp.addressAsInt())
            options.setDestinationIPv4(peerConf.getIp.addressAsInt())
            log.debug("Opening tunnel port {}", tunnelPort)
            createDatapathPort(self, tunnelPort, Some((peerConf, zone)))
        }

        if (hostConfig.getId == host.id)
            return
        if (!host.zones.contains(zone))
            return

        hostConfig match {
            case peer: GreTunnelZoneHost if op == HostConfigOperation.Added =>
                log.info("Opening a tunnel port to {}", hostConfig)
                _openTunnel(peer)

            case peer: CapwapTunnelZoneHost if op == HostConfigOperation.Added =>
                log.info("Opening a tunnel port to {}", hostConfig)
                _openTunnel(peer)

            case peer: GreTunnelZoneHost if op == HostConfigOperation.Deleted =>
                log.info("Closing a tunnel port to {}", hostConfig)
                _closeTunnel(peer)

            case peer: CapwapTunnelZoneHost if op == HostConfigOperation.Deleted =>
                log.info("Closing a tunnel port to {}", hostConfig)
                _closeTunnel(peer)

            case _ =>
        }
    }

    def dropTunnelsInZone(zoneId: UUID) {
        zonesToTunnels.get(zoneId) foreach { tunnels =>
            log.info("dropping all tunnels in zone: {}", zoneId)
            for (port <- tunnels) {
                tunnelsToHosts.get(port.getPortNo) match {
                    case Some(tzhost: GreTunnelZoneHost) =>
                        deleteDatapathPort(self, port, Some((tzhost, zoneId)))
                    case Some(tzhost: CapwapTunnelZoneHost) =>
                        deleteDatapathPort(self, port, Some((tzhost, zoneId)))
                    case _ =>
                        log.error("Cannot find TZHost for port {} while "+
                            "dropping tunnels in zone {}", port.getPortNo, zoneId)
                }
            }
        }
    }

    private def installTunnelKeyFlow(port: Port[_, _], exterior: ExteriorPort[_]) {
        val fc = FlowController.getRef()
        // packets for the port may have arrived before the
        // port came up and made us install temporary drop flows.
        // Invalidate them before adding the new flow
        fc ! FlowController.InvalidateFlowsByTag(
            FlowTagger.invalidateByTunnelKey(exterior.tunnelKey))

        val wMatch = new WildcardMatch().setTunnelID(exterior.tunnelKey)
        val actions = List[FlowAction[_]](FlowActions.output(port.getPortNo.shortValue))
        val tags = Set[Any](FlowTagger.invalidateDPPort(port.getPortNo.shortValue()))
        fc ! AddWildcardFlow(WildcardFlow(wcmatch = wMatch, actions = actions),
                             None, ROSet.empty, tags)
        log.debug("Added flow for tunnelkey {}", exterior.tunnelKey)
    }

    private def installTunnelKeyFlow(port: Port[_, _], vifId: UUID, active: Boolean) {
        val clientPortFuture = VirtualTopologyActor.expiringAsk(
            PortRequest(vifId, update = false))

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
                    installTunnelKeyFlow(port, exterior)
                }

            case _ =>
                log.warning("local port activated, but it's not an " +
                    "ExteriorPort, I don't know what to do with it: {}",
                    port)

        }
    }

    def handlePortOperationReply(opReply: PortOpReply[_]) {
        log.debug("Port operation reply: {}", opReply)

        pendingUpdateCount -= 1
        log.debug("Pending count for handlePortOperationReply {}", pendingUpdateCount)

        def _handleTunnelCreate(port: Port[_,_],
                                hConf: TZHostConfig[_,_], zone: UUID) {
            dpState.addPeerTunnel(hConf.getId, zone, port)
            log.debug("handleTunnelCreate - added zone {} port {} to" +
                      "tunnels map", zone, port.getName)
            tunnelsToHosts.put(port.getPortNo, hConf)
            zonesToTunnels.addBinding(zone, port)
            // trigger invalidation
            val tunnelPortNum: JShort = port.getPortNo.shortValue
            dpState.addLocalTunnelPort(tunnelPortNum.intValue)
            FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
                FlowTagger.invalidateDPPort(tunnelPortNum))
            log.debug("Adding tunnel with port #{}", tunnelPortNum)
            context.system.eventStream.publish(
                new TunnelChangeEvent(this.host.zones.get(zone), hConf,
                    Some(tunnelPortNum),
                    TunnelChangeEventOperation.Established))
        }

        def _handleTunnelDelete(port: Port[_,_],
                                hConf: TZHostConfig[_,_], zone: UUID) {
            val invalidate = dpState.removePeerTunnel(hConf.getId, zone)
            tunnelsToHosts.remove(port.getPortNo)
            zonesToTunnels.removeBinding(zone, port)
            dpState.removeLocalTunnelPort(port.getPortNo.shortValue)
            if (invalidate) {
                FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateDPPort(port.getPortNo.shortValue()))
            }
            log.debug("Removing tunnel with port #{}",
                      port.getPortNo.shortValue)
            context.system.eventStream.publish(
                new TunnelChangeEvent(
                    host.zones.get(zone), hConf,
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

            case PortNetdevOpReply(p, PortOperation.Create,
                                   false, null, None) =>
                dpState.dpPortAdded(p)

            case PortNetdevOpReply(p, PortOperation.Delete,
                                   false, null, None) =>
                dpState.dpPortRemoved(p)

            case PortNetdevOpReply(p, PortOperation.Create, false, ex, tag)  if (ex != null) =>
                log.warning("port {} creation failed: OVS returned {}",
                    p, ex.getErrorCodeEnum)
                // This will make the vport manager retry the create operation
                // the next time the interfaces are scanned (2 secs).
                if (ex.getErrorCodeEnum == ErrorCode.EBUSY)
                    dpState.dpPortForget(p)

            case PortNetdevOpReply(p, PortOperation.Create, timeout, ex, tag) =>
                log.warning("UNHANDLED port {} creation op reply: " +
                            "OVS returned {}, timeout: {}, ex: {}, tag:{}",
                            p, timeout, ex, tag)

            //            case PortInternalOpReply(_,_,_,_,_) =>
            //            case TunnelPatchOpReply(_,_,_,_,_) =>
            case _ =>
        }

        if (opReply.error == null && !opReply.timeout) {
            context.system.eventStream.publish(
                new DatapathPortChangedEvent(
                    opReply.port.asInstanceOf[Port[_, _]], opReply.op))
        } else if (opReply.error != null) {
            log.warning("Failed to delete port: {} due to error: {}",
                opReply.port, opReply.error)
        } else if (opReply.timeout) {
            log.warning("Failed to delete port: {} due to timeout", opReply.port)
        }

        if (pendingUpdateCount == 0) {
            if (!initialized)
                completeInitialization()
            else
                processNextHost()
        }
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
                // check if the port has already been removed, if that's the
                // case we can consider that the delete operation succeeded
                if (ex.getErrorCodeEnum == NetlinkException.ErrorCode.ENOENT) {
                    sendOpReply(caller, port, tag, PortOperation.Delete, null, timeout = false)
                } else {
                    sendOpReply(caller, port, tag, PortOperation.Delete, ex, timeout = false)
                }
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

    private def getLocalInterfaceTunnelPhaseOne(caller: ActorRef) {
        if (recentInterfacesScanned.isEmpty == false) {
            log.debug("Interface Scanning took place and cache is hot")
            getLocalInterfaceTunnelInfo(caller, recentInterfacesScanned)
        } else {
            log.debug("Interface Scanning has not taken place, trigger interface scan")
            interfaceScanner.scanInterfaces(new Callback[JList[InterfaceDescription]] {
                def onError(e: NetlinkException) {
                    log.error("Error while retrieving the interface status:" + e.getMessage)
                }

                def onTimeout() {
                    log.error("Timeout while retrieving the interface status.")
                }

                def onSuccess(data: JList[InterfaceDescription]) {
                    self ! LocalInterfaceTunnelInfoFinal(caller, data)
                }
            })
        }
    }

    /* Deep nesting of function literals in getLocalInterfaceTunnelInfo
     * results in the class file "DatapathController$$anonfun$org$midonet$midolman$DatapathController$$getLocalInterfaceTunnelInfo$3$$anonfun$apply$14$$anonfun$apply$15$$anonfun$apply$16.class"
     * which is too long for ecryptfs, so alias it to "gliti" to save some
     * room.
     */
    private val getLocalInterfaceTunnelInfo:
        (ActorRef, JList[InterfaceDescription]) => Unit = gliti _

    private def gliti(caller: ActorRef,
                      interfaces: JList[InterfaceDescription]) {
        // First we would populate the data structure with tunnel info
        // on all local interfaces
        var addrTunnelMapping: mutable.MultiMap[Int, TunnelZone.Type] =
            new mutable.HashMap[Int, mutable.Set[TunnelZone.Type]] with
                mutable.MultiMap[Int, TunnelZone.Type]
        // This next variable is the structure for return message
        var retInterfaceTunnelMap: mutable.MultiMap[InterfaceDescription, TunnelZone.Type] =
            new mutable.HashMap[InterfaceDescription, mutable.Set[TunnelZone.Type]] with
                mutable.MultiMap[InterfaceDescription, TunnelZone.Type]
        for ((zoneId, zoneConfig) <- host.zones) {
            if (zoneConfig.isInstanceOf[GreTunnelZoneHost]) {
                addrTunnelMapping.addBinding(zoneConfig.getIp.addressAsInt,
                                             TunnelZone.Type.Gre)
            }
            if (zoneConfig.isInstanceOf[CapwapTunnelZoneHost]) {
                addrTunnelMapping.addBinding(zoneConfig.getIp.addressAsInt,
                                             TunnelZone.Type.Capwap)
            }
            if (zoneConfig.isInstanceOf[IpsecTunnelZoneHost]) {
                addrTunnelMapping.addBinding(zoneConfig.getIp.addressAsInt,
                                             TunnelZone.Type.Ipsec)
            }
        }

        if (addrTunnelMapping.isEmpty == false) {
            var ipAddr : Int = 0
            log.debug("Host has some tunnel zone(s) configured")
            for (interface <- interfaces.asScala) {
                for (inetAddress <- interface.getInetAddresses().asScala) {
                    // IPv6 alert: this assumes only IPv4
                    if (inetAddress.getAddress().length == 4) {
                        ipAddr = ByteBuffer.wrap(inetAddress.getAddress()).getInt
                        addrTunnelMapping.get(ipAddr) foreach { tunnelTypes =>
                            for (tunnelType <- tunnelTypes) {
                                retInterfaceTunnelMap.addBinding(interface, tunnelType)
                            }
                        }
                    }
                }
            }
        }
        caller ! retInterfaceTunnelMap
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
                    self ! _SetLocalDatapathPorts(datapath, ports.asScala.toSet)
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

    private case class _SetLocalDatapathPorts(datapath: Datapath, ports: Set[Port[_, _]])
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
        val controller: VirtualPortManager.Controller, val log: LoggingAdapter,
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
    ) {

    private def copy = new VirtualPortManager(controller,
                                                log,
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
             dpPortsWeAdded -= (port.getName)
             _datapathPortRemoved(port.getName)
        }
    }

    def updateInterfaces(interfaces : Collection[InterfaceDescription]) =
        copy._updateInterfaces(interfaces)

    private def _updateInterfaces(interfaces : Collection[InterfaceDescription]) = {
        log.debug("updateInterfaces {}", interfaces)
        val currentInterfaces = mutable.Set[String]()

        for (itf <- interfaces.asScala) {
            currentInterfaces.add(itf.getName)
            val isUp = itf.hasLink && itf.isUp

            interfaceToStatus.get(itf.getName) match {
                case None =>
                    // This is a new interface
                    interfaceToStatus += ((itf.getName, isUp))
                    // Is there a vport binding for this interface?
                    if (interfaceToVport.contains(itf.getName)) {
                        interfaceToDpPort.get(itf.getName) match {
                            case None =>
                                // Request that it be added to the datapath.
                                requestDpPortAdd(itf.getName)
                            case Some(port) =>
                                // If the interface is up, then the virtual
                                // port is now active.
                                if (isUp)
                                    controller.setVportStatus(port,
                                        interfaceToVport.get(itf.getName).getOrElse(null),
                                        true)
                        }
                    }
                case Some(wasUp) =>
                    // The NetlinkInterfaceSensor sets the endpoint for all the
                    // ports of the dp to DATAPATH. It the endpoint is not DATAPATH
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
                        interfaceToStatus += ((itf.getName, isUp))
                        if (isUp != wasUp) {
                            interfaceToVport.get(itf.getName) foreach { vportId =>
                                val dpPort = interfaceToDpPort.get(itf.getName)
                                if (dpPort.isDefined)
                                    controller.setVportStatus(dpPort.get,
                                                              vportId, isUp)
                            }
                        }
                    }
            }
        }

        // Now deal with any interface that has been deleted.
        val deletedInterfaces = interfaceToStatus -- currentInterfaces
        deletedInterfaces.keys.foreach {
            name =>
                interfaceToStatus -= name
                // we don't have to remove the binding, the interface was deleted
                // but the binding is still valid
                interfaceToVport.get(name) foreach { vportId =>
                    val dpPort = interfaceToDpPort.get(name)
                    if (dpPort.isDefined)
                        controller.setVportStatus(
                            dpPort.get, vportId, false)
                }
                interfaceToDpPort.get(name) match {
                    case None => // do nothing
                    case Some(port) =>
                        // if the interface is not present the port
                        // has already been removed, we just need to
                        // notify the dp
                        notifyPortRemoval(port)
                }
        }
        this
    }

    def updateVPortInterfaceBindings(vportToInterface: Map[UUID, String]) =
        copy._updateVPortInterfaceBindings(vportToInterface)

    // We do not support remapping a vportId to a different
    // interface or vice versa. We assume each vportId and
    // interface will occur in at most one binding.
    private def _updateVPortInterfaceBindings(
            vportToInterface: Map[UUID, String]) = {
        log.debug("updateVPortInterfaceBindings {}", vportToInterface)
        // First, deal with new bindings.
        vportToInterface.foreach {
            case (vportId: UUID, itfName: String) =>
                if (!interfaceToVport.contains(itfName)) {
                    interfaceToVport += (itfName, vportId)
                    // This is a new binding. Does the interface exist?
                    if (interfaceToStatus.contains(itfName)) {
                        // Has the interface been added to the datapath?
                        interfaceToDpPort.get(itfName) match {
                            case None =>
                                requestDpPortAdd(itfName)
                            case Some(dpPort) =>
                                // The vport is active if the interface is up.
                                if (interfaceToStatus(itfName))
                                    controller.setVportStatus(dpPort, vportId, true)
                        }
                    }
                }
        }
        // Now, deal with deleted bindings.
        for ((ifname, vport) <- interfaceToVport) {
            if (!vportToInterface.contains(vport)) {
                interfaceToVport -= ifname
                // This binding was removed. Was there a datapath port for it?
                interfaceToDpPort.get(ifname) match {
                    case None =>
                        /* if no port were added, it could still be marked as
                         * in progress => we untrack it as such if it was */
                        if (dpPortsInProgress.contains(ifname))
                            dpPortsInProgress -= ifname
                    case Some(port) =>
                        requestDpPortRemove(port)
                        // If the port was up, the vport just became inactive.
                        if (interfaceToStatus(ifname))
                            controller.setVportStatus(port, vport, false)
                }
            }
        }
        this
    }

    def datapathPortForget(port: Port[_,_]) =
        copy._datapathPortForget(port)

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
                        controller.setVportStatus(port, vportId, true)
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
                // TODO(pino): error. We didn't know about this port at all.
            case Some(port) =>
                interfaceToDpPort -= itfName
                dpPortNumToInterface -= port.getPortNo

                // Is there a binding for this interface name?
                interfaceToVport.get(itfName) match {
                    case None => // Do nothing. No binding.
                    case Some(vportId) =>
                        // If we didn't request this removal, and the interface
                        // is up, then notify that the vport is now down.
                        // Also, if the interface exists,
                        // request that the dpPort be re-added.
                        interfaceToStatus.get(itfName) match {
                            case None => // Do nothing. Status not known.
                            case Some(isUp) =>
                                requestDpPortAdd(itfName)
                                if(isUp && !requestedByMe)
                                    controller.setVportStatus(port, vportId, false)
                        }
                }

        }
        this
    }

}

class DatapathStateManager(@scala.volatile private var _vportMgr: VirtualPortManager)
    extends DatapathState {

    @scala.volatile private var _version: Long = 0

    private def versionUp[T](sideEffect: => T): T = {
        _version += 1
        sideEffect
    }

    private val _localTunnelPorts: mutable.Set[JInteger] =
        Collections.newSetFromMap[JInteger](new ConcHashMap[JInteger,JBoolean]()).asScala

    // peerHostId -> { ZoneID -> Port[_,_] }
    private val _peerToTunnels: ConcMap[UUID, ConcMap[UUID, Port[_,_]]] =
        new ConcHashMap[UUID, ConcMap[UUID, Port[_,_]]]().asScala

    override def version = _version

    def updateInterfaces(itfs: Collection[InterfaceDescription]) =
        versionUp { _vportMgr = _vportMgr.updateInterfaces(itfs) }

    def updateVPortInterfaceBindings(bindings: Map[UUID, String]) =
        versionUp { _vportMgr = _vportMgr.updateVPortInterfaceBindings(bindings) }

    def dpPortAdded(p: Port[_,_]) =
        versionUp { _vportMgr = _vportMgr.datapathPortAdded(p) }

    def dpPortRemoved(p: Port[_,_]) =
        versionUp { _vportMgr = _vportMgr.datapathPortRemoved(p.getName) }

    def dpPortForget(p: Port[_,_]) =
        versionUp { _vportMgr = _vportMgr.datapathPortForget(p) }

    def addLocalTunnelPort(portNo: JInteger) =
        versionUp { _localTunnelPorts.add(portNo) }

    def removeLocalTunnelPort(portNo: JInteger): Boolean =
        versionUp { _localTunnelPorts.remove(portNo) }

    override def localTunnelPorts: ROSet[JInteger] = _localTunnelPorts

    def addPeerTunnel(peer: UUID, zone: UUID, port: Port[_,_]) = versionUp {
        _peerToTunnels.get(peer) match {
            case Some(tunnels) => tunnels.put(zone, port)
            case None =>
                val mapping = new ConcHashMap[UUID, Port[_,_]]()
                mapping.put(zone, port)
                _peerToTunnels.put(peer, mapping.asScala)
        }
    }

    def removePeerTunnel(peer: UUID, zone: UUID): Boolean = versionUp {
        _peerToTunnels.get(peer) match {
            case Some(tunnels) =>
                val deleted = tunnels.remove(zone)
                if (tunnels.size == 0)
                    _peerToTunnels.remove(peer)
                deleted match {
                    case None => false
                    case Some(a) => true
                }
            case None =>
                false
        }
    }

    override def peerToTunnels: ROMap[UUID, ROMap[UUID, Port[_,_]]] = _peerToTunnels

    def getInterfaceForVport(vportId: UUID): Option[String] =
        _vportMgr.interfaceToVport.inverse.get(vportId)

    def getDpPortForInterface(itfName: String): Option[Port[_,_]] =
        _vportMgr.interfaceToDpPort.get(itfName)

    def getDpPortNumberForVport(vportId: UUID): Option[JInteger] =
        _vportMgr.interfaceToVport.inverse.get(vportId) flatMap { itfName =>
            _vportMgr.interfaceToDpPort.get(itfName) map { _.getPortNo }
        }

    def getVportForDpPortNumber(portNum: JInteger): Option[UUID] =
        _vportMgr
            .dpPortNumToInterface.get(portNum)
            .flatMap { _vportMgr.interfaceToVport.get(_) }

    def getDpPortName(num: JInteger): Option[String] =
        _vportMgr.dpPortNumToInterface.get(num)

}
