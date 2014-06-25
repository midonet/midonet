/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.lang.{Integer => JInteger}
import java.util.{UUID, List => JList}
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
import org.midonet.midolman.simulation.DhcpImpl
import org.midonet.midolman.topology.VxLanPortMapper
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.{Packet, Datapath, Flow, FlowMatch}
import org.midonet.odp.flows.{FlowKey, FlowKeyUDP, FlowAction}
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.packets._
import org.midonet.sdn.flows.VirtualActions.{VirtualFlowAction, FlowActionOutputToVrnPortSet}
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.util.functors.Callback0
import org.midonet.midolman.DeduplicationActor.ActionsCache

trait PacketHandler {

    import PacketWorkflow.PipelinePath

    def start(): Urgent[PipelinePath]
    def drop(): Unit

    val packet: Packet
    val cookieOrEgressPort: Either[Int, UUID]
    val cookie: Option[Int]
    val egressPort: Option[UUID]
    val cookieStr: String
    def idle: Boolean
    def runs: Int

    override def toString = s"PacketWorkflow[$cookieStr]"
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
        val tags: ROSet[Any]
        val flowRemovalCallbacks: Seq[Callback0]
    }

    case class Drop(tags: ROSet[Any],
                    flowRemovalCallbacks: Seq[Callback0])
            extends DropSimulationResult

    case class TemporaryDrop(tags: ROSet[Any],
                             flowRemovalCallbacks: Seq[Callback0])
            extends DropSimulationResult

    case class SendPacket(actions: List[FlowAction]) extends SimulationResult

    case class AddVirtualWildcardFlow(flow: WildcardFlow,
                                      flowRemovalCallbacks: Seq[Callback0],
                                      tags: ROSet[Any]) extends SimulationResult

    sealed trait PipelinePath
    case object WildcardTableHit extends PipelinePath
    case object PacketToPortSet extends PipelinePath
    case object Simulation extends PipelinePath
    case object Error extends PipelinePath

    def apply(dpCon: OvsDatapathConnection,
              dpState: DatapathState,
              dp: Datapath,
              dataClient: DataClient,
              actionsCache: ActionsCache,
              packet: Packet,
              wcMatch: WildcardMatch,
              cookieOrEgressPort: Either[Int, UUID],
              parentCookie: Option[Int])
             (runSim: => Urgent[SimulationResult])
             (implicit system: ActorSystem): PacketHandler =
        new PacketWorkflow(dpCon, dpState, dp, dataClient, actionsCache, packet,
                           wcMatch, cookieOrEgressPort, parentCookie) {
            def runSimulation() = runSim
        }
}

