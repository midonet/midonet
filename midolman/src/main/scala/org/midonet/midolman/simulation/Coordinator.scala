/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID
import scala.collection.JavaConversions._

import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext

import org.midonet.cluster.client._
import org.midonet.midolman.{NotYet, Ready, Urgent, PacketsEntryPoint}
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.Icmp.IPv4Icmp._
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.odp.flows._
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPortSet
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}

object Coordinator {

    sealed trait Action

    // ErrorDropAction is used to signal that the Drop is being requested
    // because of an error, not because the virtual topology justifies it.
    // The resulting Drop rule may be temporary to allow retrying.
    case object ErrorDropAction extends Action

    sealed trait AbstractDropAction extends Action {
        val temporary: Boolean
    }

    case object DropAction extends AbstractDropAction {
        override val temporary = false
    }

    case object TemporaryDropAction extends AbstractDropAction {
        override val temporary = true
    }

    // NotIPv4Action implies a DROP flow. However, it differs from DropAction
    // in that the installed flow match can have all fields >L2 wildcarded.
    // TODO(pino): make the installed flow computation smarter so that it
    // TODO:       wildcards any field that wasn't used by the simulation. Then
    // TODO:       remove NotIPv4Action
    case object NotIPv4Action extends Action
    case object ConsumedAction extends Action

    sealed trait ForwardAction extends Action
    case class ToPortAction(outPort: UUID) extends ForwardAction
    case class ToPortSetAction(portSetID: UUID) extends ForwardAction
    case class DoFlowAction(action: FlowAction) extends Action

    // This action is used when one simulation has to return N forward actions
    // A good example is when a bridge that has a vlan id set receives a
    // broadcast from the virtual network. It will output it to all its
    // materialized ports and to the logical port that connects it to the VAB
    case class ForkAction(actions: Seq[Action]) extends ForwardAction

    trait Device {

        /**
         * Process a packet described by the given match object. Note that the
         * Ethernet packet is the one originally ingressed the virtual network
         * - it does not reflect the changes made by other devices' handling of
         * the packet (whereas the match object does).
         *
         * @param pktContext The context for the simulation of this packet's
         * traversal of the virtual network. Use the context to subscribe
         * for notifications on the removal of any resulting flows, or to tag
         * any resulting flows for indexing.
         * @return An instance of Action that reflects what the device would do
         * after handling this packet (e.g. drop it, consume it, forward it).
         */
        def process(pktContext: PacketContext)
                   (implicit ec: ExecutionContext): Urgent[Action]
    }
}

/**
 * Coordinator object to simulate one packet traversing the virtual network.
 */
