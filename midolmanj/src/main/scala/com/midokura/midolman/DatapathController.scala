/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import akka.actor.{ActorRef, Actor}
import com.midokura.sdn.dp._
import collection.JavaConversions._
import guice.ComponentInjectorHolder
import ports._
import vrn.{VirtualTopologyActor, VirtualToPhysicalMapper}
import com.midokura.netlink.protos.OvsDatapathConnection
import com.google.inject.Inject
import akka.event.Logging
import com.midokura.netlink.exceptions.NetlinkException
import com.midokura.netlink.{Callback => NetlinkCallback}
import collection.mutable
import akka.util.duration._
import com.midokura.netlink.exceptions.NetlinkException.ErrorCode
import java.util.UUID
import com.midokura.util.functors.Callback.{Result, MultiResult}
import com.midokura.midostore.MidostoreClient

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

    case class InitializationComplete()

    case class CreatePortInternal(port: InternalPort)
        extends CreatePortOp[InternalPort]

    case class CreatePortNetdev(port: NetDevPort)
        extends CreatePortOp[NetDevPort]

    case class CreateTunnelPatch(port: PatchTunnelPort)
        extends CreatePortOp[PatchTunnelPort]

    case class CreateTunnelGre(port: GreTunnelPort)
        extends CreatePortOp[GreTunnelPort]

    case class CreateTunnelCapwap(port: CapWapTunnelPort)
        extends CreatePortOp[CapWapTunnelPort]

    case class DeletePortInternal(port: InternalPort)
        extends DeletePortOp[InternalPort]

    case class DeletePortNetdev(port: NetDevPort)
        extends DeletePortOp[NetDevPort]

    case class DeleteTunnelPatch(port: PatchTunnelPort)
        extends DeletePortOp[PatchTunnelPort]

    case class DeleteTunnelGre(port: GreTunnelPort)
        extends DeletePortOp[GreTunnelPort]

    case class DeleteTunnelCapwap(port: CapWapTunnelPort)
        extends DeletePortOp[CapWapTunnelPort]

    case class PortInternalOpReply(port: InternalPort, op: PortOperation.Value,
                                   timeout: Boolean, error: NetlinkException)
        extends PortOpReply[InternalPort]

    case class PortNetdevOpReply(port: NetDevPort, op: PortOperation.Value,
                                 timeout: Boolean, error: NetlinkException)
        extends PortOpReply[NetDevPort]

    case class TunnelPatchOpReply(port: PatchTunnelPort, op: PortOperation.Value,
                                  timeout: Boolean, error: NetlinkException)
        extends PortOpReply[PatchTunnelPort]

    case class TunnelGreOpReply(port: GreTunnelPort, op: PortOperation.Value,
                                timeout: Boolean, error: NetlinkException)
        extends PortOpReply[GreTunnelPort]

    case class TunnelCapwapOpReply(port: CapWapTunnelPort, op: PortOperation.Value,
                                   timeout: Boolean, error: NetlinkException)
        extends PortOpReply[CapWapTunnelPort]

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
class DatapathController(hostIdentifier: String) extends Actor {

    import DatapathController._
    import VirtualToPhysicalMapper._
    import context._

    val log = Logging(context.system, this)

    @Inject
    val datapathConnection: OvsDatapathConnection = null

    var virtualTopology: ActorRef = null
    var virtualToPhysicalMapper: ActorRef = null

    var datapath: Datapath = null

    var vifTolocalPorts: mutable.Map[UUID, (Short, String)] = mutable.Map()
    var localToVifPorts: mutable.Map[Short, (UUID, String)] = mutable.Map()
    var localPorts: mutable.Map[String, Port[_, _]] = mutable.Map()
    var knownPortsByName: mutable.Set[String] = mutable.Set()

    override def preStart() {
        super.preStart()
        ComponentInjectorHolder.inject(this)

        virtualToPhysicalMapper = context.actorFor("/user/%s" format VirtualToPhysicalMapper.Name)
        virtualTopology = context.actorFor("/user/%s" format VirtualTopologyActor.Name)

        context.become(DatapathInitializationActor)
    }