abstract class PacketWorkflow(protected val datapathConnection: OvsDatapathConnection,
                              protected val dpState: DatapathState,
                              val datapath: Datapath,
                              val dataClient: DataClient,
                              val actionsCache: ActionsCache,
                              val packet: Packet,
                              val wcMatch: WildcardMatch,
                              val cookieOrEgressPort: Either[Int, UUID],
                              val parentCookie: Option[Int])
                             (implicit val system: ActorSystem)
        extends FlowTranslator with PacketHandler {

    import PacketWorkflow._
    import DeduplicationActor._
    import FlowController.{AddWildcardFlow, FlowAdded}
    import VirtualToPhysicalMapper.PortSetForTunnelKeyRequest

    def runSimulation(): Urgent[SimulationResult]

    val ERROR_CONDITION_HARD_EXPIRATION = 10000

    var idle = true
    var runs = 0 // how many times it has been started

    override val cookie = cookieOrEgressPort match {
        case Left(c) => Some(c)
        case Right(_) => None
    }

    override val egressPort = cookieOrEgressPort match {
        case Right(e) => Some(e)
        case Left(_) => None
    }

    implicit val requestReplyTimeout = new Timeout(5, TimeUnit.SECONDS)

    val log: LoggingAdapter = Logging.getLogger(system, this.getClass)
    val cookieStr: String = (if (cookie != None) "[cookie:" else "[genPkt:") +
                        cookie.getOrElse(parentCookie.getOrElse("No Cookie")) +
                        "]"

    val lastInvalidation = FlowController.lastInvalidationEvent

    override def start(): Urgent[PipelinePath] = {
        idle = false
        runs += 1
        log.debug("Initiating processing of packet {}, attempt: {}",
                  cookieStr, runs)
        (cookie match {
            case Some(_) => handlePacketWithCookie()
            case None => doEgressPortSimulation()
        }) match {
            case n@NotYet(_) => idle = true; n
            case r => r
        }
    }

    override def drop() {
        idle = false
        val wildFlow = WildcardFlow(wcmatch = wcMatch,
            hardExpirationMillis = ERROR_CONDITION_HARD_EXPIRATION)
        addTranslatedFlow(wildFlow, Set.empty, Nil)
    }

    private def notifyFlowAdded(flow: Flow,
                                newWildFlow: Option[WildcardFlow],
                                tags: ROSet[Any],
                                removalCallbacks: Seq[Callback0]) {
        log.debug("Successfully created flow for {}", cookieStr)
        newWildFlow match {
            case None =>
                FlowController ! FlowAdded(flow, wcMatch)
                addToActionsCacheAndInvalidate(flow.getActions)
            case Some(wf) =>
                FlowController ! AddWildcardFlow(wf, flow, removalCallbacks, tags,
                    lastInvalidation, packet.getMatch, actionsCache.pending,
                    actionsCache.getSlot(cookieStr))
                actionsCache.actions.put(packet.getMatch, flow.getActions)
        }
    }

    private def createFlow(wildFlow: WildcardFlow,
                           newWildFlow: Option[WildcardFlow] = None,
                           tags: ROSet[Any] = Set.empty,
                           removalCallbacks: Seq[Callback0] = Nil) {
        log.debug("Creating flow from {} for {}", wildFlow, cookieStr)
        val dpFlow = new Flow().setActions(wildFlow.getActions)
                               .setMatch(packet.getMatch)
        try {
            datapathConnection.flowsCreate(datapath, dpFlow)
            notifyFlowAdded(dpFlow, newWildFlow, tags, removalCallbacks)
        } catch {
            case e: NetlinkException =>
                log.info("{} Failed to add flow packet: {}", cookieStr, e)
                addToActionsCacheAndInvalidate(dpFlow.getActions)
        }
    }

    private def addTranslatedFlow(wildFlow: WildcardFlow,
                                  tags: ROSet[Any],
                                  removalCallbacks: Seq[Callback0]) {
        if (packet.getReason == Packet.Reason.FlowActionUserspace)
            runCallbacks(removalCallbacks)
        else if (areTagsValid(tags))
            handleValidFlow(wildFlow, tags, removalCallbacks)
        else
            handleObsoleteFlow(wildFlow, removalCallbacks)

        executePacket(wildFlow.getActions)
    }

    def areTagsValid(tags: ROSet[Any]) =
        FlowController.isTagSetStillValid(lastInvalidation, tags)

    private def handleObsoleteFlow(wildFlow: WildcardFlow,
                                   removalCallbacks: Seq[Callback0]) {
        log.debug("Skipping creation of obsolete flow {} for {}",
                  cookieStr, wildFlow.getMatch)
        if (cookie.isDefined)
            addToActionsCacheAndInvalidate(wildFlow.getActions)
        runCallbacks(removalCallbacks)
    }

    private def handleValidFlow(wildFlow: WildcardFlow,
                                tags: ROSet[Any],
                                removalCallbacks: Seq[Callback0]) {
        cookie match {
            case Some(_) if wildFlow.wcmatch.userspaceFieldsSeen =>
                log.debug("Adding wildcard flow {} for match with userspace " +
                          "only fields, without a datapath flow", wildFlow)
                FlowController ! AddWildcardFlow(wildFlow, null, removalCallbacks,
                    tags, lastInvalidation, packet.getMatch, actionsCache.pending,
                    actionsCache.getSlot(cookieStr))
                actionsCache.actions.put(packet.getMatch, wildFlow.actions)

            case Some(_) =>
                createFlow(wildFlow, Some(wildFlow), tags, removalCallbacks)

            case None =>
                log.debug("Only adding wildcard flow {} for {}",
                          wildFlow, cookieStr)
                FlowController ! AddWildcardFlow(wildFlow, null, removalCallbacks,
                                                 tags, lastInvalidation)
        }
    }

    private def addTranslatedFlowForActions(
                            actions: Seq[FlowAction],
                            tags: ROSet[Any] = Set.empty,
                            removalCallbacks: Seq[Callback0] = Nil,
                            hardExpirationMillis: Int = 0,
                            idleExpirationMillis: Int = IDLE_EXPIRATION_MILLIS) {

        val wildFlow = WildcardFlow(
            wcmatch = wcMatch,
            hardExpirationMillis = hardExpirationMillis,
            idleExpirationMillis = idleExpirationMillis,
            actions =  actions.toList,
            priority = 0)

        addTranslatedFlow(wildFlow, tags, removalCallbacks)
    }

    def executePacket(actions: Seq[FlowAction]) {
        val finalActions = if (packet.getMatch.isUserSpaceOnly) {
            UserspaceFlowActionTranslator.translate(packet, actions.asJava)
        } else {
            actions.asJava
        }

        if (finalActions.isEmpty) {
            log.debug("Dropping packet {}", cookieStr)
            return
        }

        try {
            log.debug("Executing packet {}", cookieStr)
            datapathConnection.packetsExecute(datapath, packet, finalActions)
        } catch {
            case e: NetlinkException =>
                log.info("{} Failed to execute packet: {}", cookieStr, e)
        }
    }

    private def handlePacketWithCookie(): Urgent[PipelinePath] = {
        if (wcMatch.getInputPortNumber == null) {
            log.error("PacketIn had no inPort number {}.", cookieStr)
            return Ready(Error)
        }
        if (packet.getReason == Packet.Reason.FlowActionUserspace) {
            processSimulationResult(simulatePacketIn(vportForLocalTraffic))
        } else {
            FlowController.queryWildcardFlowTable(wcMatch) match {
                case Some(wildflow) =>
                    handleWildcardTableMatch(wildflow)
                    Ready(WildcardTableHit)
                case None =>
                    handleWildcardTableMiss()
            }
        }
    }

    def handleWildcardTableMatch(wildFlow: WildcardFlow) {
        log.debug("Packet {} matched a wildcard flow", cookieStr)
        if (wildFlow.wcmatch.userspaceFieldsSeen) {
            log.debug("no datapath flow for match {} with userspace only fields",
                      wildFlow.wcmatch)
            addToActionsCacheAndInvalidate(wildFlow.getActions)
        } else {
            createFlow(wildFlow)
        }

        executePacket(wildFlow.getActions)
    }

    private def handleWildcardTableMiss(): Urgent[PipelinePath] = {
        /** If the FlowMatch indicates that the packet came from a tunnel port,
         *  we assume here there are only two possible situations: 1) the tunnel
         *  port type is gre, in which case we are dealing with host2host
         *  tunnelled traffic; 2) the tunnel port type is vxlan, in which case
         *  we are dealing with vtep to midolman traffic. In the future, using
         *  vxlan encap for host2host tunnelling will break this assumption. */
        if (wcMatch.isFromTunnel) {
            if (dpState isOverlayTunnellingPort wcMatch.getInputPortNumber) {
                handlePacketToPortSet()
            } else {
                val uuidOpt = VxLanPortMapper uuidOf wcMatch.getTunnelID.toInt
                processSimulationResult(simulatePacketIn(uuidOpt))
            }
        } else {
            /* QUESTION: do we need another de-duplication stage here to avoid
             * e.g. two micro-flows that differ only in TTL from going to the
             * simulation stage? */
            processSimulationResult(simulatePacketIn(vportForLocalTraffic))
        }
    }

    private def vportForLocalTraffic = {
        val inPortNo = wcMatch.getInputPortNumber
        dpState getVportForDpPortNumber Unsigned.unsign(inPortNo)
    }

    /*
     * Take the outgoing filter for each port and apply it, checking for
     * Action.ACCEPT.
     *
     * Aux. to handlePacketToPortSet.
     */
    private def applyOutgoingFilter(wMatch: WildcardMatch,
                                    tags: mutable.Set[Any],
                                    localPorts: Seq[Port]): Urgent[Boolean] =
        applyOutboundFilters(localPorts, wMatch, tags) map {
            portNumbers =>
                val actions = towardsLocalDpPorts(portNumbers, tags)
                addTranslatedFlowForActions(actions, tags)
                true
        }

    /** The packet arrived on a tunnel but didn't match in the WFT. It's either
      * addressed (by the tunnel key) to a local PortSet or it was mistakenly
      * routed here. Map the tunnel key to a port set (through the
      * VirtualToPhysicalMapper).
      */
    private def handlePacketToPortSet(): Urgent[PipelinePath] = {
        log.debug("Packet {} from a tunnel port towards a port set", cookieStr)
        // We currently only handle packets ingressing on tunnel ports if they
        // have a tunnel key. If the tunnel key corresponds to a local virtual
        // port then the pre-installed flow rules should have matched the
        // packet. So we really only handle cases where the tunnel key exists
        // and corresponds to a port set.

        val req = PortSetForTunnelKeyRequest(wcMatch.getTunnelID)
        val portSet = VirtualToPhysicalMapper expiringAsk req

        (portSet map {
            case null =>
                throw new Exception("null portSet")
            case pSet =>
                log.debug("tun => portSet: {}", pSet)
                // egress port filter simulation
                val tags = mutable.Set[Any]()
                activePorts(pSet.localPorts, tags) flatMap {
                    applyOutgoingFilter(wcMatch, tags, _)
                }
        }) map {
            _ => PacketToPortSet
        }
    }

    private def doEgressPortSimulation() = {
        log.debug("Handling generated packet")
        processSimulationResult(runSimulation())
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
    def processSimulationResult(result: Urgent[SimulationResult]): Urgent[PipelinePath] = {

        result flatMap {
            case AddVirtualWildcardFlow(flow, callbacks, tags) =>
                val urgent = addVirtualWildcardFlow(flow, callbacks, tags)
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
                sendPacket(actions)
            case NoOp =>
                addToActionsCacheAndInvalidate(Nil)
                Ready(true)
            case TemporaryDrop(tags, flowRemovalCallbacks) =>
                addTranslatedFlowForActions(Nil, tags, flowRemovalCallbacks,
                                            TEMPORARY_DROP_MILLIS, 0)
                Ready(true)
            case Drop(tags, flowRemovalCallbacks) =>
                addTranslatedFlowForActions(Nil, tags, flowRemovalCallbacks,
                                            0, IDLE_EXPIRATION_MILLIS)
                Ready(true)
        } map {
            _ => Simulation
        }
    }

    private def simulatePacketIn(vport: Option[UUID]): Urgent[SimulationResult] =
        vport match {
            case Some(vportId) =>
                wcMatch.setInputPortUUID(vportId)

                system.eventStream.publish(
                    PacketIn(wcMatch.clone(), packet.getPacket, packet.getMatch,
                        packet.getReason, cookie getOrElse 0))

                handleDHCP(vportId) flatMap {
                    case true => Ready(NoOp)
                    case false => runSimulation()
                }

            case None =>
                Ready(TemporaryDrop(Set.empty, Nil))
        }

    def addVirtualWildcardFlow(flow: WildcardFlow,
                               flowRemovalCallbacks: Seq[Callback0],
                               tags: ROSet[Any]): Urgent[Boolean] = {
        translateVirtualWildcardFlow(flow, tags) map {
            case (finalFlow, finalTags) =>
                addTranslatedFlow(finalFlow, finalTags, flowRemovalCallbacks)
                true
        }
    }

    private def handleDHCP(inPortId: UUID): Urgent[Boolean] = {
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

        if (packet.getMatch.getKeys.filter(isUdpDhcpFlowKey).isEmpty)
            false

        (for {
            ip4 <- payloadAs[IPv4](packet.getPacket)
            udp <- payloadAs[UDP](ip4)
            dhcp <- payloadAs[DHCP](udp)
            if dhcp.getOpCode == DHCP.OPCODE_REQUEST
        } yield {
            processDhcpFuture(inPortId, dhcp)
        }) getOrElse Ready(false)
    }

    private def processDhcpFuture(inPortId: UUID, dhcp: DHCP): Urgent[Boolean] =
        VirtualTopologyActor.expiringAsk[Port](inPortId, log) map {
            port => processDhcp(port, dhcp, DatapathController.minMtu)
        }

    private def processDhcp(inPort: Port, dhcp: DHCP, mtu: Short) = {
        val srcMac = packet.getPacket.getSourceMACAddress
        val dhcpLogger = Logging.getLogger(system, classOf[DhcpImpl])
        val optMtu = Option(mtu)
        DhcpImpl(dataClient, inPort, dhcp, srcMac, optMtu, dhcpLogger) match {
            case Some(dhcpReply) =>
                log.debug(
                    "sending DHCP reply {} to port {}", dhcpReply, inPort.id)
                PacketsEntryPoint !
                    EmitGeneratedPacket(inPort.id, dhcpReply, cookie)
                true
            case None =>
                false
        }
    }

    private def sendPacket(acts: List[FlowAction]): Urgent[Boolean] =
        if (null == acts || acts.isEmpty) {
            log.debug("Dropping {} {} without actions", cookieStr, packet)
            Ready(true)
        } else {
            log.debug("Sending {} {} with actions {}", cookieStr, packet, acts)
            val throwAwayTags = mutable.Set.empty[Any]
            translateActions(acts, None, throwAwayTags, wcMatch) map {
                actions =>
                    executePacket(actions)
                    true
            }
        }

    private def runCallbacks(callbacks: Iterable[Callback0]) {
        val iterator = callbacks.iterator
        while (iterator.hasNext) {
            iterator.next().call()
        }
    }

    private def addToActionsCacheAndInvalidate(actions: JList[FlowAction]): Unit = {
        val wm = packet.getMatch
        actionsCache.actions.put(wm, actions)
        actionsCache.pending(actionsCache.getSlot(cookieStr)) = wm
    }
}
