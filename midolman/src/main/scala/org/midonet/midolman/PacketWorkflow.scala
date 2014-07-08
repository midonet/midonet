/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.util.{List => JList}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.{Set => ROSet, mutable}
import scala.reflect.ClassTag

import akka.actor._
import akka.event.{Logging, LoggingAdapter}
import akka.util.Timeout

import org.midonet.cluster.DataClient
import org.midonet.cluster.client.Port
import org.midonet.midolman.topology.VxLanPortMapper
import org.midonet.midolman.DeduplicationActor.ActionsCache
import org.midonet.midolman.simulation.{Coordinator, PacketContext, DhcpImpl}
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.{Packet, Datapath, Flow, FlowMatch}
import org.midonet.odp.flows.{FlowKey, FlowKeyUDP, FlowAction}
import org.midonet.packets._
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.functors.Callback0
import org.midonet.midolman.io.DatapathConnectionPool

trait PacketHandler {
    def start(pktCtx: PacketContext): Urgent[PacketWorkflow.PipelinePath]
    def drop(pktCtx: PacketContext): Unit
}

object PacketWorkflow {
    case class PacketIn(wMatch: WildcardMatch,
                        eth: Ethernet,
                        dpMatch: FlowMatch,
                        reason: Packet.Reason,
                        cookie: Int)

    val TEMPORARY_DROP_MILLIS = 5 * 1000
    val IDLE_EXPIRATION_MILLIS = 60 * 1000

    sealed trait SimulationResult

    case object NoOp extends SimulationResult

    sealed trait DropSimulationResult extends SimulationResult {
        val tags: ROSet[FlowTag]
        val flowRemovalCallbacks: Seq[Callback0]
    }

    case class Drop(tags: ROSet[FlowTag],
                    flowRemovalCallbacks: Seq[Callback0])
            extends DropSimulationResult

    case class TemporaryDrop(tags: ROSet[FlowTag],
                             flowRemovalCallbacks: Seq[Callback0])
            extends DropSimulationResult

    case class SendPacket(actions: List[FlowAction]) extends SimulationResult

    case class AddVirtualWildcardFlow(flow: WildcardFlow,
                                      flowRemovalCallbacks: Seq[Callback0],
                                      tags: ROSet[FlowTag]) extends SimulationResult

    sealed trait PipelinePath
    case object WildcardTableHit extends PipelinePath
    case object PacketToPortSet extends PipelinePath
    case object Simulation extends PipelinePath
    case object Error extends PipelinePath
}