    var pendingUpdateCount = 0

    var initializer:ActorRef = null

    protected def receive = null

    val DatapathInitializationActor: Receive = {

        /**
         * External message reaction
         */
        case Initialize() =>
            initializer = sender
            virtualToPhysicalMapper ! LocalDatapathRequest(hostIdentifier)

        case m: InitializationComplete =>
            log.info("Initialization complete. Requester was {}", initializer)
            become(DatapathControllerActor)
            initializer forward m

        /**
         * Reply messages reaction
         */
        case LocalDatapathReply(wantedDatapath) =>
            readDatapathInformation(wantedDatapath)

        case LocalPortsReply(ports) =>
            doDatapathPortsUpdate(ports)

        case newPortOp: CreatePortOp[Port[_, _]] =>
            if (sender == self)
                doCreateDatapathPort(sender, newPortOp.port)

        case delPortOp: DeletePortOp[Port[_, _]] =>
            if (sender == self)
                doDeleteDatapathPort(sender, delPortOp.port)

        case opReply: PortOpReply[Port[_, _]] =>
            if (sender == self)
                doHandlePortOperationReply(opReply)

        case value =>
            log.info("(behaving as InitializationActor). Not handling message: " + value)
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
            doDeleteDatapathPort(sender, newPortOp.port)

        case delPortOp: DeletePortOp[Port[_, _]] =>
            doDeleteDatapathPort(sender, delPortOp.port)

        /**
         * internally posted replies reactions
         */
        case _PacketIn(packet) =>
            doPacketIn(packet)
    }

    def doHandlePortOperationReply(opReply: PortOpReply[_]) {
        log.info("Port operation reply: {}", opReply)

        pendingUpdateCount -= 1

        if (pendingUpdateCount == 0)
            self ! InitializationComplete()
    }

    def doDatapathPortsUpdate(ports: Map[UUID, String]) {
        log.info("localPorts: {}", localPorts)
        log.info("desiredPorts: {}", ports)

        // post myself messages to force the creation of missing ports
        for ((vifId, tapName) <- ports) {
            if (!localPorts.contains(tapName)) {
                self ! CreatePortNetdev(Ports.newNetDevPort(tapName))
            }
        }

        // find ports that needs to be removes and post myself messages to
        // remove them
        for ((portName, portData) <- localPorts) {
            if (!knownPortsByName.contains(portName) && !localPorts.contains(portName)) {
                portData match {
                    case p: NetDevPort =>
                        self ! DeletePortNetdev(p)
                    case p: InternalPort if (p.getPortNo == 0) =>
                    //
                    case default =>
                        log.error("port type not matched {}", default)
                }
            }
        }

        log.info("Pending updates {}", pendingUpdateCount)
        if ( pendingUpdateCount == 0 )
            self ! InitializationComplete()
    }

    def doCreateDatapathPort(caller: ActorRef, port: Port[_, _]) {
        log.info("creating port: {} (by request of: {})", port, caller)

        datapathConnection.portsCreate(datapath, port, new ErrorHandlingCallback[Port[_, _]] {
            def onSuccess(data: Port[_, _]) {
                sendOpReply(caller, port, PortOperation.Create, null, timeout = false)
            }

            def handleError(ex: NetlinkException, timeout: Boolean) {
                sendOpReply(caller, port, PortOperation.Create, ex, timeout)
            }
        })
    }

