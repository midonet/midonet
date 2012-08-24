/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import akka.actor.{ActorRef, Actor}
import com.midokura.sdn.dp._
import com.midokura.sdn.dp.{Flow => KernelFlow}
import collection.JavaConversions._
import datapath.ErrorHandlingCallback
import flows.{FlowKeyInPort, FlowActions, FlowKeys, FlowAction}
import ports._
import datapath.{FlowActionVrnPortOutput, FlowKeyVrnPort}
import topology.{VirtualTopologyActor, VirtualToPhysicalMapper}
import com.midokura.netlink.protos.OvsDatapathConnection
import com.google.inject.Inject
import akka.event.Logging
import com.midokura.netlink.exceptions.NetlinkException
import collection.mutable
import akka.util.duration._
import com.midokura.netlink.exceptions.NetlinkException.ErrorCode
import java.util.UUID
import java.lang
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.util.functors.Callback1

/**
 * Holder object that keeps the external message definitions
 */
object PortOperation extends Enumeration {
    val Create, Delete = Value
}

sealed trait PortOp[P <: Port[_ <: PortOptions, P]] {
    val port: P
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
    val op: PortOperation.Value
    val timeout: Boolean
    val error: NetlinkException
}

object DatapathController {

    val Name = "DatapathController"

    /**
     * This will make the Datapath Controller to start the local state
     * initialization process.
     */
    case class Initialize()

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
     */
    case class CreatePortInternal(port: InternalPort)
        extends CreatePortOp[InternalPort]

    /**
     * Will trigger an internal port delete operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.PortInternalOpReply]]
     * message when the operation is completed.
     *
     * @param port the port information
     */
    case class DeletePortInternal(port: InternalPort)
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
     */
    case class PortInternalOpReply(port: InternalPort, op: PortOperation.Value,
                                   timeout: Boolean, error: NetlinkException)
        extends PortOpReply[InternalPort]

    /**
     * Will trigger an netdev port creation operation. The sender will
     * receive an `PortNetdevOpReply` message in return.
     *
     * @param port the port information
     */
    case class CreatePortNetdev(port: NetDevPort)
        extends CreatePortOp[NetDevPort]

    /**
     * Will trigger an netdev port deletion operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.PortNetdevOpReply]]
     * message in return.
     *
     * @param port the port information
     */
    case class DeletePortNetdev(port: NetDevPort)
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
     */
    case class PortNetdevOpReply(port: NetDevPort, op: PortOperation.Value,
                                 timeout: Boolean, error: NetlinkException)
        extends PortOpReply[NetDevPort]

    /**
     * Will trigger an `patch` tunnel creation operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelPatchOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     */
    case class CreateTunnelPatch(port: PatchTunnelPort)
        extends CreatePortOp[PatchTunnelPort]

    /**
     * Will trigger an `patch` tunnel deletion operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelPatchOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     */
    case class DeleteTunnelPatch(port: PatchTunnelPort)
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
     */
    case class TunnelPatchOpReply(port: PatchTunnelPort, op: PortOperation.Value,
                                  timeout: Boolean, error: NetlinkException)
        extends PortOpReply[PatchTunnelPort]

    /**
     * Will trigger an `gre` tunnel creation operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelGreOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     */
    case class CreateTunnelGre(port: GreTunnelPort)
        extends CreatePortOp[GreTunnelPort]

    /**
     * Will trigger an `gre` tunnel deletion operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelGreOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     */
    case class DeleteTunnelGre(port: GreTunnelPort)
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
     */
    case class TunnelGreOpReply(port: GreTunnelPort, op: PortOperation.Value,
                                timeout: Boolean, error: NetlinkException)
        extends PortOpReply[GreTunnelPort]

    /**
     * Will trigger an `capwap` tunnel creation operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelCapwapOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     */
    case class CreateTunnelCapwap(port: CapWapTunnelPort)
        extends CreatePortOp[CapWapTunnelPort]

    /**
     * Will trigger an `capwap` tunnel deletion operation. The sender will
     * receive an [[com.midokura.midolman.DatapathController.TunnelCapwapOpReply]]
     * message in return.
     *
     * @param port the tunnel port information
     */
    case class DeleteTunnelCapwap(port: CapWapTunnelPort)
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
     */
    case class TunnelCapwapOpReply(port: CapWapTunnelPort, op: PortOperation.Value,
                                   timeout: Boolean, error: NetlinkException)
        extends PortOpReply[CapWapTunnelPort]

    case class InstallFlow(flow: KernelFlow)

    case class DeleteFlow(flow: KernelFlow)

