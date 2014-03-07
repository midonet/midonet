/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.simulation

import java.util.UUID
import scala.collection.JavaConversions._
import scala.collection.immutable
import scala.collection.mutable.ListBuffer

import akka.actor.ActorSystem
import scala.concurrent.{ExecutionContext, Future}

import org.midonet.cache.Cache
import org.midonet.cluster.client._
import org.midonet.midolman.PacketsEntryPoint
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.Icmp.IPv4Icmp._
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology._
import org.midonet.odp.flows._
import org.midonet.odp.flows.FlowActions.{setKey, popVLAN}
import org.midonet.packets.{Ethernet, ICMP, IPv4, IPv4Addr, IPv6Addr, TCP, UDP}
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPortSet
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.util.concurrent._

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
        val temporary = false
    }

    case object TemporaryDropAction extends AbstractDropAction {
        val temporary = true
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
                   (implicit ec: ExecutionContext): Future[Action]
    }

    /*
     * Compares two objects, which may be null, to determine if they should
     * cause flow actions.
     * The catch here is that if `modif` is null, the verdict is true regardless
     * because we don't create actions that set values to null.
     */
    def matchObjectsSame(orig: Any, modif: Any) = modif == null || orig == modif

}

/**
 * Coordinator object to simulate one packet traversing the virtual network.
 *
 * @param origMatch
 * @param origEthernetPkt
 * @param cookie
 * @param generatedPacketEgressPort Only used if this packet was generated
 *                                  by a virtual device. It's the ID of
 *                                  the port via which the packet
 *                                  egresses the device.
 * @param expiry The time (as returned by currentTime) at which to expire
 *               this simulation.
 * @param connectionCache The Cache to use for connection tracking.
 * @param traceMessageCache The Cache to use for trace messages.
 * @param traceIndexCache The Cache to use for the trace index.
 * @param parentCookie TODO
 * @param traceConditions Seq of Conditions which will trigger tracing.
 * @param ec
 */
