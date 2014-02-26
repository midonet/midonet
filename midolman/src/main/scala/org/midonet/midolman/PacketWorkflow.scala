/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.lang.{Integer => JInteger}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.{Set => ROSet}
import scala.concurrent._
import scala.reflect.ClassTag

import akka.actor._
import akka.event.{Logging, LoggingAdapter}
import akka.util.Timeout

import org.midonet.cluster.client.Port
import org.midonet.cluster.DataClient
import org.midonet.midolman.datapath.ErrorHandlingCallback
import org.midonet.midolman.simulation.DhcpImpl
import org.midonet.midolman.topology.FlowTagger
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.rcu.PortSet
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.netlink.{Callback => NetlinkCallback}
import org.midonet.odp.{Packet, Datapath, Flow, FlowMatch}
import org.midonet.odp.flows.{FlowKey, FlowKeyUDP, FlowAction}
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.packets._
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPortSet
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.util.concurrent._
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

    sealed trait SimulationResult

    case object NoOp extends SimulationResult

    case object ErrorDrop extends SimulationResult

    case class SendPacket(actions: List[FlowAction]) extends SimulationResult

    case class AddVirtualWildcardFlow(flow: WildcardFlow,
                                      flowRemovalCallbacks: ROSet[Callback0],
                                      tags: ROSet[Any]) extends SimulationResult

    trait PipelinePath

    case object WildcardTableHit extends PipelinePath
    case object PacketToPortSet extends PipelinePath
    case object Simulation extends PipelinePath

    def apply(dpCon: OvsDatapathConnection,
              dpState: DatapathState,
              dp: Datapath,
              dataClient: DataClient,
              packet: Packet,
              wcMatch: WildcardMatch,
              cookieOrEgressPort: Either[Int, UUID],
              parentCookie: Option[Int])
             (runSim: => Future[SimulationResult])
             (implicit system: ActorSystem) =
        new PacketWorkflow(dpCon, dpState, dp, dataClient, packet,
                           wcMatch, cookieOrEgressPort, parentCookie) {
            def runSimulation() = runSim
        }
}

