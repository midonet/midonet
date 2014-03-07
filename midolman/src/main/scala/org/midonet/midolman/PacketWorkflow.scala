/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.lang.{Integer => JInteger}
import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.{Set => ROSet, mutable}
import scala.concurrent._
import scala.reflect.ClassTag
import scala.util.{Success, Failure}

import akka.actor._
import akka.event.{Logging, LoggingAdapter}
import akka.util.Timeout

import org.midonet.cluster.DataClient
import org.midonet.cluster.client.Port
import org.midonet.midolman.simulation.DhcpImpl
import org.midonet.midolman.topology.FlowTagger
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.{Packet, Datapath, Flow, FlowMatch}
import org.midonet.odp.flows.{FlowKey, FlowKeyUDP, FlowAction}
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.packets._
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPortSet
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.util.functors.Callback0

trait PacketHandler {

    import PacketWorkflow.PipelinePath

    def start(): Future[PipelinePath]

    val packet: Packet
    val cookieOrEgressPort: Either[Int, UUID]
    val cookie: Option[Int]
    val egressPort: Option[UUID]
    val cookieStr: String
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
        val flowRemovalCallbacks: ROSet[Callback0]
    }

    case class Drop(tags: ROSet[Any],
                    flowRemovalCallbacks: ROSet[Callback0])
            extends DropSimulationResult

    case class TemporaryDrop(tags: ROSet[Any],
                             flowRemovalCallbacks: ROSet[Callback0])
            extends DropSimulationResult

    case class SendPacket(actions: List[FlowAction]) extends SimulationResult

    case class AddVirtualWildcardFlow(flow: WildcardFlow,
                                      flowRemovalCallbacks: ROSet[Callback0],
                                      tags: ROSet[Any]) extends SimulationResult

    sealed trait PipelinePath
    case object WildcardTableHit extends PipelinePath
    case object PacketToPortSet extends PipelinePath
    case object Simulation extends PipelinePath

    def apply(deduplicator: ActorRef,
              dpCon: OvsDatapathConnection,
              dpState: DatapathState,
              dp: Datapath,
              dataClient: DataClient,
              packet: Packet,
              wcMatch: WildcardMatch,
              cookieOrEgressPort: Either[Int, UUID],
              parentCookie: Option[Int])
             (runSim: => Future[SimulationResult])
             (implicit system: ActorSystem): PacketHandler =
        new PacketWorkflow(deduplicator, dpCon, dpState, dp, dataClient, packet,
                           wcMatch, cookieOrEgressPort, parentCookie) {
            def runSimulation() = runSim
        }
}

