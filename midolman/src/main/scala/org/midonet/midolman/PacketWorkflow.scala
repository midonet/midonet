/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.util.{List => JList}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.reflect.ClassTag

import akka.actor._
import akka.event.{Logging, LoggingAdapter}

import org.midonet.cluster.DataClient
import org.midonet.cluster.client.Port
import org.midonet.midolman.DeduplicationActor.ActionsCache
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.simulation.{Coordinator, PacketContext, DhcpImpl}
import org.midonet.midolman.topology.{VirtualTopologyActor, VirtualToPhysicalMapper, VxLanPortMapper}
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.{Packet, Datapath, Flow, FlowMatch}
import org.midonet.odp.flows.{FlowKey, FlowKeyUDP, FlowAction}
import org.midonet.packets._
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.sdn.flows.FlowTagger.{FlowTag, tagForDpPort}

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

    val log: LoggingAdapter = Logging.getLogger(system, this.getClass)

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
        val dpFlow = new Flow(pktCtx.packet.getMatch, wildFlow.getActions)
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
        if (pktCtx.packet.getReason == Packet.Reason.FlowActionUserspace)
            pktCtx.runFlowRemovedCallbacks()
        else if (areTagsValid(pktCtx))
            handleValidFlow(pktCtx, wildFlow)
        else
            handleObsoleteFlow(pktCtx, wildFlow)

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
                sendPacket(pktCtx, actions)
            case NoOp =>
                addToActionsCacheAndInvalidate(pktCtx, Nil)
                Ready(true)
            case TemporaryDrop =>
                addTranslatedFlowForActions(pktCtx, Nil,
                                            TEMPORARY_DROP_MILLIS, 0)
                Ready(true)
            case Drop =>
                addTranslatedFlowForActions(pktCtx, Nil,
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
            Ready(TemporaryDrop)
        }

    def addVirtualWildcardFlow(pktCtx: PacketContext,
                               flow: WildcardFlow): Urgent[_] = {
        translateVirtualWildcardFlow(pktCtx, flow) map {
            addTranslatedFlow(pktCtx, _)
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
