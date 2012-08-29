// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import collection.mutable
import collection.{Set => ROSet}  // read-only view
import util.continuations.cps
import java.util.UUID

import akka.actor.ActorRef
import akka.dispatch.{Future, ExecutionContext, Promise}
import akka.dispatch.Future._
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._

import com.google.inject.Inject
import org.slf4j.LoggerFactory

import com.midokura.midolman.topology._
import com.midokura.packets.Ethernet
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.util.functors.Callback0
import com.midokura.midolman.FlowController.{AddWildcardFlow, Drop, SendPacket}
import com.midokura.midolman.{FlowController, DatapathController}
import com.midokura.midolman.services.MidolmanActorsService
import com.midokura.sdn.dp.{FlowMatch, Packet}
import com.midokura.midolman.datapath.FlowActionVrnPortOutput


object Coordinator {
    trait Action

    case class DropAction() extends Action
    // NotIPv4Action implies a DROP flow. However, it differs from DropAction
    // in that the installed flow match can have all fields >L2 wildcarded.
    // TODO(pino): make the installed flow computation smarter so that it
    // TODO:       wildcards any field that wasn't used by the simulation. Then
    // TODO:       remove NotIPv4Action
    case class NotIPv4Action() extends Action
    case class ConsumedAction() extends Action
    case class ForwardAction(outPort: UUID,
                             outMatch: WildcardMatch) extends Action

    class PacketContext {
        // PacketContext starts unfrozen, in which mode it can have callbacks
        // and tags added.  Freezing it switches it from write-only to
        // read-only.
        private var frozen = false
        def isFrozen() = frozen

        // This set will store the callback to call when this flow is removed
        private val flowRemovedCallbacks = mutable.Set[Callback0]()
        def addFlowRemovedCallback(cb: Callback0): Unit = this.synchronized {
            if (frozen)
                throw new IllegalArgumentException(
                                "Adding callback to frozen PacketContext")
            else
                flowRemovedCallbacks.add(cb)
        }
        def getFlowRemovedCallbacks(): ROSet[Callback0] = {
            if (!frozen)
                throw new IllegalArgumentException(
                        "Reading callbacks from unfrozen PacketContext")

            flowRemovedCallbacks
        }
        // This Set will store the tags by which the flow should be indexed
        // The index can be used to remove flows associated with the given tag
        private val flowTags = mutable.Set[Any]()
        def addFlowTag(tag: Any): Unit = this.synchronized {
            if (frozen)
                throw new IllegalArgumentException(
                                "Adding tag to frozen PacketContext")
            else
                flowTags.add(tag)
        }
        def getFlowTags(): ROSet[_] = {
            if (!frozen)
                throw new IllegalArgumentException(
                        "Reading tags from unfrozen PacketContext")

            flowTags
        }

        def freeze(): Unit = this.synchronized {
            frozen = true
        }
    }

    trait Device {
        /**
         * Process a packet described by the given match object. Note that the
         * Ethernet packet is the one originally ingressed the virtual network
         * - it does not reflect the changes made by other devices' handling of
         * the packet (whereas the match object does).
         *
         * @param pktMatch The wildcard match that describes the packet's
         * fields at the time it ingresses the device. This match contains the
         * UUID of the ingress port; it can be accessed via getInputPortUUID.
         * @param packet The original packet that ingressed the virtual network,
         * which may be different from the packet that actually arrives at this
         * device.
         * @param pktContext The context for the simulation of this packet's
         * traversal of the virtual network. Use the context to subscribe
         * for notifications on the removal of any resulting flows, or to tag
         * any resulting flows for indexing.
         * @param ec the Coordinator actor's execution context.
         * @return An instance of Action that reflects what the device would do
         * after handling this packet (e.g. drop it, consume it, forward it).
         */
        def process(pktMatch: WildcardMatch,
                    packet: Ethernet,
                    pktContext: PacketContext,
                    ec: ExecutionContext): Future[Action]
    }
}

class Coordinator {
    import Coordinator._

    @Inject
    var actors: MidolmanActorsService = _

    private val log = LoggerFactory.getLogger(classOf[Coordinator])