abstract class PacketWorkflow(val deduplicator: ActorRef,
                              protected val datapathConnection: OvsDatapathConnection,
                              protected val dpState: DatapathState,
                              val datapath: Datapath,
                              val dataClient: DataClient,
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

    def runSimulation(): Future[SimulationResult]

    val ERROR_CONDITION_HARD_EXPIRATION = 10000

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

    override val cookieStr: String =
        (if (cookie != None) "[cookie:" else "[genPkt:") +
        cookie.getOrElse(parentCookie.getOrElse("No Cookie")) + "]"

    val lastInvalidation = FlowController.lastInvalidationEvent

    override def start(): Future[PipelinePath] = {
        log.debug("Initiating processing of packet {}", cookieStr)
        cookie match {
            case Some(_) => handlePacketWithCookie()
            case None => doEgressPortSimulation()
        }
    }

    private def notifyFlowAdded(flow: Flow,
                                  newWildFlow: Option[WildcardFlow],
                                  tags: ROSet[Any],
                                  removalCallbacks: ROSet[Callback0]) {
        log.debug("Successfully created flow for {}", cookieStr)
        newWildFlow match {
            case None =>
                FlowController ! FlowAdded(flow, wcMatch)
                deduplicator ! ApplyFlow(flow.getActions, cookie)
            case Some(wf) =>
                val msg = AddWildcardFlow(wf, Some(flow), removalCallbacks,
                                          tags, lastInvalidation)
                FlowController ! msg
                FlowController !
                    ApplyFlowFor(ApplyFlow(flow.getActions, cookie), deduplicator)
        }
    }

    private def createFlow(wildFlow: WildcardFlow,
                           newWildFlow: Option[WildcardFlow] = None,
                           tags: ROSet[Any] = Set.empty,
                           removalCallbacks: ROSet[Callback0] = Set.empty) {
        log.debug("Creating flow {} for {}", wildFlow, cookieStr)
        val dpFlow = new Flow().setActions(wildFlow.getActions)
                               .setMatch(packet.getMatch)
        try {
            datapathConnection.flowsCreate(datapath, dpFlow)
            notifyFlowAdded(dpFlow, newWildFlow, tags, removalCallbacks)
        } catch {
            case e: NetlinkException =>
                log.info("{} Failed to add flow packet: {}", cookieStr, e)
                deduplicator ! ApplyFlow(dpFlow.getActions, cookie)
        }
    }

    private def addTranslatedFlow(wildFlow: WildcardFlow,
                                  tags: ROSet[Any],
                                  removalCallbacks: ROSet[Callback0]) {
        if (areTagsValid(tags))
            handleValidFlow(wildFlow, tags, removalCallbacks)
        else
            handleObsoleteFlow(wildFlow, removalCallbacks)

        executePacket(wildFlow.getActions)
    }

    def areTagsValid(tags: ROSet[Any]) =
        FlowController.isTagSetStillValid(lastInvalidation, tags)

    private def handleObsoleteFlow(wildFlow: WildcardFlow,
                           removalCallbacks: ROSet[Callback0]) {
        log.debug("Skipping creation of obsolete flow {} for {}",
                  cookieStr, wildFlow.getMatch)
        if (cookie.isDefined)
            deduplicator ! ApplyFlow(wildFlow.getActions, cookie)
        runCallbacks(removalCallbacks)
    }

    private def handleValidFlow(wildFlow: WildcardFlow,
                                tags: ROSet[Any],
                                removalCallbacks: ROSet[Callback0]) {
        cookie match {
            case Some(_) if packet.getMatch.isUserSpaceOnly =>
                log.debug("Adding wildcard flow {} for userspace only match",
                          wildFlow)
                FlowController !
                    AddWildcardFlow(wildFlow, None, removalCallbacks,
                                    tags, lastInvalidation)
                FlowController !
                    ApplyFlowFor(ApplyFlow(wildFlow.getActions, cookie), deduplicator)

            case Some(_) if !packet.getMatch.isUserSpaceOnly =>
                createFlow(wildFlow, Some(wildFlow), tags, removalCallbacks)

            case None =>
                log.debug("Only adding wildcard flow {} for {}",
                          wildFlow, cookieStr)
                FlowController !
                    AddWildcardFlow(wildFlow, None, removalCallbacks,
                                    tags, lastInvalidation)
        }
    }

    private def addTranslatedFlowForActions(
                            actions: Seq[FlowAction],
                            tags: ROSet[Any] = Set.empty,
                            removalCallbacks: ROSet[Callback0] = Set.empty,
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
        if (actions == null || actions.isEmpty) {
            log.debug("Dropping packet {}", cookieStr)
            return
        }

        log.debug("Executing packet {}", cookieStr)

        packet.setActions(actions.asJava)
        if (packet.getMatch.isUserSpaceOnly) {
            log.debug("Applying userspace actions to packet {}", cookieStr)
            UserspaceFlowActionTranslator.translate(packet)
        }

        try {
            datapathConnection.packetsExecute(datapath, packet)
        } catch {
            case e: NetlinkException =>
                log.info("{} Failed to execute packet: {}", cookieStr, e)
        }
    }

    private def handlePacketWithCookie(): Future[PipelinePath] =
        if (packet.getReason == Packet.Reason.FlowActionUserspace) {
            simulatePacketIn() flatMap processSimulationResult
        } else {
            FlowController.queryWildcardFlowTable(wcMatch) match {
                case Some(wildflow) =>
                    handleWildcardTableMatch(wildflow)
                    Future.successful(WildcardTableHit)
                case None =>
                    handleWildcardTableMiss()
            }
        }

    def handleWildcardTableMatch(wildFlow: WildcardFlow) {
        log.debug("Packet {} matched a wildcard flow", cookieStr)
        if (packet.getMatch.isUserSpaceOnly) {
            log.debug("Won't add flow with userspace match {}", packet.getMatch)
            deduplicator ! ApplyFlow(wildFlow.getActions, cookie)
        } else {
            createFlow(wildFlow)
        }

        executePacket(wildFlow.getActions)
    }

    private def handleWildcardTableMiss()
    : Future[PipelinePath] = {
        if (wcMatch.isFromTunnel) {
            handlePacketToPortSet()
        } else {
            /* QUESTION: do we need another de-duplication
             *  stage here to avoid e.g. two micro-flows that
             *  differ only in TTL from going to the simulation
             *  stage? */
            simulatePacketIn() flatMap processSimulationResult
        }
    }

    /** The packet arrived on a tunnel but didn't match in the WFT. It's either
      * addressed (by the tunnel key) to a local PortSet or it was mistakenly
      * routed here. Map the tunnel key to a port set (through the
      * VirtualToPhysicalMapper).
      */
    private def handlePacketToPortSet(): Future[PipelinePath] = {
        log.debug("Packet {} from a tunnel port towards a port set", cookieStr)
        // We currently only handle packets ingressing on tunnel ports if they
        // have a tunnel key. If the tunnel key corresponds to a local virtual
        // port then the pre-installed flow rules should have matched the
        // packet. So we really only handle cases where the tunnel key exists
        // and corresponds to a port set.

        val req = PortSetForTunnelKeyRequest(wcMatch.getTunnelID)
        val portSetFuture = VirtualToPhysicalMapper expiringAsk req

        portSetFuture flatMap {
            case portSet =>
                val action = FlowActionOutputToVrnPortSet(portSet.id)
                log.debug("tun => portSet, action: {}, portSet: {}",
                    action, portSet)
                // egress port filter simulation

                val tags = mutable.Set[Any]()
                activePorts(portSet.localPorts, tags) flatMap { localPorts =>
                    // Take the outgoing filter for each port
                    // and apply it, checking for Action.ACCEPT.
                    applyOutboundFilters(
                        localPorts, portSet.id, wcMatch, Some(tags)
                    ) andThen {
                        case Success(portIDs) =>
                            addTranslatedFlowForActions(
                                towardsLocalDpPorts(List(action), portSet.id,
                                    portsForLocalPorts(portIDs), tags), tags)
                    }
                }
        } andThen {
            case Failure(e) =>
                // for now, install a drop flow. We will invalidate
                // it if the port comes up later on.
                log.debug("PacketIn came from a tunnel port but the key does " +
                          "not map to any PortSet. Exception: {}", e)
                val wildFlow = WildcardFlow(
                    wcmatch = wcMatch,
                    hardExpirationMillis = ERROR_CONDITION_HARD_EXPIRATION)
                addTranslatedFlow(wildFlow,
                    Set(FlowTagger.invalidateByTunnelKey(wcMatch.getTunnelID)),
                    Set.empty)
        } map {
            _ => PacketToPortSet
        }
    }

    private def doEgressPortSimulation() = {
        log.debug("Handling generated packet")
        runSimulation() flatMap processSimulationResult
    }

    def processSimulationResult(result: SimulationResult) = {
        log.debug("Simulation phase returned: {}", result)
        (result match {
            case AddVirtualWildcardFlow(flow, callbacks, tags) =>
                addVirtualWildcardFlow(flow, callbacks, tags)
            case SendPacket(actions) =>
                sendPacket(actions)
            case NoOp =>
                deduplicator ! ApplyFlow(Nil, cookie)
                Future.successful(true)
            case TemporaryDrop(tags, flowRemovalCallbacks) =>
                addTranslatedFlowForActions(Nil, tags, flowRemovalCallbacks,
                                            TEMPORARY_DROP_MILLIS, 0)
                Future.successful(true)
            case Drop(tags, flowRemovalCallbacks) =>
                addTranslatedFlowForActions(Nil, tags, flowRemovalCallbacks,
                                            0, IDLE_EXPIRATION_MILLIS)
                Future.successful(true)
        }) map {
            _ => Simulation
        }
    }

    private def simulatePacketIn()
    : Future[SimulationResult] = {
        val inPortNo = wcMatch.getInputPortNumber
        if (inPortNo == null) {
            log.error(
                "SCREAM: got a PacketIn with no inPort number {}.", cookieStr)
            return Future.successful(NoOp)
        }

        val port: JInteger = Unsigned.unsign(inPortNo)
        log.debug("Handling packet {} on port #{}: {}",
                  cookieStr, port, packet.getReason)
        dpState.getVportForDpPortNumber(port) match {
            case Some(vportId) =>
                wcMatch.setInputPortUUID(vportId)

                system.eventStream.publish(
                    PacketIn(wcMatch.clone(), packet.getPacket, packet.getMatch,
                        packet.getReason, cookie getOrElse 0))

                handleDHCP(vportId) flatMap {
                    case true =>
                        Future.successful(NoOp)
                    case false =>
                        runSimulation()
                }

            case None =>
                Future.successful(TemporaryDrop(Set.empty, Set.empty))
        }
    }

    def addVirtualWildcardFlow(flow: WildcardFlow,
                               flowRemovalCallbacks: ROSet[Callback0] = Set.empty,
                               tags: ROSet[Any] = Set.empty): Future[Boolean] = {
        translateVirtualWildcardFlow(flow, tags) andThen {
            case Success((finalFlow, finalTags)) =>
                addTranslatedFlow(finalFlow, finalTags, flowRemovalCallbacks)
        } map { _ => true }
    }

    private def handleDHCP(inPortId: UUID): Future[Boolean] = {
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
            return Future.successful(false)

        (for {
            ip4 <- payloadAs[IPv4](packet.getPacket)
            udp <- payloadAs[UDP](ip4)
            dhcp <- payloadAs[DHCP](udp)
            if dhcp.getOpCode == DHCP.OPCODE_REQUEST
        } yield {
            processDhcpFuture(inPortId, dhcp)
        }) getOrElse { Future.successful(false) }
    }

    private def processDhcpFuture(inPortId: UUID, dhcp: DHCP): Future[Boolean] =
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
                DeduplicationActor !
                    EmitGeneratedPacket(inPort.id, dhcpReply, cookie)
                true
            case None =>
                false
        }
    }

    private def sendPacket(actions: List[FlowAction]): Future[Boolean] = {

        if (null == actions || actions.isEmpty) {
            log.debug("Dropping {} {} without actions", cookieStr, packet)
            return Future.successful(true)
        }

        log.debug("Sending {} {} with actions {}", cookieStr, packet, actions)
        translateActions(actions, None, None, wcMatch) recover {
            case ex =>
                log.warning("failed to translate actions: {}", ex)
                Nil
        } andThen {
            case Success(a) => executePacket(a)
        } map {
            _ => true
        }
    }

    private def runCallbacks(callbacks: Iterable[Callback0]) {
        val iterator = callbacks.iterator
        while (iterator.hasNext) {
            iterator.next().call()
        }
    }
}