    case class SendPacket(packet: Packet)

    case class PacketIn(packet:Packet, wMatch:WildcardMatch)
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
class DatapathController() extends Actor {

    import DatapathController._
    import VirtualToPhysicalMapper._
    import context._

    val log = Logging(system, this)

    @Inject
    val datapathConnection: OvsDatapathConnection = null

    val hostId = UUID.fromString("067e6162-3b6f-4ae2-a171-2470b63dff00")

    private def flowController(): ActorRef =  {
        actorFor("/user/%s" format FlowController.Name)
    }

    private def virtualTopology(): ActorRef =  {
        actorFor("/user/%s" format VirtualTopologyActor.Name)
    }

    private def virtualToPhysicalMapper(): ActorRef =  {
        actorFor("/user/%s" format VirtualToPhysicalMapper.Name)
    }

    private def simulationController(): ActorRef =  {
        actorFor("/user/%s" format SimulationController.Name)
    }

    var datapath: Datapath = null

    val localToVifPorts: mutable.Map[Short, UUID] = mutable.Map()
    val vifPorts: mutable.Map[UUID, String] = mutable.Map()

    // the list of local ports
    val localPorts: mutable.Map[String, Port[_, _]] = mutable.Map()
    val knownPortsByName: mutable.Set[String] = mutable.Set()

    var pendingUpdateCount = 0

    var initializer: ActorRef = null

    protected def receive = null

    override def preStart() {
        super.preStart()
        context.become(DatapathInitializationActor)
    }