class Coordinator(pktCtx: PacketContext)
                 (implicit val ec: ExecutionContext,
                           val actorSystem: ActorSystem) {

    import Coordinator._

    implicit val logPktCtx: PacketContext = pktCtx
    implicit val log = LoggerFactory.getSimulationAwareLog(
        this.getClass)(actorSystem.eventStream)
    private val RETURN_FLOW_EXPIRATION_MILLIS = 60 * 1000
    private val MAX_DEVICES_TRAVERSED = 12

    // Used to detect loops: devices simulated (with duplicates).
    private var numDevicesSimulated = 0

    private def dropFlow(temporary: Boolean, withTags: Boolean)
    : Ready[SimulationResult] = {
        // If the packet is from the datapath, install a temporary Drop flow.
        // Note: a flow with no actions drops matching packets.
        pktCtx.traceMessage(null, "Dropping flow")
        Ready(
            if (pktCtx.ingressed) {
                val tags = if (withTags) pktCtx.flowTags
                           else Set.empty[FlowTag]
                if (temporary)
                    TemporaryDrop(tags, pktCtx.flowRemovedCallbacks)
                else
                    Drop(tags, pktCtx.flowRemovedCallbacks)
            } else {
                // Internally-generated packet. Do nothing.
                pktCtx.runFlowRemovedCallbacks()
                NoOp
            })
    }

    /**
     * Simulate the packet moving through the virtual topology. The packet
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
     * When the future returned by this method completes all the actions
     * resulting from the simulation (install flow and/or execute packet)
     * have been completed.
     *
     * The resulting future is never in a failed state.
     */
    def simulate(): Urgent[SimulationResult] = {
        try {
            bareSimulation() ifNotReady postpone
        } catch {
            case e: Exception =>
                postpone()
                throw e
        }
    }

    private def postpone() {
        log.debug("Simulation will be postponed, run callbacks")
        pktCtx.runFlowRemovedCallbacks()
    }

    def bareSimulation(): Urgent[SimulationResult] = {
        log.debug("Simulate a packet {}", pktCtx.ethernet)
        pktCtx.cookieOrEgressPort match {
            case Left(_) => // This is a packet from the datapath
                val inPortId = pktCtx.inputPort
                packetIngressesPort(inPortId, getPortGroups = true)
            case Right(egressID) =>
                packetEgressesPort(egressID)
        }
    }

    private def packetIngressesDevice(port: Port)
    : Urgent[SimulationResult] =
        (port match {
            case _: BridgePort => expiringAsk[Bridge](port.deviceID, log, pktCtx.expiry)
            case _: VxLanPort => expiringAsk[Bridge](port.deviceID, log, pktCtx.expiry)
            case _: RouterPort => expiringAsk[Router](port.deviceID, log, pktCtx.expiry)
        }) flatMap { case deviceReply =>
            numDevicesSimulated += 1
            log.debug("Simulating packet with match {}, device {}",
                pktCtx.wcmatch, port.deviceID)
            pktCtx.traceMessage(port.deviceID, "Entering device")
            deviceReply.process(pktCtx) flatMap { handleAction }
        }

    private def mergeSimulationResults(first: SimulationResult,
                                       second: SimulationResult)
    : SimulationResult = {
        (first, second) match {
            case (SendPacket(acts1), SendPacket(acts2)) =>
                SendPacket(acts1 ++ acts2)
            case (AddVirtualWildcardFlow(wcf1, _, _),
                  AddVirtualWildcardFlow(wcf2, _, _)) =>
                //TODO(rossella) set the other fields Priority
                val res = AddVirtualWildcardFlow(
                    wcf1.combine(wcf2),
                    pktCtx.flowRemovedCallbacks,
                    pktCtx.flowTags)
                log.debug("Forked action merged results {}", res)
                res
            case (firstAction, secondAction) =>
                val clazz1 = firstAction.getClass
                val clazz2 = secondAction.getClass
                if (clazz1 != clazz2) {
                    log.error("Matching actions of different types {} & {}!",
                        clazz1, clazz2)
                } else {
                    log.debug("Receive unrecognized action {}", firstAction)
                }
                dropFlow(temporary = true, withTags = false).get
        }
    }

    private def handleAction(action: Action): Urgent[SimulationResult] = {
        log.debug("Received action: {}", action)
        action match {
            case DoFlowAction(act) => act match {
                case b: FlowActionPopVLAN =>
                    val flow = WildcardFlow(
                        wcmatch = pktCtx.origMatch,
                        actions = List(b))
                    val vlanId = pktCtx.wcmatch.getVlanIds.get(0)
                    pktCtx.wcmatch.removeVlanId(vlanId)
                    Ready(virtualWildcardFlowResult(flow))
                case _ => Ready(NoOp)
            }

            case ForkAction(acts) =>
                // Our handler for each action consists on evaluating
                // the Coordinator action and pairing it with the
                // original WMatch at that point in time
                // Will fail if run in parallel because of side-effects

                var stopsAt: Urgent[SimulationResult] = null
                val originalMatch = pktCtx.origMatch.clone()
                // TODO: maybe replace with some other alternative that spares
                //       iterating the entire if we find the break cond
                val results = acts map handleAction map {
                    case n@NotYet(_) => // the simulation didn't complete
                        stopsAt = if (stopsAt == null) n else stopsAt
                        null
                    case Ready(simRes) =>
                        pktCtx.origMatch.reset(pktCtx.wcmatch)
                        simRes
                }

                pktCtx.origMatch.reset(originalMatch)

                if (stopsAt ne null) {
                    stopsAt
                } else {
                    // Merge the completed results of the simulations. The
                    // resulting pair (SimulationResult, WildcardMatch) contains
                    // the merge action resulting from the other partial ones
                    Ready(results reduceLeft mergeSimulationResults)
                }

            case ToPortSetAction(portSetID) =>
                pktCtx.traceMessage(portSetID, "Flooded to port set")
                emit(portSetID, isPortSet = true, null)

            case ToPortAction(outPortID) =>
                pktCtx.traceMessage(outPortID, "Forwarded to port")
                packetEgressesPort(outPortID)

            case ConsumedAction =>
                pktCtx.traceMessage(null, "Consumed")
                pktCtx.runFlowRemovedCallbacks()
                Ready(NoOp)

            case ErrorDropAction =>
                pktCtx.traceMessage(null, "Encountered error")
                dropFlow(temporary = true, withTags = false)

            case act: AbstractDropAction =>
                log.debug("Device returned DropAction for {}", pktCtx.origMatch)
                dropFlow(act.temporary || pktCtx.isConnTracked,
                         withTags = !act.temporary)

            case NotIPv4Action =>
                pktCtx.traceMessage(null, "Unsupported protocol")
                log.debug("Device returned NotIPv4Action for {}", pktCtx.origMatch)
                Ready(
                    if (pktCtx.isGenerated) {
                        pktCtx.runFlowRemovedCallbacks()
                        NoOp
                    } else {
                        val notIPv4Match =
                            new WildcardMatch()
                                .setInputPortUUID(
                                        pktCtx.origMatch.getInputPortUUID)
                                .setEthernetSource(
                                        pktCtx.origMatch.getEthernetSource)
                                .setEthernetDestination(
                                        pktCtx.origMatch.getEthernetDestination)
                                .setEtherType(
                                        pktCtx.origMatch.getEtherType)
                        virtualWildcardFlowResult(
                            WildcardFlow(wcmatch = notIPv4Match))
                        // TODO(pino): Connection-tracking blob?
                    })

            case _ =>
                pktCtx.traceMessage(null,
                                    "ERROR Unexpected action returned")
                log.error("Device returned unexpected action!")
                dropFlow(temporary = true, withTags = false)
        } // end action match
    }

    private def packetIngressesPort(portID: UUID, getPortGroups: Boolean)
    : Urgent[SimulationResult] =
        // Avoid loops - simulate at most X devices.
        if (numDevicesSimulated >= MAX_DEVICES_TRAVERSED) {
            dropFlow(temporary = true, withTags = false)
        } else {
            expiringAsk[Port](portID, log) flatMap { port =>
                pktCtx.addFlowTag(port.deviceTag)
                port match {
                    case p if !p.adminStateUp =>
                        processAdminStateDown(p, isIngress = true)
                    case p =>
                        if (getPortGroups && p.isExterior) {
                            pktCtx.portGroups = p.portGroups
                        }
                        pktCtx.inPortId = p
                        applyPortFilter(p, p.inboundFilter, packetIngressesDevice)
                }
            }
        }

    private def applyPortFilter(port: Port, filterID: UUID,
                                thunk: (Port) => Urgent[SimulationResult])
    : Urgent[SimulationResult] = {
        if (filterID == null)
            return thunk(port)

        expiringAsk[Chain](filterID, log, pktCtx.expiry) flatMap { chain =>
            val result = Chain.apply(chain, pktCtx,
                                     pktCtx.wcmatch, port.id, true)
            result.action match {
                case RuleResult.Action.ACCEPT =>
                    thunk(port)
                case RuleResult.Action.DROP | RuleResult.Action.REJECT =>
                    dropFlow(temporary = false, withTags = true)
                case other =>
                    log.error("Port filter {} returned {} which was " +
                            "not ACCEPT, DROP or REJECT.", filterID, other)
                    dropFlow(temporary = true, withTags = false)
            }
        }
    }

    /**
     * Simulate the packet egressing a virtual port. This is NOT intended
     * for output-ing to PortSets.
     */
    private def packetEgressesPort(portID: UUID): Urgent[SimulationResult] =
        expiringAsk[Port](portID, log) flatMap { port =>
            pktCtx.addFlowTag(port.deviceTag)

            port match {
                case p if !p.adminStateUp =>
                    processAdminStateDown(p, isIngress = false)
                case p =>
                    pktCtx.outPortId = p.id
                    applyPortFilter(p, p.outboundFilter, {
                        case p: Port if p.isExterior =>
                            emit(portID, isPortSet = false, p)
                        case p: Port if p.isInterior =>
                            packetIngressesPort(p.peerID, getPortGroups = false)
                        case _ =>
                            log.warning("Port {} is unplugged", portID)
                            dropFlow(temporary = true, withTags = false)
                    })
            }
        }

    /**
     * Complete the simulation by emitting the packet from the specified
     * virtual port or PortSet.  If the packet was internally generated
     * this will do a SendPacket, otherwise it will do an AddWildcardFlow.
     * @param outputID The ID of the virtual port or PortSet from which the
     *                 packet must be emitted.
     * @param isPortSet Whether the packet is output to a port set.
     * @param port The port output to; unused if outputting to a port set.
     */
    private def emit(outputID: UUID, isPortSet: Boolean, port: Port)
    : Urgent[SimulationResult] = {
        val actions = pktCtx.actionsFromMatchDiff()
        isPortSet match {
            case false =>
                log.debug("Emitting packet from vport {}", outputID)
                actions.append(FlowActionOutputToVrnPort(outputID))
            case true =>
                log.debug("Emitting packet from port set {}", outputID)
                actions.append(FlowActionOutputToVrnPortSet(outputID))
        }

        Ready(
            if (pktCtx.isGenerated) {
                log.debug("SendPacket with actions {}", actions)
                pktCtx.runFlowRemovedCallbacks()
                SendPacket(actions.toList)
            } else {
                log.debug("Add a flow with actions {}", actions)
                // TODO(guillermo,pino) don't assume that portset id == bridge id
                if (pktCtx.isConnTracked && pktCtx.isForwardFlow) {
                    // Write the packet's data to the connectionCache.
                    pktCtx.installConnectionCacheEntry(
                            if (isPortSet) outputID else port.deviceID)
                }

                var idleExp = 0
                var hardExp = 0

                if (pktCtx.isConnTracked) {
                    if (pktCtx.isForwardFlow) {
                        // See #577: if fwd flow we expire the fwd bc. we want
                        // it to keep refreshing the cache key
                        hardExp = RETURN_FLOW_EXPIRATION_MILLIS / 2
                    } else {
                        // if ret flow, we need to simulate periodically to
                        // ensure that it's legit by checking that there is a
                        // matching fwd flow
                        hardExp = RETURN_FLOW_EXPIRATION_MILLIS
                    }
                } else {
                    idleExp = IDLE_EXPIRATION_MILLIS
                }
                val wFlow = WildcardFlow(
                        wcmatch = pktCtx.origMatch,
                        actions = actions.toList,
                        idleExpirationMillis = idleExp,
                        hardExpirationMillis = hardExp)
                virtualWildcardFlowResult(wFlow)
            })
    }

    private[this] def processAdminStateDown(port: Port, isIngress: Boolean)
    : Urgent[SimulationResult] = {
        port match {
            case p: RouterPort if isIngress =>
                sendIcmpProhibited(p)
            case p: RouterPort if pktCtx.inPortId != null =>
                expiringAsk[Port](pktCtx.inPortId, log, pktCtx.expiry) map {
                    case p: RouterPort =>
                        sendIcmpProhibited(p)
                    case _ =>
                }
            case _ =>
        }

        dropFlow(temporary = false, withTags = true)
    }

    private def sendIcmpProhibited(port: RouterPort) {
        val ethOpt = unreachableProhibitedIcmp(port, pktCtx.wcmatch, pktCtx.ethernet)
        if (ethOpt.nonEmpty)
            PacketsEntryPoint !
                EmitGeneratedPacket(port.id, ethOpt.get, pktCtx.flowCookie)
    }

    /** Generates a final AddVirtualWildcardFlow simulation result */
    private def virtualWildcardFlowResult(wcFlow: WildcardFlow) = {
        wcFlow.wcmatch.propagateUserspaceFieldsOf(pktCtx.wcmatch)
        AddVirtualWildcardFlow(wcFlow,
            pktCtx.flowRemovedCallbacks,
            pktCtx.flowTags)
    }
}