    def doDeleteDatapathPort(caller: ActorRef, port: Port[_, _]) {
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

    def sendOpReply(actor: ActorRef, port: Port[_, _], op: PortOperation.Value,
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

    private def doPacketIn(packet: Packet) {

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

                    virtualToPhysicalMapper ! VirtualToPhysicalMapper.LocalPortsRequest(hostIdentifier)
                }

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

/*
    def doDatapathCreatePorts(datapath: Datapath, retry: Int) {

        log.info("Creating ports on the local datapath")
        val collector: ComposingCallback[Port[_, _], NetlinkException] =
            Callbacks.composeTo(
                new NetlinkMultiCallback[Port[_, _]]() {
                    override def onSuccess(multi: MultiResult[Port[_, _]]) {
                        super.onSuccess(multi)
                        //                        if ( (multi.hasTimeouts || multi.hasExceptions) && retry > 0)
                        //                            self ! _NetlinkCreateDatapathPorts(localState, datapath, retry - 1)
                    }
                })

        //        for ((uuid, (tapName, _)) <- localState.ports) {
        //            log.info("Creating port: " + tapName)
        //            datapathConnection.portsCreate(
        //                datapath, Ports.newNetDevPort(tapName),
        //                collector.createCallback(
        //                    classOf[Callback[Port[_, _]]], "Create port:" + tapName))
        //        }

        collector.enableResultCollection()
    }

*/
    def doDatapathStateUpdate(datapath: Datapath, ports: mutable.Set[Port[_, _]]) {
        log.info("Updating the local datapath state.")
        log.info("Datapath: " + datapath)
        log.info("Ports: " + ports)

        //        if (datapath == null) {
        //            datapathConnection.datapathsCreate(localState.dpName,
        //                new ErrorHandlingCallback[Datapath] {
        //                    def onSuccess(data: Datapath) {
        //                        self ! _NetlinkDatapathPortsStatus(localState, datapath, ports)
        //                    }
        //
        //                    def handleError(ex: NetlinkException, timeout: Boolean) {
        //                        self ! _NetlinkDatapathPortsStatus(localState, datapath, ports)
        //                    }
        //                }
        //            )
        //        }

        // index the existing ports by name
        var indexedPorts: mutable.Map[String, Port[_, _]] = mutable.Map()

        // remove the local port (it can't be deleted anyway
        for (port <- ports) {
            indexedPorts(port.getName) = port
        }

        // remove all the ports that are supposed to be in there
        //        for ((uuid, (tapName, b)) <- localState.ports) {
        //            indexedPorts -= tapName
        //        }

        indexedPorts -= datapath.getName

        log.info("After removing local ports " + indexedPorts)
        // if there are no spurious ports we go to the next phase
        if (indexedPorts.size == 0) {
            //            self ! _NetlinkCreateDatapathPorts(localState, datapath, 3)
            return
        }

        for ((name, port) <- indexedPorts) {
            port match {
                case p: InternalPort =>
                    self ! DeletePortInternal(p)
                case p: NetDevPort =>
                    self ! DeletePortNetdev(p)
                case p: PatchTunnelPort =>
                    self ! DeleteTunnelPatch(p)
                case p: GreTunnelPort =>
                    self ! DeleteTunnelGre(p)
                case p: CapWapTunnelPort =>
                    self ! DeleteTunnelCapwap(p)
            }
        }
    }

    /**
     * Called when the netlink library receives a packet in
     *
     * @param packet the received packet
     */
    private case class _PacketIn(packet: Packet)

    /**
     * Called from the callback listing the datapath ports.
     *
     * @param datapath the datapath data
     * @param ports the set of ports
     */
    private case class _NetlinkDatapathPortsStatus(datapath: Datapath,
                                                   ports: mutable.Set[Port[_, _]])

    private case class _NetlinkCreateDatapathPorts(datapath: Datapath)

    abstract class ErrorHandlingCallback[T] extends NetlinkCallback[T] {

        def onTimeout() {
            handleError(null, timeout = true)
        }

        def onError(e: NetlinkException) {
            handleError(e, timeout = false)
        }

        def handleError(ex: NetlinkException, timeout: Boolean)
    }

    class NetlinkMultiCallback[T] extends NetlinkCallback[MultiResult[T]] {
        def onSuccess(multi: MultiResult[T]) {
            if (multi.hasTimeouts || multi.hasExceptions) {
                for (result <- multi) {
                    if (result.timeout())
                        onTimeout(result)
                    else if (result.exception() != null)
                        onError(result)
                }
            }
        }

        final def onTimeout() {}

        def onTimeout(result: Result[T]) {
            log.error("Operation \"{}\" timed out.", result.operation)
        }

        final def onError(e: NetlinkException) {}

        def onError(result: Result[T]) {
            log.error(result.exception(), "Operation \"{}\" failed.", result.operation)
        }
    }

}