class Coordinator(var origMatch: WildcardMatch,
                  val origEthernetPkt: Ethernet,
                  val cookie: Option[Int],
                  val generatedPacketEgressPort: Option[UUID],
                  val expiry: Long,
                  val connectionCache: Cache,
                  val traceMessageCache: Cache,
                  val traceIndexCache: Cache,
                  val parentCookie: Option[Int],
                  val traceConditions: immutable.Seq[Condition])
                 (implicit val ec: ExecutionContext,
                           val actorSystem: ActorSystem) {

    import Coordinator._

    implicit val log = LoggerFactory.getSimulationAwareLog(
        this.getClass)(actorSystem.eventStream)
    private val RETURN_FLOW_EXPIRATION_MILLIS = 60 * 1000
    private val MAX_DEVICES_TRAVERSED = 12

    // Used to detect loops: devices simulated (with duplicates).
    private var numDevicesSimulated = 0
    implicit private val pktContext = new PacketContext(cookie, origEthernetPkt,
         expiry, connectionCache, traceMessageCache, traceIndexCache,
         generatedPacketEgressPort.isDefined, parentCookie, origMatch)
    pktContext.setTraced(matchTraceConditions())

    implicit def simulationActionToSuccessfulFuture(
        a: SimulationResult): Future[SimulationResult] = Future.successful(a)

    private def matchTraceConditions(): Boolean = {
        val anyConditionMatching =
            traceConditions exists { _.matches(pktContext, origMatch, false) }
        log.debug("Checking packet {} against tracing conditions {}: {}",
                  pktContext, traceConditions, anyConditionMatching);
        anyConditionMatching
    }

    private def dropFlow(temporary: Boolean, withTags: Boolean)
    : SimulationResult = {
        // If the packet is from the datapath, install a temporary Drop flow.
        // Note: a flow with no actions drops matching packets.
        pktContext.freeze()
        pktContext.traceMessage(null, "Dropping flow")
        cookie match {
            case Some(_) =>
                val tags = if (withTags) pktContext.getFlowTags else Set.empty[Any]
                if (temporary)
                    TemporaryDrop(tags, pktContext.getFlowRemovedCallbacks)
                else
                    Drop(tags, pktContext.getFlowRemovedCallbacks)
            case None => // Internally-generated packet. Do nothing.
                pktContext.runFlowRemovedCallbacks()
                NoOp
        }
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
    def simulate(): Future[SimulationResult] = {
        log.debug("Simulate a packet {}", origEthernetPkt)

        val simf: Future[SimulationResult] = generatedPacketEgressPort match {
            case None => // This is a packet from the datapath
                origMatch.getInputPortUUID match {
                    case null =>
                        log.error(
                            "Coordinator cannot simulate a flow that " +
                            "NEITHER egressed a virtual device's interior " +
                            "port NOR ingressed a virtual device's exterior " +
                            "port. Match: %s; Packet: %s".format(
                                origMatch.toString, origEthernetPkt.toString))
                        dropFlow(temporary = true, withTags = false)

                    case inPortId: UUID =>
                        val simRes = packetIngressesPort(inPortId,
                                                         getPortGroups = true)
                        if (origMatch.getIpFragmentType == IPFragmentType.None)
                            simRes
                        else
                            simRes map handleFragmentation(inPortId)
                }
            case Some(egressID) =>
                origMatch.getInputPortUUID match {
                    case null => packetEgressesPort(egressID)
                    case id: UUID =>
                        log.error(
                            "Coordinator cannot simulate a flow that " +
                            "BOTH egressed a virtual device's interior " +
                            "port AND ingressed a virtual device's exterior " +
                            "port. Match: %s; Packet: %s".format(
                                origMatch.toString, origEthernetPkt.toString))
                        dropFlow(temporary = true, withTags = false)
                }
        }
        simf recover { case _ =>
            dropFlow(temporary = true, withTags = false)
        }
    }

    /**
     * After a simulation, take care of fragmentation. This checks if the sim
     * touched any L4 fields in the WcMatch. If it didn't it'll let the packet
     * through. Otherwise it'll reply with a Frag. Needed for the first fragment
     * and drop subsequent ones.
     */
    private def handleFragmentation(inPort: UUID)
                                   (simRes: SimulationResult) = simRes match {
        case sr if pktContext.wcmatch.highestLayerSeen() < 4 =>
            log.debug("Fragmented packet, L4 fields untouched: execute")
            sr
        case sr =>
            log.debug("Fragmented packet, L4 fields touched..")
            origMatch.getIpFragmentType match {
                case IPFragmentType.First =>
                    origMatch.getEtherType.shortValue match {
                        case IPv4.ETHERTYPE =>
                            /* We don't support fragmentation. So, assuming some
                             * device in the middle fragmented the packet, we
                             * send a Fragmentation Needed ICMP with that device's
                             * MTU so that the sender can adjust itself and the
                             * device will stop fragmenting subsequent packets.
                             */
                            log.debug("Reply with frag needed and DROP")
                            sendIpv4FragNeeded(inPort)
                            dropFlow(temporary = true, withTags = false)
                        case ethertype =>
                            log.info("Dropping fragmented packet of " +
                                     "unsupported ethertype={}", ethertype)
                            dropFlow(temporary = true, withTags = false)
                    }
                case IPFragmentType.Later =>
                    log.debug("Dropping non-first fragment at simulation layer")
                    dropFlow(temporary = false, withTags = true)
                case IPFragmentType.None =>
                    throw new IllegalStateException(
                        "handleFragmentation called for an unfragmented packet")
            }
    }

    private def sendIpv4FragNeeded(inPort: UUID) {
        val origPkt: IPv4 = origEthernetPkt.getPayload match {
            case ip: IPv4 => ip
            case _ => null
        }

        if (origPkt == null)
            return

        /* Certain versions of Linux try to guess a lower MTU value if the MTU
         * suggested by the ICMP FRAG_NEEDED error is equal or bigger than the
         * size declared in the IP header found in the ICMP data. This happens
         * when it's the sender host who is fragmenting.
         *
         * In those cases, we can at least prevent the sender from lowering
         * the PMTU by increasing the length in the IP header.
         */
        val mtu = origPkt.getTotalLength match {
            case 0 => origPkt.serialize().length
            case nonZero => nonZero
        }
        origPkt.setTotalLength(mtu + 1)
        origPkt.setChecksum(0)

        val icmp = new ICMP()
        icmp.setFragNeeded(mtu, origPkt)
        val ip = new IPv4()
        ip.setPayload(icmp)
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        ip.setSourceAddress(origPkt.getDestinationAddress)
        ip.setDestinationAddress(origPkt.getSourceAddress)
        val eth = new Ethernet()
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(ip)
        eth.setSourceMACAddress(origEthernetPkt.getDestinationMACAddress)
        eth.setDestinationMACAddress(origEthernetPkt.getSourceMACAddress)

        PacketsEntryPoint ! EmitGeneratedPacket(inPort, eth, cookie)
    }

    private def packetIngressesDevice(port: Port)
    : Future[SimulationResult] =
        (port match {
            case _: BridgePort => expiringAsk[Bridge](port.deviceID, log, expiry)
            case _: RouterPort => expiringAsk[Router](port.deviceID, log, expiry)
        }) flatMap { case deviceReply =>
            numDevicesSimulated += 1
            log.debug("Simulating packet with match {}, device {}",
                pktContext.wcmatch, port.deviceID)
            pktContext.traceMessage(port.deviceID, "Entering device")
            deviceReply.process(pktContext) flatMap { handleAction }
        }

    private def mergeSimulationResults(firstAction: SimulationResult,
                                       secondAction: SimulationResult,
                                       firstOrigMatch: WildcardMatch)
    : SimulationResult = {
        (firstAction, secondAction) match {
            case (SendPacket(acts1), SendPacket(acts2)) =>
                SendPacket(acts1 ++ acts2)
            case (AddVirtualWildcardFlow(wcf1, cb1, tags1),
                    AddVirtualWildcardFlow(wcf2, cb2, tags2)) =>
                //TODO(rossella) set the other fields Priority
                val res = AddVirtualWildcardFlow(
                    wcf1.combine(wcf2), cb1 ++ cb2, tags1 ++ tags2)
                log.debug("Forked action merged results {}", res)
                res
            case _ =>
                val clazz1 = firstAction.getClass
                val clazz2 = secondAction.getClass
                if (clazz1 != clazz2) {
                    log.error("Matching actions of different types {} & {}!",
                        clazz1, clazz2)
                } else {
                    log.debug("Receive unrecognized action {}", firstAction)
                }
                origMatch = firstOrigMatch
                dropFlow(temporary = true, withTags = false)
        }
    }

    private def handleAction(action: Action): Future[SimulationResult] = {
        log.debug("Received action: {}", action)
        action match {

            case DoFlowAction(action) =>
                action match {
                    case b: FlowActionPopVLAN =>
                        pktContext.unfreeze()
                        val flow = WildcardFlow(
                            wcmatch = origMatch,
                            actions = List(b))
                        val vlanId = pktContext.wcmatch.getVlanIds.get(0)
                        pktContext.wcmatch.removeVlanId(vlanId)
                        origMatch = pktContext.wcmatch.clone()
                        pktContext.freeze()
                        AddVirtualWildcardFlow(flow,
                            pktContext.getFlowRemovedCallbacks,
                            pktContext.getFlowTags)
                    case _ => NoOp
                }

            case ForkAction(acts) =>
                val results = Future.sequentially(acts) {
                    (act: Coordinator.Action) => {
                        // Our handler for each action consists on evaluating
                        // the Coordinator action and pairing it with the
                        // original WMatch at that point in time
                        // Will fail if run in parallel because of side-effects
                        handleAction(act) map {
                            simRes => {
                                val firstOrigMatch = origMatch.clone()
                                origMatch = pktContext.wcmatch.clone()
                                pktContext.unfreeze()
                                (simRes, firstOrigMatch)
                            }
                        }
                    }
                }

                // When ready, merge the results of the simulations. The
                // resulting pair (SimulationResult, WildcardMatch) contains
                // the merge action resulting from the other partial ones
                results.map (results => results.reduceLeft(
                    (a, b) => (mergeSimulationResults(a._1, b._1, a._2), b._2)
                )).map( r => r._1)

            case ToPortSetAction(portSetID) =>
                pktContext.traceMessage(portSetID, "Flooded to port set")
                emit(portSetID, isPortSet = true, null)

            case ToPortAction(outPortID) =>
                pktContext.traceMessage(outPortID, "Forwarded to port")
                packetEgressesPort(outPortID)

            case ConsumedAction =>
                pktContext.traceMessage(null, "Consumed")
                pktContext.freeze()
                pktContext.runFlowRemovedCallbacks()
                NoOp

            case ErrorDropAction =>
                pktContext.traceMessage(null, "Encountered error")
                dropFlow(temporary = true, withTags = false)

            case act: AbstractDropAction =>
                log.debug("Device returned DropAction for {}", origMatch)
                dropFlow(act.temporary || pktContext.isConnTracked,
                         withTags = !act.temporary)

            case NotIPv4Action =>
                pktContext.traceMessage(null, "Unsupported protocol")
                pktContext.freeze()
                log.debug("Device returned NotIPv4Action for {}", origMatch)
                cookie match {
                    case None => // Do nothing.
                        pktContext.runFlowRemovedCallbacks()
                        NoOp
                    case Some(_) =>
                        val notIPv4Match =
                            new WildcardMatch()
                                .setInputPortUUID(origMatch.getInputPortUUID)
                                .setEthernetSource(origMatch.getEthernetSource)
                                .setEthernetDestination(
                                    origMatch.getEthernetDestination)
                                .setEtherType(origMatch.getEtherType)
                        AddVirtualWildcardFlow(
                            WildcardFlow(wcmatch = notIPv4Match),
                            pktContext.getFlowRemovedCallbacks,
                            pktContext.getFlowTags)
                        // TODO(pino): Connection-tracking blob?
                }


            case _ =>
                pktContext.traceMessage(null,
                                        "ERROR Unexpected action returned")
                log.error("Device returned unexpected action!")
                dropFlow(temporary = true, withTags = false)
        } // end action match
    }

    private def packetIngressesPort(portID: UUID, getPortGroups: Boolean)
    : Future[SimulationResult] = {
        // Avoid loops - simulate at most X devices.
        if (numDevicesSimulated >= MAX_DEVICES_TRAVERSED) {
            return dropFlow(temporary = true, withTags = false)
        }

        // add tag for flow invalidation
        pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(portID))

        // Get the RCU port object and start simulation.
        expiringAsk[Port](portID, log) flatMap {
            case p if !p.adminStateUp =>
                processAdminStateDown(p, isIngress = true)
            case p =>
                if (getPortGroups && p.isExterior) {
                    pktContext.portGroups = p.portGroups
                }

                pktContext.inPortId = p
                applyPortFilter(p, p.inboundFilter, packetIngressesDevice)
        }
    }

    def applyPortFilter(port: Port, filterID: UUID,
                        thunk: (Port) => Future[SimulationResult]):
                        Future[SimulationResult] = {
        if (filterID == null)
            return thunk(port)

        expiringAsk[Chain](filterID, log, expiry) flatMap { chain =>
            val result = Chain.apply(chain, pktContext,
                pktContext.wcmatch, port.id, true)
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
     * @param portID
     */
    private def packetEgressesPort(portID: UUID): Future[SimulationResult] = {
        // add tag for flow invalidation
        pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(portID))

        expiringAsk[Port](portID, log) flatMap {
            case port if !port.adminStateUp =>
                processAdminStateDown(port, isIngress = false)
            case port =>
                pktContext.outPortId = port.id
                applyPortFilter(port, port.outboundFilter, {
                    case port: Port if port.isExterior =>
                        emit(portID, isPortSet = false, port)
                    case port: Port if port.isInterior =>
                        packetIngressesPort(port.peerID,
                            getPortGroups = false)
                    case _ =>
                        log.warning("Port {} is unplugged", port)
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
    private def emit(outputID: UUID, isPortSet: Boolean, port: Port):
            SimulationResult = {
        val actions = actionsFromMatchDiff(origMatch, pktContext.wcmatch)
        isPortSet match {
            case false =>
                log.debug("Emitting packet from vport {}", outputID)
                actions.append(FlowActionOutputToVrnPort(outputID))
            case true =>
                log.debug("Emitting packet from port set {}", outputID)
                actions.append(FlowActionOutputToVrnPortSet(outputID))
        }

        cookie match {
            case None =>
                log.debug("No cookie. SendPacket with actions {}", actions)
                pktContext.freeze()
                pktContext.runFlowRemovedCallbacks()
                SendPacket(actions.toList)
            case Some(_) =>
                log.debug("Cookie {}; Add a flow with actions {}",
                    cookie.get, actions)
                pktContext.freeze()
                // TODO(guillermo,pino) don't assume that portset id == bridge id
                if (pktContext.isConnTracked && pktContext.isForwardFlow) {
                    // Write the packet's data to the connectionCache.
                    pktContext.installConnectionCacheEntry(
                            if (isPortSet) outputID else port.deviceID)
                }

                var idleExp = 0
                var hardExp = 0

                if (pktContext.isConnTracked) {
                    if (pktContext.isForwardFlow) {
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
                        wcmatch = origMatch,
                        actions = actions.toList,
                        idleExpirationMillis = idleExp,
                        hardExpirationMillis = hardExp)

                AddVirtualWildcardFlow(wFlow,
                                       pktContext.getFlowRemovedCallbacks,
                                       pktContext.getFlowTags)
        }
    }

    private[this] def processAdminStateDown(port: Port, isIngress: Boolean)
    : SimulationResult = {
        port match {
            case p: RouterPort if isIngress =>
                sendIcmpProhibited(p)
            case p: RouterPort if pktContext.inPortId != null =>
                expiringAsk[Port](pktContext.inPortId, log, expiry) map {
                    case p: RouterPort =>
                        sendIcmpProhibited(p)
                    case _ =>
                }
            case _ =>
        }

        dropFlow(temporary = false, withTags = true)
    }

    private def sendIcmpProhibited(port: RouterPort) {
        val ethOpt = unreachableProhibitedIcmp(port, pktContext.wcmatch,
            pktContext.frame)
        if (ethOpt.nonEmpty)
            PacketsEntryPoint !
                EmitGeneratedPacket(port.id, ethOpt.get, pktContext.flowCookie)
    }

    private def actionsFromMatchDiff(orig: WildcardMatch, modif: WildcardMatch)
    : ListBuffer[FlowAction] = {
        val actions = ListBuffer[FlowAction]()
        modif.doNotTrackSeenFields()
        orig.doNotTrackSeenFields()
        if (!orig.getEthernetSource.equals(modif.getEthernetSource) ||
            !orig.getEthernetDestination.equals(modif.getEthernetDestination)) {
            actions.append(setKey(FlowKeys.ethernet(
                modif.getDataLayerSource, modif.getDataLayerDestination)))
        }
        if (!matchObjectsSame(orig.getNetworkSourceIP,
                              modif.getNetworkSourceIP) ||
            !matchObjectsSame(orig.getNetworkDestinationIP,
                              modif.getNetworkDestinationIP) ||
            !matchObjectsSame(orig.getNetworkTTL,
                              modif.getNetworkTTL)) {
            actions.append(setKey(
                modif.getNetworkSourceIP match {
                    case srcIP: IPv4Addr =>
                        FlowKeys.ipv4(srcIP,
                            modif.getNetworkDestinationIP.asInstanceOf[IPv4Addr],
                            modif.getNetworkProtocol,
                            modif.getNetworkTypeOfService,
                            modif.getNetworkTTL,
                            modif.getIpFragmentType)
                    case srcIP: IPv6Addr =>
                        FlowKeys.ipv6(srcIP,
                            modif.getNetworkDestinationIP.asInstanceOf[IPv6Addr],
                            modif.getNetworkProtocol,
                            modif.getNetworkTTL,
                            modif.getIpFragmentType)
                }
            ))
        }
        // Vlan tag
        if (!orig.getVlanIds.equals(modif.getVlanIds)) {
            val vlansToRemove = orig.getVlanIds.diff(modif.getVlanIds)
            val vlansToAdd = modif.getVlanIds.diff(orig.getVlanIds)
            log.debug("Vlan tags to pop {}, vlan tags to push {}",
                      vlansToRemove, vlansToAdd)

            for (vlan <- vlansToRemove) {
                actions.append(popVLAN())
            }

            var count = vlansToAdd.size
            for (vlan <- vlansToAdd) {
                count -= 1
                val protocol = if (count == 0) Ethernet.VLAN_TAGGED_FRAME
                               else Ethernet.PROVIDER_BRIDGING_TAG
                actions.append(FlowActions.pushVLAN(vlan, protocol))
            }
        }

        // ICMP errors
        if (!matchObjectsSame(orig.getIcmpData,
                              modif.getIcmpData)) {
            val icmpType = modif.getTransportSource()
            if (icmpType == ICMP.TYPE_PARAMETER_PROBLEM ||
                icmpType == ICMP.TYPE_UNREACH ||
                icmpType == ICMP.TYPE_TIME_EXCEEDED) {

                actions.append(setKey(FlowKeys.icmpError(
                    modif.getTransportSource.asInstanceOf[Byte],
                    modif.getTransportDestination.asInstanceOf[Byte],
                    modif.getIcmpData
                )))
            }
        }
        if (!matchObjectsSame(orig.getTransportSourceObject,
                              modif.getTransportSourceObject) ||
            !matchObjectsSame(orig.getTransportDestinationObject,
                              modif.getTransportDestinationObject)) {

            modif.getNetworkProtocol match {
                case TCP.PROTOCOL_NUMBER =>
                    actions.append(setKey(FlowKeys.tcp(
                        modif.getTransportSource,
                        modif.getTransportDestination)))
                case UDP.PROTOCOL_NUMBER =>
                    actions.append(setKey(FlowKeys.udp(
                        modif.getTransportSource,
                        modif.getTransportDestination)))
                case ICMP.PROTOCOL_NUMBER =>
                    // this case would only happen if icmp id in ECHO req/reply
                    // were translated, which is not the case, so leave alone
            }
        }
        modif.doTrackSeenFields()
        orig.doTrackSeenFields()
        actions
    }
}