    /**
     * Simulate a single packet moving through the virtual topology. A packet
     * begins its journey through the virtual topology in one of these ways:
     * 1) it ingresses an exterior port of a virtual device (in which case the
     * packet arrives via the datapath switch from an entity outside the
     * virtual topology).
     * 2) it egresses an interior port of a virtual device (in which case the
     * packet was generated by that virtual device).
     *
     * In case 1, the match object for the packet was computed by the
     * FlowController and must contain an inPortID. If a wildcard flow is
     * eventually installed in the FlowController, the match will be a subset
     * of the match originally provided to the simulation. Note that in this
     * case the generatedPacketEgressPort argument should be null and will be
     * ignored.
     *
     * In case 2, the match object for the packet was computed by the Device
     * that emitted the packet and must not contain an inPortID. If the packet
     * is not dropped, it will eventually result in a packet being emitted
     * from one or more of the datapath ports. However, a flow is never
     * installed as a result of such a simulation. Note that in this case the
     * generatedPacketEgressPort argument must not be null.
     *
     * When this method completes, it may send a message to the Datapath
     * Controller to install a flow or send a packet.
     *
     * @param origMatch
     * @param packet
     * @param generatedPacketEgressPort Only used if this packet was generated
     *                                  by a virtual device. It's the ID of
     *                                  the port via which the packet
     *                                  egresses the device.
     */
    def simulate(origMatch: WildcardMatch,
                 origFlowMatch: FlowMatch,
                 packet: Array[Byte],
                 generatedPacketEgressPort: UUID)
                (implicit ec: ExecutionContext): Unit = {
        val datapathController =
            actors.system.actorFor("/user/%s" format DatapathController.Name)
        val flowController =
            actors.system.actorFor("/user/%s" format FlowController.Name)
        val virtualTopologyManager =
            actors.system.actorFor("/user/%s" format VirtualTopologyActor.Name)
        // TODO(pino): if any topology object cannot be found, log an error.

        val origEthernetPkt = Ethernet.deserialize(packet)
        var currentIngressPortFuture: Future[Port[_]] = null
        var currentMatch = origMatch.clone
        val isInternallyGenerated = generatedPacketEgressPort != null

        if (!isInternallyGenerated) {
            origMatch.getInputPortUUID match {
                case null =>
                    throw new IllegalArgumentException(
                        "Coordinator cannot simulate a flow that NEITHER " +
                        "egressed a virtual device's interior port NOR " +
                        "ingressed a virtual device's exterior port. Match: " +
                        "%s; Packet: %s".format(
                            origMatch.toString, origEthernetPkt.toString))
                case _ =>
                    currentIngressPortFuture = virtualTopologyManager.ask(
                        PortRequest(origMatch.getInputPortUUID, false)
                    )(Timeout(1 second)).mapTo[Port[_]]
            }
        } else if (origMatch.getInputPortUUID != null) {
            throw new IllegalArgumentException(
                        "Coordinator cannot simulate a flow that BOTH " +
                        "egressed a virtual device's interior port AND " +
                        "ingressed a virtual device's exterior port. Match: " +
                        "%s; Packet: %s".format(
                            origMatch.toString, origEthernetPkt.toString))
        } else {
            // it IS a generated packet
            // TODO(pino): apply the port's output filter
            val egressPortFuture = virtualTopologyManager.ask(
                PortRequest(generatedPacketEgressPort, false)
            )(Timeout(1 second)).mapTo[Port[_]]
            currentIngressPortFuture = egressPortFuture flatMap {
                egressPort: Port[_] => egressPort match {
                    case _: ExteriorPort[_] =>
                        val pkt = new Packet().setData(packet).addAction(
                                      new FlowActionVrnPortOutput(
                                          generatedPacketEgressPort))
                        // TODO(pino): replace null with actions?
                        datapathController.tell(SendPacket(pkt.getData, null))
                        // All done!
                        Promise.successful(null)
                    case interiorPort: InteriorPort[_] =>
                        virtualTopologyManager.ask(
                            PortRequest(interiorPort.peerID, false)
                        )(Timeout(1 second)).mapTo[Port[_]]
                    case port =>
                        log.error("Port {} neither interior nor exterior port",
                                  port)
                        Promise.successful(null)
                }
            }
        }

        if (currentIngressPortFuture == null)
            return

        // Used to detect loops.
        val traversedFEs = mutable.Map[UUID, Int]()

        // Used for connection tracking
        val connectionTracked = false  //XXX
        val forwardFlow = false  //XXX
        // TODO(pino): val connectionCache

        val pktContext = new PacketContext {}

        flow {
            // depth of devices traversed in the simulation
            var depth: Int = 0
            var currentIngressPort = currentIngressPortFuture.apply
            val ingressDeviceID: UUID = currentIngressPort.deviceID

            while (currentIngressPort != null) {
                // TODO(pino): check for too long loop
                // TODO(pino): the port's input filter.
                val currentDevice = deviceOfPort(currentIngressPort,
                                                 virtualTopologyManager)
                val action = currentDevice().process(
                    currentMatch, origEthernetPkt, pktContext, ec).apply

                if (!action.isInstanceOf[ForwardAction]) {
                    currentIngressPort == null

                    handleNonForwardAction(action, isInternallyGenerated,
                                           origMatch, datapathController,
                                           flowController)
                    (): Unit @cps[Future[Any]]
                } else {
                    // action is a ForwardAction
                    val outPortID = action.asInstanceOf[ForwardAction].outPort
                    val outMatch = action.asInstanceOf[ForwardAction].outMatch

                    // TODO(pino): apply the port's output filter
                    virtualTopologyManager.ask(
                        PortRequest(outPortID, false)
                    )(Timeout(1 second)).mapTo[Port[_]].apply match {
                        case _: ExteriorPort[_] =>
                            // TODO(pino): Compute actions from matches' diff.
                            val pkt = new Packet().setData(packet).addAction(
                                          new FlowActionVrnPortOutput(
                                              generatedPacketEgressPort))
                            if (isInternallyGenerated) {
                                datapathController.tell(
                                    SendPacket(/*XXX*/null, null))
                            } else {
                                datapathController.tell(
                                    AddWildcardFlow(
                                        /*XXX*/ null, null, null, null))
                            }
                            currentIngressPort = null
                        case interiorPort: InteriorPort[_] =>
                            val peerID = interiorPort.peerID
                            currentIngressPort = virtualTopologyManager.ask(
                                PortRequest(peerID, false)
                            )(Timeout(1 second)).mapTo[Port[_]].apply
                            currentMatch = outMatch
                        case port =>
                            throw new RuntimeException(("Port %s neither " +
                                "interior nor exterior port") format
                                    port.id.toString)
                    } // end 'port match'
                } // end if action.isInstanceOf
            } // end while loop
        }(ec) // end flow block
    } // end simulate method