    val DatapathInitializationActor: Receive = {

        /**
         * Initialization request message
         */
        case Initialize() =>
            initializer = sender
            log.info("Initialize from: " + sender)
            virtualToPhysicalMapper ! LocalDatapathRequest(hostId)

        /**
         * Initialization complete (sent by self) and we forward the reply to
         * the actual guy that requested initialization.
         */
        case m: InitializationComplete if (sender == self) =>
            log.info("Initialization complete. Starting to act as a controller.")
            become(DatapathControllerActor)
            flowController ! DatapathController.DatapathReady(datapath)
            initializer forward m

        /**
         * Handle replies for local state queries.
         */
        case LocalDatapathReply(wantedDatapath) =>
            readDatapathInformation(wantedDatapath)

        case LocalPortsReply(ports) =>
            doDatapathPortsUpdate(ports)

        /**
         * Handle personal create port requests
         */
        case createPortOp: CreatePortOp[Port[_, _]] if (sender == self) =>
            createDatapathPort(sender, createPortOp.port)

        /**
         * Handle personal delete port requests
         */
        case deletePortOp: DeletePortOp[Port[_, _]] if (sender == self) =>
            deleteDatapathPort(sender, deletePortOp.port)

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

    val DatapathControllerActor: Receive = {

        // When we get the initialization message we switch into initialization
        // mode and only respond to some messages.
        // When initialization is completed we will revert back to this Actor
        // loop for general message response
        case m: Initialize =>
            become(DatapathInitializationActor)
            self ! m

        case LocalPortsReply(ports) =>
            doDatapathPortsUpdate(ports)

        case newPortOp: CreatePortOp[Port[_, _]] =>
            createDatapathPort(sender, newPortOp.port)

        case delPortOp: DeletePortOp[Port[_, _]] =>
            deleteDatapathPort(sender, delPortOp.port)

        case opReply: PortOpReply[Port[_, _]] =>
            handlePortOperationReply(opReply)

        case AddWildcardFlow(flow, packet, callbacks, tags) =>
            handleAddWildcardFlow(flow, packet, callbacks, tags)

        case Messages.Ping(value) =>
            sender ! Messages.Pong(value)

        case PacketIn(packet, wildcard) =>
            handleFlowPacketIn(packet, wildcard)

//        case SendPacket(packet) =>
//            handleSendPacket(packet)
    }

    def handleAddWildcardFlow(flow: WildcardFlow, packet: Option[Packet],
                              callbacks: mutable.Set[Callback1[WildcardMatch]],
                              tags: mutable.Set[AnyRef]) {
        val flowMatch = flow.getMatch

        vifToLocalPortNumber(flowMatch.getInputPortUUID) match {
            case Some(portNo: Short) =>
                flowMatch
                    .setInputPortNumber(portNo)
                    .unsetInputPortUUID()
            case None =>
        }

        if (flow.getActions != null) {
            var translatedActions = List[FlowAction[_]]()
            for (action <- flow.getActions) {
                action match {
                    case a: FlowActionVrnPortOutput =>
                        vifToLocalPortNumber(a.portId) match {
                            case Some(p: Short) =>
                                translatedActions ::= FlowActions.output(p)
                            case _ =>
                                translatedActions ::= action
                        }
                    case _ =>
                        translatedActions ::= action
                }
            }

            flow.setActions(translatedActions.toList)
        }

        // TODO: translate the port groups.

        flowController() ! AddWildcardFlow(flow, packet, callbacks, tags)
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

    def handleFlowPacketIn(packet: Packet, wildcard: WildcardMatch) {
        wildcard.getInputPortNumber match {
            case port:java.lang.Short =>
                wildcard.setInputPortUUID(dpPortToVifId(port))
        }

        simulationController() ! PacketIn(packet, wildcard)
    }

    private def dpPortToVifId(port: Short): UUID = {
        localToVifPorts(port)
    }

    def handleInstallFlow(flow: KernelFlow) {
        flow.setActions(translate(flow.getActions))
        flow.setMatch(translate(flow.getMatch))
        datapathConnection.flowsCreate(datapath, flow, new ErrorHandlingCallback[Flow] {
            def onSuccess(data: Flow) {

            }

            def handleError(ex: NetlinkException, timeout: Boolean) {}
        })
    }

    def handleSendPacket(packet: Packet) {
        packet
            .setMatch(translate(packet.getMatch))
            .setActions(translate(packet.getActions))

        datapathConnection.packetsExecute(datapath, packet, new ErrorHandlingCallback[lang.Boolean] {
            def onSuccess(data: lang.Boolean) {

            }

            def handleError(ex: NetlinkException, timeout: Boolean) {}
        })
    }

    private def translate(flowMatch: FlowMatch): FlowMatch = {
        val translatedFlowMatch = new FlowMatch()

        for (flowKey <- flowMatch.getKeys) {
            flowKey match {
                case vrnPort: FlowKeyVrnPort =>
                    translatedFlowMatch.addKey(FlowKeys.inPort(10))
                case value =>
                    translatedFlowMatch.addKey(value)
            }
        }

        translatedFlowMatch
    }

    private def translate(actions: java.util.List[FlowAction[_]]): java.util.List[FlowAction[_]] = {
        val translatedActions = List[FlowAction[_]]()
        for (action <- actions) {
            action match {
                case vrnPortAction: FlowActionVrnPortOutput =>
                    translatedActions.add(FlowActions.output(10))
                case value =>
                    translatedActions.add(value)
            }
        }

        translatedActions
    }


    def handlePortOperationReply(opReply: PortOpReply[_]) {
        log.info("Port operation reply: {}", opReply)

        pendingUpdateCount -= 1

        opReply.port match {
            case p: Port[_, _] if opReply.error == null && !opReply.timeout =>
                opReply.op match {
                    case PortOperation.Create =>
                        localPorts.put(p.getName, p)
                    case PortOperation.Delete =>
                        localPorts.remove(p.getName)
                }
            case value =>
                log.error("No match {}", value)
        }

        if (pendingUpdateCount == 0)
            self ! InitializationComplete()
    }

    def doDatapathPortsUpdate(ports: Map[UUID, String]) {
        if (pendingUpdateCount != 0) {
            system.scheduler.scheduleOnce(100 millis, self, LocalPortsReply(ports))
            return
        }

        log.info("Migrating local datapath to configuration {}", ports)
        log.info("Current known local ports: {}", localPorts)

        vifPorts.clear()
        // post myself messages to force the creation of missing ports
        val newTaps: mutable.Set[String] = mutable.Set()
        for ((vifId, tapName) <- ports) {
            vifPorts.put(vifId, tapName)
            newTaps.add(tapName)
            if (!localPorts.contains(tapName)) {
                selfPostPortCommand(CreatePortNetdev(Ports.newNetDevPort(tapName)))
            }
        }

        // find ports that need to be removed and post myself messages to
        // remove them
        for ((portName, portData) <- localPorts) {
            log.info("Looking at {} -> {}", portName, portData)
            if (!knownPortsByName.contains(portName) && !newTaps.contains(portName)) {
                portData match {
                    case p: NetDevPort =>
                        selfPostPortCommand(DeletePortNetdev(p))
                    case p: InternalPort if (p.getPortNo != 0) =>
                        selfPostPortCommand(DeletePortInternal(p))
                    case default =>
                        log.error("port type not matched {}", default)
                }
            }
        }

        log.info("Pending updates {}", pendingUpdateCount)
        if (pendingUpdateCount == 0)
            self ! InitializationComplete()
    }

    private def selfPostPortCommand(command: PortOp[_]) {
        pendingUpdateCount += 1
        log.info("Scheduling port command {}", command)
        self ! command
    }

    def createDatapathPort(caller: ActorRef, port: Port[_, _]) {
        log.info("creating port: {} (by request of: {})", port, caller)

        datapathConnection.portsCreate(datapath, port,
            new ErrorHandlingCallback[Port[_, _]] {
                def onSuccess(data: Port[_, _]) {
                    for ((vifId, tapName) <- vifPorts) {
                        if (tapName == data.getName) {
                            log.info("VIF port {} mapped to local port number {}", vifId, data.getPortNo)
                            localToVifPorts.put(data.getPortNo.shortValue(), vifId)
                        }
                    }
                    sendOpReply(caller, data, PortOperation.Create, null, timeout = false)
                }

                def handleError(ex: NetlinkException, timeout: Boolean) {
                    sendOpReply(caller, port, PortOperation.Create, ex, timeout)
                }
            })
    }

    def deleteDatapathPort(caller: ActorRef, port: Port[_, _]) {
        log.info("deleting port: {} (by request of: {})", port, caller)

        datapathConnection.portsDelete(port, datapath, new ErrorHandlingCallback[Port[_, _]] {
            def onSuccess(data: Port[_, _]) {
                sendOpReply(caller, data, PortOperation.Delete, null, timeout = false)
            }

            def handleError(ex: NetlinkException, timeout: Boolean) {
                sendOpReply(caller, port, PortOperation.Delete, ex, timeout = false)
            }
        })
    }

    private def sendOpReply(actor: ActorRef, port: Port[_, _], op: PortOperation.Value,
                            ex: NetlinkException, timeout: Boolean) {
        port match {
            case p: InternalPort =>
                actor ! PortInternalOpReply(p, op, timeout, ex)
            case p: NetDevPort =>
                actor ! PortNetdevOpReply(p, op, timeout, ex)
            case p: PatchTunnelPort =>
                actor ! TunnelPatchOpReply(p, op, timeout, ex)
            case p: GreTunnelPort =>
                actor ! TunnelGreOpReply(p, op, timeout, ex)
            case p: CapWapTunnelPort =>
                actor ! TunnelCapwapOpReply(p, op, timeout, ex)
        }
    }

    private def readDatapathInformation(wantedDatapath: String) {
        log.info("Wanted datapath: {}", wantedDatapath)

        datapathConnection.datapathsGet(wantedDatapath,
            new ErrorHandlingCallback[Datapath] {
                def onSuccess(data: Datapath) {
                    datapath = data
                    queryDatapathPorts()
                }

                def handleError(ex: NetlinkException, timeout: Boolean) {
                    if (timeout) {
                        log.error("Timeout while getting the datapath", timeout)
                        context.system.scheduler.scheduleOnce(100 millis,
                            self, LocalDatapathReply(wantedDatapath))
                        return
                    }

                    if (ex == null)
                        return

                    val errorCode: ErrorCode = ex.getErrorCodeEnum

                    if (errorCode != null &&
                        errorCode == NetlinkException.ErrorCode.ENODEV) {
                        log.info("Datapath is missing. Creating.")
                        datapathConnection.datapathsCreate(wantedDatapath, new ErrorHandlingCallback[Datapath] {
                            def onSuccess(data: Datapath) {
                                datapath = data
                                log.info("Datapath created {}", data)
                                queryDatapathPorts()
                            }

                            def handleError(ex: NetlinkException, timeout: Boolean) {
                                log.error(ex, "Datapath creation failure {}", timeout)
                                context.system.scheduler.scheduleOnce(100 millis,
                                    self, LocalDatapathReply(wantedDatapath))
                            }
                        })
                    }
                }
            }
        )
    }

    private def queryDatapathPorts() {
        log.info("Enumerating ports for datapath: " + datapath)
        datapathConnection.portsEnumerate(datapath,
            new ErrorHandlingCallback[java.util.Set[Port[_, _]]] {
                def onSuccess(ports: java.util.Set[Port[_, _]]) {
                    for (port <- ports) {
                        localPorts.put(port.getName, port)
                    }

                    log.info("Local ports listed {}", ports)
                    virtualToPhysicalMapper ! VirtualToPhysicalMapper.LocalPortsRequest(hostId)
                }

                // WARN: this is ugly. Normally we should configure the message error handling
                // inside the router
                def handleError(ex: NetlinkException, timeout: Boolean) {
                    context.system.scheduler.scheduleOnce(100 millis, new Runnable {
                        def run() {
                            queryDatapathPorts()
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
}