class PacketWorkflow(protected val dpState: DatapathState,
                     val datapath: Datapath,
                     val dataClient: DataClient,
                     val dpConnPool: DatapathConnectionPool,
                     val actionsCache: ActionsCache)
                    (implicit val system: ActorSystem)
        extends FlowTranslator with PacketHandler {

    import PacketWorkflow._
    import DeduplicationActor._
    import FlowController.{AddWildcardFlow, FlowAdded}
    import VirtualToPhysicalMapper.PortSetForTunnelKeyRequest

    val ERROR_CONDITION_HARD_EXPIRATION = 10000

    implicit val requestReplyTimeout = new Timeout(5, TimeUnit.SECONDS)

    val log: LoggingAdapter = Logging.getLogger(system, this.getClass)
    var cookieStr: String = null

    override def start(pktCtx: PacketContext): Urgent[PipelinePath] = {
        // TODO: remove this when flow translation takes in a PacketContext
        cookieStr = pktCtx.cookieStr
        pktCtx.prepareForSimulation(FlowController.lastInvalidationEvent)
        log.debug("Initiating processing of packet {}, attempt: {}",
                  cookieStr, pktCtx.runs)
        val res = (if (pktCtx.ingressed)
                    handlePacketWithCookie(pktCtx)
                  else
                    doEgressPortSimulation(pktCtx)
        ) match {
            case n@NotYet(_) => pktCtx.postpone(); n
            case r => r
        }

        cookieStr = null
        res
    }

    override def drop(pktCtx: PacketContext) {
        pktCtx.prepareForSimulation(FlowController.lastInvalidationEvent)
        val wildFlow = WildcardFlow(wcmatch = pktCtx.origMatch,
            hardExpirationMillis = ERROR_CONDITION_HARD_EXPIRATION)
        addTranslatedFlow(pktCtx, wildFlow, Set.empty, Nil)
    }

    def runSimulation(pktCtx: PacketContext): Urgent[SimulationResult] =
        new Coordinator(pktCtx).simulate()

    private def notifyFlowAdded(pktCtx: PacketContext,
                                flow: Flow,
                                newWildFlow: Option[WildcardFlow],
                                tags: ROSet[FlowTag],
                                removalCallbacks: Seq[Callback0]) {
        log.debug("Successfully created flow for {}", cookieStr)
        newWildFlow match {
            case None =>
                FlowController ! FlowAdded(flow, pktCtx.origMatch)
                addToActionsCacheAndInvalidate(pktCtx, flow.getActions)
            case Some(wf) =>
                FlowController ! AddWildcardFlow(wf, flow, removalCallbacks, tags,
                                pktCtx.lastInvalidation, pktCtx.packet.getMatch,
                                actionsCache.pending,
                                actionsCache.getSlot(pktCtx.cookieStr))
                actionsCache.actions.put(pktCtx.packet.getMatch, flow.getActions)
        }
    }

    private def createFlow(pktCtx: PacketContext,
                           wildFlow: WildcardFlow,
                           newWildFlow: Option[WildcardFlow] = None,
                           tags: ROSet[FlowTag] = Set.empty,
                           removalCallbacks: Seq[Callback0] = Nil) {
        log.debug("Creating flow from {} for {}", wildFlow, cookieStr)
        val dpFlow = new Flow(pktCtx.packet.getMatch, wildFlow.getActions)
        try {
            datapathConn(pktCtx).flowsCreate(datapath, dpFlow)
            notifyFlowAdded(pktCtx, dpFlow, newWildFlow, tags, removalCallbacks)
        } catch {
            case e: NetlinkException =>
                log.info("{} Failed to add flow packet: {}", cookieStr, e)
                addToActionsCacheAndInvalidate(pktCtx, dpFlow.getActions)
        }
    }

    private def addTranslatedFlow(pktCtx: PacketContext,
                                  wildFlow: WildcardFlow,
                                  tags: ROSet[FlowTag],
                                  removalCallbacks: Seq[Callback0]) {
        if (pktCtx.packet.getReason == Packet.Reason.FlowActionUserspace)
            runCallbacks(removalCallbacks)
        else if (areTagsValid(pktCtx, tags))
            handleValidFlow(pktCtx, wildFlow, tags, removalCallbacks)
        else
            handleObsoleteFlow(pktCtx, wildFlow, removalCallbacks)

        executePacket(pktCtx, wildFlow.getActions)
    }

    def areTagsValid(pktCtx: PacketContext, tags: ROSet[FlowTag]) =
        FlowController.isTagSetStillValid(pktCtx.lastInvalidation, tags)

    private def handleObsoleteFlow(pktCtx: PacketContext,
                                   wildFlow: WildcardFlow,
                                   removalCallbacks: Seq[Callback0]) {
        log.debug("Skipping creation of obsolete flow {} for {}",
                  cookieStr, wildFlow.getMatch)
        if (pktCtx.ingressed)
            addToActionsCacheAndInvalidate(pktCtx, wildFlow.getActions)
        runCallbacks(removalCallbacks)
    }

    private def handleValidFlow(pktCtx: PacketContext,
                                wildFlow: WildcardFlow,
                                tags: ROSet[FlowTag],
                                removalCallbacks: Seq[Callback0]) {
        if (pktCtx.isGenerated) {
            log.debug("Only adding wildcard flow {} for {}",
                      wildFlow, cookieStr)
            FlowController ! AddWildcardFlow(wildFlow, null, removalCallbacks,
                                             tags, pktCtx.lastInvalidation)
        } else if (wildFlow.wcmatch.userspaceFieldsSeen) {
            log.debug("Adding wildcard flow {} for match with userspace " +
                      "only fields, without a datapath flow", wildFlow)
            FlowController ! AddWildcardFlow(wildFlow, null, removalCallbacks,
                tags, pktCtx.lastInvalidation, pktCtx.packet.getMatch,
                actionsCache.pending, actionsCache.getSlot(cookieStr))
            actionsCache.actions.put(pktCtx.packet.getMatch, wildFlow.actions)

        } else {
            createFlow(pktCtx, wildFlow, Some(wildFlow), tags, removalCallbacks)
        }
    }

    private def addTranslatedFlowForActions(
                           pktCtx: PacketContext,
                            actions: Seq[FlowAction],
                            tags: ROSet[FlowTag] = Set.empty,
                            removalCallbacks: Seq[Callback0] = Nil,
                            hardExpirationMillis: Int = 0,
                            idleExpirationMillis: Int = IDLE_EXPIRATION_MILLIS) {

        val wildFlow = WildcardFlow(
            wcmatch = pktCtx.origMatch,
            hardExpirationMillis = hardExpirationMillis,
            idleExpirationMillis = idleExpirationMillis,
            actions =  actions.toList,
            priority = 0)

        addTranslatedFlow(pktCtx, wildFlow, tags, removalCallbacks)
    }

    def executePacket(pktCtx: PacketContext, actions: Seq[FlowAction]) {
        val finalActions = if (pktCtx.packet.getMatch.isUserSpaceOnly) {
            UserspaceFlowActionTranslator.translate(pktCtx.packet, actions.asJava)
        } else {
            actions.asJava
        }

        if (finalActions.isEmpty) {
            log.debug("Dropping packet {}", cookieStr)
            return
        }

        try {
            log.debug("Executing packet {}", cookieStr)
            datapathConn(pktCtx).packetsExecute(datapath, pktCtx.packet, finalActions)
        } catch {
            case e: NetlinkException =>
                log.info("{} Failed to execute packet: {}", cookieStr, e)
        }
    }

    private def handlePacketWithCookie(pktCtx: PacketContext)
    : Urgent[PipelinePath] = {
        if (pktCtx.origMatch.getInputPortNumber eq null) {
            log.error("PacketIn had no inPort number {}.", cookieStr)
            return Ready(Error)
        }
        if (pktCtx.packet.getReason == Packet.Reason.FlowActionUserspace) {
            setVportForLocalTraffic(pktCtx)
            processSimulationResult(pktCtx, simulatePacketIn(pktCtx))
        } else {
            FlowController.queryWildcardFlowTable(pktCtx.origMatch) match {
                case Some(wildflow) =>
                    log.debug("{} - {} hit the WildcardFlowTable", cookieStr,
                              pktCtx.origMatch)
                    handleWildcardTableMatch(pktCtx, wildflow)
                    Ready(WildcardTableHit)
                case None =>
                    log.debug("{} - {} missed the WildcardFlowTable", cookieStr,
                              pktCtx.origMatch)
                    handleWildcardTableMiss(pktCtx)
            }
        }
    }

    def handleWildcardTableMatch(pktCtx: PacketContext,
                                 wildFlow: WildcardFlow): Unit = {
        log.debug("Packet {} matched a wildcard flow", cookieStr)
        if (wildFlow.wcmatch.userspaceFieldsSeen) {
            log.debug("no datapath flow for match {} with userspace only fields",
                      wildFlow.wcmatch)
            addToActionsCacheAndInvalidate(pktCtx, wildFlow.getActions)
        } else {
            createFlow(pktCtx, wildFlow)
        }

        executePacket(pktCtx, wildFlow.getActions)
    }

    private def handleWildcardTableMiss(pktCtx: PacketContext): Urgent[PipelinePath] = {
        /** If the FlowMatch indicates that the packet came from a tunnel port,
         *  we assume here there are only two possible situations: 1) the tunnel
         *  port type is gre, in which case we are dealing with host2host
         *  tunnelled traffic; 2) the tunnel port type is vxlan, in which case
         *  we are dealing with vtep to midolman traffic. In the future, using
         *  vxlan encap for host2host tunnelling will break this assumption. */
        val wcMatch = pktCtx.origMatch
        if (wcMatch.isFromTunnel) {
            if (dpState isOverlayTunnellingPort wcMatch.getInputPortNumber) {
                handlePacketToPortSet(pktCtx)
            } else {
                val portIdOpt = VxLanPortMapper uuidOf wcMatch.getTunnelID.toInt
                pktCtx.inputPort = portIdOpt.orNull
                processSimulationResult(pktCtx, simulatePacketIn(pktCtx))
            }
        } else {
            /* QUESTION: do we need another de-duplication stage here to avoid
             * e.g. two micro-flows that differ only in TTL from going to the
             * simulation stage? */
            setVportForLocalTraffic(pktCtx)
            processSimulationResult(pktCtx, simulatePacketIn(pktCtx))
        }
    }

    private def setVportForLocalTraffic(pktCtx: PacketContext): Unit = {
        val inPortNo = pktCtx.origMatch.getInputPortNumber
        val inPortId = dpState getVportForDpPortNumber Unsigned.unsign(inPortNo)
        pktCtx.inputPort = inPortId.orNull
    }

    /*
     * Take the outgoing filter for each port and apply it, checking for
     * Action.ACCEPT.
     *
     * Aux. to handlePacketToPortSet.
     */
    private def applyOutgoingFilter(pktCtx: PacketContext,
                                    tags: mutable.Set[FlowTag],
                                    localPorts: Seq[Port]): Urgent[Boolean] =
        applyOutboundFilters(localPorts, pktCtx.origMatch, tags) map {
            portNumbers =>
                val actions = towardsLocalDpPorts(portNumbers, tags)
                addTranslatedFlowForActions(pktCtx, actions, tags)
                true
        }

    /** The packet arrived on a tunnel but didn't match in the WFT. It's either
      * addressed (by the tunnel key) to a local PortSet or it was mistakenly
      * routed here. Map the tunnel key to a port set (through the
      * VirtualToPhysicalMapper).
      */
    private def handlePacketToPortSet(pktCtx: PacketContext)
    : Urgent[PipelinePath] = {
        log.debug("Packet {} from a tunnel port towards a port set", cookieStr)
        // We currently only handle packets ingressing on tunnel ports if they
        // have a tunnel key. If the tunnel key corresponds to a local virtual
        // port then the pre-installed flow rules should have matched the
        // packet. So we really only handle cases where the tunnel key exists
        // and corresponds to a port set.

        val req = PortSetForTunnelKeyRequest(pktCtx.origMatch.getTunnelID)
        val portSet = VirtualToPhysicalMapper expiringAsk req

        (portSet map {
            case null =>
                throw new Exception("null portSet")
            case pSet =>
                log.debug("tun => portSet: {}", pSet)
                // egress port filter simulation
                val tags = mutable.Set[FlowTag]()
                activePorts(pSet.localPorts, tags) flatMap {
                    applyOutgoingFilter(pktCtx, tags, _)
                }
        }) map {
            _ => PacketToPortSet
        }
    }

    private def doEgressPortSimulation(pktCtx: PacketContext) = {
        log.debug("Handling generated packet")
        processSimulationResult(pktCtx, runSimulation(pktCtx))
    }

    /*
     * Here we receive the result of the simulation, which can be either a
     * simulation that could complete with resources cached locally, or the
     * first incomplete future that was found in the way.
     *
     * This will enqueue the simulation for later processing whenever the future
     * completes, or proceed with the resulting actions if the result is
     * already computed.
     */
    def processSimulationResult(pktCtx: PacketContext,
                                result: Urgent[SimulationResult])
    : Urgent[PipelinePath] = {

        result flatMap {
            case AddVirtualWildcardFlow(flow, callbacks, tags) =>
                val urgent = addVirtualWildcardFlow(pktCtx, flow, callbacks, tags)
                if (urgent.notReady) {
                    log.debug("AddVirtualWildcardFlow, must be postponed, " +
                              "running callbacks")
                    // TODO: The packet context should be exposed to us so we
                    // can have a single runCallbacks here, rather than
                    // scattered everywhere a NotYet is generated.
                    runCallbacks(callbacks)
                }
                urgent
            case SendPacket(actions) =>
                sendPacket(pktCtx, actions)
            case NoOp =>
                addToActionsCacheAndInvalidate(pktCtx, Nil)
                Ready(true)
            case TemporaryDrop(tags, flowRemovalCallbacks) =>
                addTranslatedFlowForActions(pktCtx, Nil, tags,
                                            flowRemovalCallbacks,
                                            TEMPORARY_DROP_MILLIS, 0)
                Ready(true)
            case Drop(tags, flowRemovalCallbacks) =>
                addTranslatedFlowForActions(pktCtx, Nil, tags,
                                            flowRemovalCallbacks,
                                            0, IDLE_EXPIRATION_MILLIS)
                Ready(true)
        } map {
            _ => Simulation
        }
    }

    private def simulatePacketIn(pktCtx: PacketContext): Urgent[SimulationResult] =
        if (pktCtx.inputPort ne null) {
            val packet = pktCtx.packet
            system.eventStream.publish(
                PacketIn(pktCtx.origMatch.clone(), packet.getEthernet,
                    packet.getMatch, packet.getReason,
                    pktCtx.cookieOrEgressPort.left getOrElse 0))

            handleDHCP(pktCtx) flatMap {
                case true => Ready(NoOp)
                case false => runSimulation(pktCtx)
            }
        } else {
            Ready(TemporaryDrop(Set.empty, Nil))
        }

    def addVirtualWildcardFlow(pktCtx: PacketContext,
                               flow: WildcardFlow,
                               flowRemovalCallbacks: Seq[Callback0],
                               tags: ROSet[FlowTag]): Urgent[Boolean] = {
        translateVirtualWildcardFlow(flow, tags) map {
            case (finalFlow, finalTags) =>
                addTranslatedFlow(pktCtx, finalFlow, finalTags,
                                  flowRemovalCallbacks)
                true
        }
    }

    private def handleDHCP(pktCtx: PacketContext): Urgent[Boolean] = {
        def isUdpDhcpFlowKey(k: FlowKey): Boolean = k match {
            case udp: FlowKeyUDP => (udp.getUdpSrc == 68) && (udp.getUdpDst == 67)
            case _ => false
        }

        def payloadAs[T](pkt: IPacket)(implicit tag: ClassTag[T]): Option[T] = {
            val payload = pkt.getPayload
            if (tag.runtimeClass == payload.getClass)
                Some(payload.asInstanceOf[T])
            else
                None
        }

        if (pktCtx.packet.getMatch.getKeys.filter(isUdpDhcpFlowKey).isEmpty)
            false

        (for {
            ip4 <- payloadAs[IPv4](pktCtx.packet.getEthernet)
            udp <- payloadAs[UDP](ip4)
            dhcp <- payloadAs[DHCP](udp)
            if dhcp.getOpCode == DHCP.OPCODE_REQUEST
        } yield {
            processDhcpFuture(pktCtx, dhcp)
        }) getOrElse Ready(false)
    }

    private def processDhcpFuture(pktCtx: PacketContext,
                                  dhcp: DHCP): Urgent[Boolean] =
        VirtualTopologyActor.expiringAsk[Port](pktCtx.inputPort, log) map {
            processDhcp(pktCtx, _, dhcp, DatapathController.minMtu)
        }

    private def processDhcp(pktCtx: PacketContext, inPort: Port,
                            dhcp: DHCP, mtu: Short): Boolean = {
        val srcMac = pktCtx.packet.getEthernet.getSourceMACAddress
        val dhcpLogger = Logging.getLogger(system, classOf[DhcpImpl])
        val optMtu = Option(mtu)
        DhcpImpl(dataClient, inPort, dhcp, srcMac, optMtu, dhcpLogger) match {
            case Some(dhcpReply) =>
                log.debug(
                    "sending DHCP reply {} to port {}", dhcpReply, inPort.id)
                PacketsEntryPoint !
                    EmitGeneratedPacket(inPort.id, dhcpReply, pktCtx.flowCookie)
                true
            case None =>
                false
        }
    }

    private def sendPacket(pktCtx: PacketContext,
                           acts: List[FlowAction]): Urgent[Boolean] =
        if (null == acts || acts.isEmpty) {
            log.debug("Dropping {} {} without actions", cookieStr, pktCtx.packet)
            Ready(true)
        } else {
            log.debug("Sending {} {} with actions {}", cookieStr, pktCtx.packet, acts)
            val throwAwayTags = mutable.Set.empty[FlowTag]
            translateActions(acts, None, throwAwayTags, pktCtx.origMatch) map {
                actions =>
                    executePacket(pktCtx, actions)
                    true
            }
        }

    private def runCallbacks(callbacks: Iterable[Callback0]): Unit = {
        val iterator = callbacks.iterator
        while (iterator.hasNext) {
            iterator.next().call()
        }
    }

    private def addToActionsCacheAndInvalidate(pktCtx: PacketContext,
                                               actions: JList[FlowAction]): Unit = {
        val wm = pktCtx.packet.getMatch
        actionsCache.actions.put(wm, actions)
        actionsCache.pending(actionsCache.getSlot(cookieStr)) = wm
    }

    private def datapathConn(pktCtx: PacketContext) =
        dpConnPool.get(pktCtx.packet.getMatch.hashCode)
}