    private def deviceOfPort(port: Port[_], virtualTopologyManager: ActorRef)
                : Future[Device] = {
        port match {
            case _: BridgePort[_] =>
                virtualTopologyManager.ask(
                    BridgeRequest(port.deviceID, false)
                )(Timeout(1 second)).mapTo[Bridge]
            case _: RouterPort[_] =>
                virtualTopologyManager.ask(
                    RouterRequest(port.deviceID, false)
                )(Timeout(1 second)).mapTo[Router]
            case _ =>
                throw new RuntimeException(
                    "Ingress port %s neither BridgePort nor RouterPort"
                             format port.id.toString)
        }
    }

    private def handleNonForwardAction(action: Action,
                                       isInternallyGenerated: Boolean,
                                       origMatch: WildcardMatch,
                                       datapathController: ActorRef,
                                       flowController: ActorRef) {
        // An internally generated packet which wasn't emitted is
        // invisible to the datapath.
        if (isInternallyGenerated)
            return

        action match {
            case _: ConsumedAction =>
                // XXX(pino): drop the SDN packet
                flowController.tell(Drop(null))
            case _: DropAction =>
                datapathController.tell(AddWildcardFlow(
                    null /*XXX*/, null /*XXX*/, null /*XXX*/, null /*XXX*/
                ))
            case _: NotIPv4Action =>
                val notIPv4Match =
                    (new WildcardMatch()
                        .setInputPortUUID(origMatch.getInputPortUUID)
                        .setEthernetSource(origMatch.getEthernetSource)
                        .setEthernetDestination(
                                origMatch.getEthernetDestination)
                        .setEtherType(origMatch.getEtherType))
                datapathController.tell(AddWildcardFlow(
                    /* XXX */ null, null, null, null))
        }
    }

} // end Coordinator class