abstract class PacketWorkflow(protected val datapathConnection: OvsDatapathConnection,
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
    import FlowController.AddWildcardFlow
    import FlowController.FlowAdded
    import VirtualToPhysicalMapper.PortSetForTunnelKeyRequest
    import VirtualTopologyActor.PortRequest

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

    private def noOpCallback = new NetlinkCallback[Flow] {
        def onSuccess(dpFlow: Flow) {}
        def onTimeout() {}
        def onError(ex: NetlinkException) {}
    }

    private def flowAddedCallback(flow: Flow,
                                  newWildFlow: Option[WildcardFlow],
                                  tags: ROSet[Any],
                                  removalCallbacks: ROSet[Callback0])
    : (Future[Boolean], NetlinkCallback[Flow]) = {
        val flowPromise = promise[Boolean]()
        val callback = new NetlinkCallback[Flow] {
            def onSuccess(dpFlow: Flow) {
                log.debug("Successfully created flow for {}", cookieStr)
                newWildFlow match {
                    case None =>
                        FlowController ! FlowAdded(dpFlow)
                    case Some(wf) =>
                        FlowController !
                                AddWildcardFlow(wf, Some(dpFlow),
                                    removalCallbacks, tags, lastInvalidation)
                }
                DeduplicationActor ! ApplyFlow(dpFlow.getActions, cookie)
                flowPromise success true
            }

            def onTimeout() {
                log.warning("Flow creation for {} timed out, deleting", cookieStr)
                datapathConnection.flowsDelete(datapath, flow, noOpCallback)
                DeduplicationActor ! ApplyFlow(flow.getActions, cookie)
                flowPromise success true
            }

            def onError(ex: NetlinkException) {
                if (ex.getErrorCodeEnum == ErrorCode.EEXIST) {
                    log.info("File exists while adding flow for {}", cookieStr)
                    DeduplicationActor !
                            ApplyFlow(flow.getActions, cookie)
                    runCallbacks(removalCallbacks)
                    flowPromise success true
                } else {
                    // NOTE(pino) - it'd be more correct to execute the
                    // packets with the actions found in the flow that
                    // failed to install  ...but, if the cause of the error
                    // is a busy netlink channel then this policy is more
                    // sensible.
                    log.error("Error {} while adding flow for {}. " +
                              "Dropping packets. The flow was {}",
                              ex, cookieStr, flow)
                    DeduplicationActor ! ApplyFlow(Seq.empty, cookie)
                    flowPromise failure ex
                }
            }
        }
        (flowPromise.future, callback)
    }

    private def createFlow(wildFlow: WildcardFlow,
                           newWildFlow: Option[WildcardFlow] = None,
                           tags: ROSet[Any] = Set.empty,
                           removalCallbacks: ROSet[Callback0] = Set.empty)
    : Future[Boolean] = {
        log.debug("Creating flow {} for {}", wildFlow, cookieStr)
        val dpFlow = new Flow().setActions(wildFlow.getActions)
                               .setMatch(packet.getMatch)
        val (future, callback) =
            flowAddedCallback(dpFlow, newWildFlow, tags, removalCallbacks)
        datapathConnection.flowsCreate(datapath, dpFlow, callback)
        future
    }

    private def addTranslatedFlow(wildFlow: WildcardFlow,
                                  tags: ROSet[Any],
                                  removalCallbacks: ROSet[Callback0])
    : Future[Boolean] = {
        val flowFuture =
            if (FlowController.isTagSetStillValid(lastInvalidation, tags))
                handleValidFlow(wildFlow, tags, removalCallbacks)
            else
                handleObsoleteFlow(wildFlow, removalCallbacks)

        val execFuture = executePacket(wildFlow.getActions)
        flowFuture flatMap { _ => execFuture } continue { _.isSuccess }
    }

    private def handleObsoleteFlow(wildFlow: WildcardFlow,
                           removalCallbacks: ROSet[Callback0]) = {
        log.debug("Skipping creation of obsolete flow {} for {}",
                  cookieStr, wildFlow.getMatch)
        if (cookie.isDefined)
            DeduplicationActor ! ApplyFlow(wildFlow.getActions, cookie)
        runCallbacks(removalCallbacks)
        Future.successful(true)
    }

    private def handleValidFlow(wildFlow: WildcardFlow,
                                tags: ROSet[Any],
                                removalCallbacks: ROSet[Callback0]) =
        cookie match {
            case Some(_) if packet.getMatch.isUserSpaceOnly =>
                log.debug("Adding wildcard flow {} for userspace only match",
                          wildFlow)
                FlowController !
                    AddWildcardFlow(wildFlow, None, removalCallbacks,
                                    tags, lastInvalidation)
                DeduplicationActor !
                    ApplyFlow(wildFlow.getActions, cookie)
                Future.successful(true)

            case Some(_) if !packet.getMatch.isUserSpaceOnly =>
                createFlow(wildFlow, Some(wildFlow), tags, removalCallbacks)

            case None =>
                log.debug("Only adding wildcard flow {} for {}",
                          wildFlow, cookieStr)
                FlowController !
                    AddWildcardFlow(wildFlow, None, removalCallbacks,
                                    tags, lastInvalidation)
                Future.successful(true)
        }

    private def addTranslatedFlowForActions(actions: Seq[FlowAction],
                                            tags: ROSet[Any] = Set.empty,
                                            removalCallbacks: ROSet[Callback0] = Set.empty,
                                            expiration: Int = 3000,
                                            priority: Short = 0): Future[Boolean] = {

        val wildFlow = WildcardFlow(
            wcmatch = wcMatch,
            idleExpirationMillis = expiration,
            actions =  actions.toList,
            priority = priority)

        addTranslatedFlow(wildFlow, tags, removalCallbacks)
    }

    private def executePacket(actions: Seq[FlowAction]): Future[Boolean] = {
        if (actions == null || actions.isEmpty) {
            log.debug("Dropping packet {}", cookieStr)
            return Future.successful(true)
        }

        log.debug("Executing packet {}", cookieStr)

        packet.setActions(actions.asJava)
        if (packet.getMatch.isUserSpaceOnly) {
            log.debug("Applying userspace actions to packet {}", cookieStr)
            UserspaceFlowActionTranslator.translate(packet)
        }

        val pktPromise = promise[Boolean]()
        datapathConnection.packetsExecute(
            datapath, packet,
            new ErrorHandlingCallback[java.lang.Boolean] {
                def onSuccess(data: java.lang.Boolean) {
                    log.debug("Packet execute success {}", cookieStr)
                    pktPromise success true
                }

                def handleError(ex: NetlinkException, timeout: Boolean) {
                    log.error(ex, "Failed to send a packet {} {} due to {}",
                              cookieStr, packet,
                              if (timeout) "timeout" else "error")
                    pktPromise failure ex
                }
            })
        pktPromise.future
    }

    private def handlePacketWithCookie(): Future[PipelinePath] =
        if (packet.getReason == Packet.Reason.FlowActionUserspace) {
            simulatePacketIn() flatMap processSimulationResult
        } else {
            FlowController.queryWildcardFlowTable(packet.getMatch) match {
                case Some(wildflow) =>
                  handleWildcardTableMatch(wildflow)
                case None =>
                  handleWildcardTableMiss()
            }
        }

    private def handleWildcardTableMatch(wildFlow: WildcardFlow): Future[PipelinePath] = {
        log.debug("Packet {} matched a wildcard flow", cookieStr)

        val flowFuture = if (packet.getMatch.isUserSpaceOnly) {
            log.debug("Won't add flow with userspace match {}", packet.getMatch)
            DeduplicationActor ! ApplyFlow(wildFlow.getActions, cookie)
            Future.successful(true)
        } else {
            createFlow(wildFlow)
        }

        val execFuture = executePacket(wildFlow.getActions)

        flowFuture.flatMap { _ => execFuture }
                  .map{ _ => WildcardTableHit }
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

        val portSetFuture = VirtualToPhysicalMapper ?
                PortSetForTunnelKeyRequest(wcMatch.getTunnelID)

        portSetFuture.mapTo[PortSet] flatMap {
            case null =>
                Future.failed(new Exception("null portSet"))
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
                    ) flatMap {
                        portIDs =>
                            addTranslatedFlowForActions(
                                towardsLocalDpPorts(List(action), portSet.id,
                                    portsForLocalPorts(portIDs), tags), tags)
                    }
                }
        } recoverWith {
            case e =>
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

    private def processSimulationResult(result: SimulationResult) = {
        log.debug("Simulation phase returned: {}", result)
        (result match {
            case AddVirtualWildcardFlow(flow, callbacks, tags) =>
                addVirtualWildcardFlow(flow, callbacks, tags)
            case SendPacket(actions) =>
                sendPacket(actions)
            case NoOp =>
                DeduplicationActor ! ApplyFlow(Nil, cookie)
                Future.successful(true)
            case ErrorDrop =>
                addTranslatedFlowForActions(Nil, expiration = 5000)
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
                Future.successful(ErrorDrop)
        }
    }

    def addVirtualWildcardFlow(flow: WildcardFlow,
                               flowRemovalCallbacks: ROSet[Callback0] = Set.empty,
                               tags: ROSet[Any] = Set.empty): Future[Boolean] = {
        translateVirtualWildcardFlow(flow, tags) flatMap {
            case (finalFlow, finalTags) =>
                addTranslatedFlow(finalFlow, finalTags, flowRemovalCallbacks)
        }
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
        VirtualTopologyActor.expiringAsk(PortRequest(inPortId), log).flatMap{
            port =>
                DatapathController.calculateMinMtu.map {
                    mtu =>
                        processDhcp(port, dhcp, mtu)
                }
        }

    private def processDhcp(inPort: Port, dhcp: DHCP, mtu: Option[Short]) = {
        val srcMac = packet.getPacket.getSourceMACAddress
        val dhcpLogger = Logging.getLogger(system, classOf[DhcpImpl])
        DhcpImpl(dataClient, inPort, dhcp, srcMac, mtu, dhcpLogger) match {
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
        } flatMap executePacket
    }

    private def runCallbacks(callbacks: Iterable[Callback0]) {
        val iterator = callbacks.iterator
        while (iterator.hasNext) {
            iterator.next().call()
        }
    }
}
