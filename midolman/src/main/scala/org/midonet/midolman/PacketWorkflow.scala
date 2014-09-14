/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.util.{List => JList}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

import akka.actor._
import akka.event.{Logging, LoggingAdapter}

import org.midonet.cluster.DataClient
import org.midonet.cluster.client.Port
import org.midonet.midolman.DeduplicationActor.ActionsCache
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.simulation.{Coordinator, PacketContext, DhcpImpl}
import org.midonet.midolman.state.FlowStateReplicator
import org.midonet.midolman.topology.{VirtualTopologyActor, VirtualToPhysicalMapper, VxLanPortMapper}
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.sdn.flows.FlowTagger.tagForDpPort

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

    case object Drop extends SimulationResult

    case object TemporaryDrop extends SimulationResult

    case class SendPacket(actions: List[FlowAction]) extends SimulationResult

    case class AddVirtualWildcardFlow(flow: WildcardFlow) extends SimulationResult

    sealed trait PipelinePath
    case object WildcardTableHit extends PipelinePath
    case object PacketToPortSet extends PipelinePath
    case object StateMessage extends PipelinePath
    case object Simulation extends PipelinePath
    case object Error extends PipelinePath
}

class PacketWorkflow(protected val dpState: DatapathState,
                     val datapath: Datapath,
                     val dataClient: DataClient,
                     val dpConnPool: DatapathConnectionPool,
                     val actionsCache: ActionsCache,
                     val replicator: FlowStateReplicator)
                    (implicit val system: ActorSystem)
        extends FlowTranslator with PacketHandler {

    import PacketWorkflow._
    import DeduplicationActor._
    import FlowController.{AddWildcardFlow, FlowAdded}
    import VirtualToPhysicalMapper.PortSetForTunnelKeyRequest

    val ERROR_CONDITION_HARD_EXPIRATION = 10000

    val log: LoggingAdapter = Logging.getLogger(system, classOf[PacketWorkflow])

    override def start(pktCtx: PacketContext): Urgent[PipelinePath] = {
        pktCtx.prepareForSimulation(FlowController.lastInvalidationEvent)
        log.debug("Initiating processing of packet {}, attempt: {}",
                  pktCtx.cookieStr, pktCtx.runs)
        val res = if (pktCtx.ingressed)
                    handlePacketWithCookie(pktCtx)
                  else
                    doEgressPortSimulation(pktCtx)
        res
    }

    override def drop(pktCtx: PacketContext) {
        pktCtx.prepareForDrop(FlowController.lastInvalidationEvent)
        val wildFlow = WildcardFlow(wcmatch = pktCtx.origMatch,
            hardExpirationMillis = ERROR_CONDITION_HARD_EXPIRATION)
        addTranslatedFlow(pktCtx, wildFlow)
    }

    def runSimulation(pktCtx: PacketContext): Urgent[SimulationResult] =
        new Coordinator(pktCtx).simulate()

    private def notifyFlowAdded(pktCtx: PacketContext,
                                flow: Flow,
                                newWildFlow: Option[WildcardFlow]) {
        log.debug("Successfully created flow for {}", pktCtx.cookieStr)
        newWildFlow match {
            case None =>
                FlowController ! FlowAdded(flow, pktCtx.origMatch)
                addToActionsCacheAndInvalidate(pktCtx, flow.getActions)
            case Some(wf) =>
                FlowController ! AddWildcardFlow(wf, flow,
                                pktCtx.flowRemovedCallbacks, pktCtx.flowTags,
                                pktCtx.lastInvalidation, pktCtx.packet.getMatch,
                                actionsCache.pending,
                                actionsCache.getSlot(pktCtx.cookieStr))
                actionsCache.actions.put(pktCtx.packet.getMatch, flow.getActions)
        }
    }

    private def createFlow(pktCtx: PacketContext,
                           wildFlow: WildcardFlow,
                           newWildFlow: Option[WildcardFlow] = None) {
        log.debug("Creating flow from {} for {}", wildFlow, pktCtx.cookieStr)

        val flowMatch = pktCtx.packet.getMatch
        val providedKeys = flowMatch.getKeys.asScala.toList
        val hasTcpFlags = providedKeys.exists({ x => x.isInstanceOf[FlowKeyTCPFlags] })

        val flowMask = new FlowMask()
        if (hasTcpFlags) {
            // wildcard the TCP flags
            // TODO: this will change in the future: we'll use the wildcard match
            //       until then when we are smarter, we must set exact matches
            //       for everything up to the TCPFlags level... [alvaro]
            flowMask.addKey(FlowKeys.priority(FlowMask.PRIO_EXACT)).
                     addKey(FlowKeys.inPort(FlowMask.INPORT_EXACT)).
                     addKey(FlowKeys.ethernet(FlowMask.ETHER_EXACT,
                                              FlowMask.ETHER_EXACT)).
                     addKey(FlowKeys.etherType(FlowMask.ETHERTYPE_EXACT)).
                     addKey(FlowKeys.ipv4(FlowMask.IP_EXACT, FlowMask.IP_EXACT,
                            FlowMask.BYTE_EXACT, FlowMask.BYTE_EXACT,
                            FlowMask.BYTE_EXACT, FlowMask.BYTE_EXACT)).
                     addKey(FlowKeys.tcp(FlowMask.TCP_EXACT, FlowMask.TCP_EXACT)).
                     addKey(FlowKeys.tcpFlags(FlowMask.TCPFLAGS_ANY))
        }

        val dpFlow = new Flow(flowMatch, flowMask, wildFlow.getActions)
        try {
            datapathConn(pktCtx).flowsCreate(datapath, dpFlow)
            notifyFlowAdded(pktCtx, dpFlow, newWildFlow)
        } catch {
            case e: NetlinkException =>
                log.info("{} Failed to add flow packet: {}", pktCtx.cookieStr, e)
                addToActionsCacheAndInvalidate(pktCtx, dpFlow.getActions)
        }
    }

    private def addTranslatedFlow(pktCtx: PacketContext,
                                  wildFlow: WildcardFlow): Unit = {
        if (pktCtx.packet.getReason == Packet.Reason.FlowActionUserspace) {
            pktCtx.runFlowRemovedCallbacks()
        } else if (areTagsValid(pktCtx)) {
            // ApplyState needs to happen before we add the wildcard flow
            // because it adds callbacks to the PacketContext and it can also
            // result in a NotYet exception being thrown.
            applyState(pktCtx, wildFlow.getActions)
            handleValidFlow(pktCtx, wildFlow)
        } else {
            applyObsoleteState(pktCtx, wildFlow.getActions)
            handleObsoleteFlow(pktCtx, wildFlow)
        }

        executePacket(pktCtx, wildFlow.getActions)
    }

    def areTagsValid(pktCtx: PacketContext) =
        FlowController.isTagSetStillValid(pktCtx.lastInvalidation, pktCtx.flowTags)

    private def handleObsoleteFlow(pktCtx: PacketContext,
                                   wildFlow: WildcardFlow) {
        log.debug("Skipping creation of obsolete flow {} for {}",
                  pktCtx.cookieStr, wildFlow.getMatch)
        if (pktCtx.ingressed)
            addToActionsCacheAndInvalidate(pktCtx, wildFlow.getActions)
        pktCtx.runFlowRemovedCallbacks()
    }

    private def handleValidFlow(pktCtx: PacketContext,
                                wildFlow: WildcardFlow) {
        if (pktCtx.isGenerated) {
            log.debug("Only adding wildcard flow {} for {}",
                      wildFlow, pktCtx.cookieStr)
            FlowController ! AddWildcardFlow(wildFlow, null, pktCtx.flowRemovedCallbacks,
                                             pktCtx.flowTags, pktCtx.lastInvalidation)
        } else if (wildFlow.wcmatch.userspaceFieldsSeen) {
            log.debug("Adding wildcard flow {} for match with userspace " +
                      "only fields, without a datapath flow", wildFlow)
            FlowController ! AddWildcardFlow(wildFlow, null, pktCtx.flowRemovedCallbacks,
                pktCtx.flowTags, pktCtx.lastInvalidation, pktCtx.packet.getMatch,
                actionsCache.pending, actionsCache.getSlot(pktCtx.cookieStr))
            actionsCache.actions.put(pktCtx.packet.getMatch, wildFlow.actions)
        } else {
            createFlow(pktCtx, wildFlow, Some(wildFlow))
        }
    }

    private def addTranslatedFlowForActions(
                            pktCtx: PacketContext,
                            actions: Seq[FlowAction],
                            hardExpirationMillis: Int = 0,
                            idleExpirationMillis: Int = IDLE_EXPIRATION_MILLIS) {

        val wildFlow = WildcardFlow(
            wcmatch = pktCtx.origMatch,
            hardExpirationMillis = hardExpirationMillis,
            idleExpirationMillis = idleExpirationMillis,
            actions =  actions.toList,
            priority = 0)

        addTranslatedFlow(pktCtx, wildFlow)
    }

    def executePacket(pktCtx: PacketContext, actions: Seq[FlowAction]): Unit = {
        if (actions.isEmpty) {
            log.debug("Dropping packet {}", pktCtx.cookieStr)
            return
        }

        try {
            log.debug("Executing packet {}", pktCtx.cookieStr)
            datapathConn(pktCtx).packetsExecute(datapath, pktCtx.packet, actions)
        } catch {
            case e: NetlinkException =>
                log.info("{} Failed to execute packet: {}", pktCtx.cookieStr, e)
        }
    }

    def applyState(pktCtx: PacketContext, actions: Seq[FlowAction]): Unit =
        if (!actions.isEmpty) {
            log.debug("{} Applying connection state", pktCtx.cookieStr)
            val outPort = pktCtx.outPortId
            replicator.accumulateNewKeys(pktCtx.state.conntrackTx,
                                         pktCtx.state.natTx,
                                         pktCtx.inputPort,
                                         if (pktCtx.toPortSet) null else outPort,
                                         if (pktCtx.toPortSet) outPort else null,
                                         pktCtx.flowTags,
                                         pktCtx.flowRemovedCallbacks)
            replicator.pushState(datapathConn(pktCtx))
            pktCtx.state.conntrackTx.commit()
            pktCtx.state.natTx.commit()
    }

    def applyObsoleteState(pktCtx: PacketContext, actions: Seq[FlowAction]): Unit =
        if (!actions.isEmpty) {
            // We only add this state to the local tables, which will eventually
            // idle out. This means we will keep the state a bit longer, allowing
            // it to be refreshed.
            log.debug("{} Applying obsolete connection state", pktCtx.cookieStr)
            pktCtx.state.conntrackTx.commit()
            pktCtx.state.natTx.commit()
        }

    private def handlePacketWithCookie(pktCtx: PacketContext)
    : Urgent[PipelinePath] = {
        if (pktCtx.origMatch.getInputPortNumber eq null) {
            log.error("PacketIn had no inPort number {}", pktCtx.cookieStr)
            return Ready(Error)
        }

        pktCtx.flowTags.add(tagForDpPort(pktCtx.origMatch.getInputPortNumber.toShort))

        if (pktCtx.packet.getReason == Packet.Reason.FlowActionUserspace) {
            setVportForLocalTraffic(pktCtx)
            processSimulationResult(pktCtx, simulatePacketIn(pktCtx))
        } else {
            FlowController.queryWildcardFlowTable(pktCtx.origMatch) match {
                case Some(wildflow) =>
                    log.debug("{} - {} hit the WildcardFlowTable", pktCtx.cookieStr,
                              pktCtx.origMatch)
                    handleWildcardTableMatch(pktCtx, wildflow)
                    Ready(WildcardTableHit)
                case None =>
                    log.debug("{} - {} missed the WildcardFlowTable", pktCtx.cookieStr,
                              pktCtx.origMatch)
                    handleWildcardTableMiss(pktCtx)
            }
        }
    }

    def handleWildcardTableMatch(pktCtx: PacketContext,
                                 wildFlow: WildcardFlow): Unit = {
        log.debug("Packet {} matched a wildcard flow", pktCtx.cookieStr)
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
            if (pktCtx.isStateMessage) {
                handleStateMessage(pktCtx)
            } else if (dpState isOverlayTunnellingPort wcMatch.getInputPortNumber) {
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
                                    localPorts: Seq[Port]): Urgent[Boolean] =
        applyOutboundFilters(localPorts, pktCtx) map {
            portNumbers =>
                val actions = towardsLocalDpPorts(portNumbers, pktCtx.flowTags)
                addTranslatedFlowForActions(pktCtx, actions)
                true
        }

    /** The packet arrived on a tunnel but didn't match in the WFT. It's either
      * addressed (by the tunnel key) to a local PortSet or it was mistakenly
      * routed here. Map the tunnel key to a port set (through the
      * VirtualToPhysicalMapper).
      */
    private def handlePacketToPortSet(pktCtx: PacketContext)
    : Urgent[PipelinePath] = {
        log.debug("Packet {} from a tunnel port towards a port set", pktCtx.cookieStr)
        // We currently only handle packets ingressing on tunnel ports if they
        // have a tunnel key. If the tunnel key corresponds to a local virtual
        // port then the pre-installed flow rules should have matched the
        // packet. So we really only handle cases where the tunnel key exists
        // and corresponds to a port set.

        val req = PortSetForTunnelKeyRequest(pktCtx.origMatch.getTunnelID)
        val portSet = VirtualToPhysicalMapper expiringAsk req
        portSet map {
            case null =>
                throw new Exception("null portSet")
            case pSet =>
                log.debug("tun => portSet: {}", pSet)
                // egress port filter simulation
                activePorts(pSet.localPorts, pktCtx.flowTags) flatMap {
                    applyOutgoingFilter(pktCtx, _)
                }
                PacketToPortSet
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
            case AddVirtualWildcardFlow(flow) =>
                val urgent = addVirtualWildcardFlow(pktCtx, flow)
                if (urgent.notReady) {
                    log.debug("AddVirtualWildcardFlow, must be postponed, " +
                              "running callbacks")
                }
                urgent
            case SendPacket(actions) =>
                pktCtx.runFlowRemovedCallbacks()
                sendPacket(pktCtx, actions)
            case NoOp =>
                pktCtx.runFlowRemovedCallbacks()
                addToActionsCacheAndInvalidate(pktCtx, Nil)
                Ready(null)
            case TemporaryDrop =>
                pktCtx.clearFlowTags()
                addTranslatedFlowForActions(pktCtx, Nil,
                                            TEMPORARY_DROP_MILLIS, 0)
                Ready(null)
            case Drop =>
                addTranslatedFlowForActions(pktCtx, Nil,
                                            0, IDLE_EXPIRATION_MILLIS)
                Ready(null)
        } map { _ => Simulation }
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
            Ready(TemporaryDrop)
        }

    def addVirtualWildcardFlow(pktCtx: PacketContext,
                               flow: WildcardFlow): Urgent[_] = {
        translateVirtualWildcardFlow(pktCtx, flow) map {
            addTranslatedFlow(pktCtx, _)
        }
    }

    private def handleStateMessage(pktCtx: PacketContext): Urgent[PipelinePath] = {
        log.debug("Accepting a state push message")
        replicator.accept(pktCtx.ethernet)
        Ready(StateMessage)
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
            return Ready(false)

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
                           acts: List[FlowAction]): Urgent[_] = {
        log.debug("Sending {} {} with actions {}", pktCtx.cookieStr, pktCtx.packet, acts)
        translateActions(pktCtx, acts) map { executePacket(pktCtx, _) }
    }

    private def addToActionsCacheAndInvalidate(pktCtx: PacketContext,
                                               actions: JList[FlowAction]): Unit = {
        val wm = pktCtx.packet.getMatch
        actionsCache.actions.put(wm, actions)
        actionsCache.pending(actionsCache.getSlot(pktCtx.cookieStr)) = wm
    }

    private def datapathConn(pktCtx: PacketContext) =
        dpConnPool.get(pktCtx.packet.getMatch.hashCode)
}
