// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import java.lang.{Integer => JInteger}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.collection.mutable
import scala.collection.{Set => ROSet}
import scala.compat.Platform

import akka.actor._
import akka.dispatch.{ExecutionContext, Promise, Future}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.ask
import akka.util.Timeout
import com.yammer.metrics.core.Clock

import org.midonet.cache.Cache
import org.midonet.cluster.DataClient
import org.midonet.midolman.DeduplicationActor._
import org.midonet.midolman.FlowController.AddWildcardFlow
import org.midonet.midolman.FlowController.FlowAdded
import org.midonet.midolman.datapath.ErrorHandlingCallback
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.simulation.{DhcpImpl, Coordinator}
import org.midonet.midolman.topology.FlowTagger
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualToPhysicalMapper.
    PortSetForTunnelKeyRequest
import org.midonet.midolman.topology.rcu.PortSet
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.netlink.{Callback => NetlinkCallback}
import org.midonet.odp.Packet.Reason.FlowActionUserspace
import org.midonet.odp._
import org.midonet.odp.flows.{FlowKey, FlowKeyUDP, FlowAction}
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.packets._
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPortSet
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.util.functors.Callback0
import org.midonet.util.throttling.ThrottlingGuard

trait PacketHandler {
    def start(): Future[Boolean]
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

    case class SendPacket(actions: List[FlowAction[_]]) extends SimulationResult

    case class AddVirtualWildcardFlow(flow: WildcardFlow,
                                      flowRemovalCallbacks: ROSet[Callback0],
                                      tags: ROSet[Any]) extends SimulationResult

    private trait PipelinePath

    private case object WildcardTableHit extends PipelinePath
    private case object PacketToPortSet extends PipelinePath
    private case object Simulation extends PipelinePath
}

class PacketWorkflow(
        protected val datapathConnection: OvsDatapathConnection,
        protected val dpState: DatapathState,
        datapath: Datapath,
        dataClient: DataClient,
        connectionCache: Cache,
        traceMessageCache: Cache,
        traceIndexCache: Cache,
        packet: Packet,
        cookieOrEgressPort: Either[Int, UUID],
        throttlingGuard: ThrottlingGuard,
        metrics: PacketPipelineMetrics,
        private val traceConditions: immutable.Seq[Condition])
       (implicit val executor: ExecutionContext,
        implicit val system: ActorSystem,
        implicit val context: ActorContext)
    extends FlowTranslator with UserspaceFlowActionTranslator with PacketHandler {

    import PacketWorkflow._


    // TODO marc get config from parent actors.
    val timeout = 5000 //config.getArpTimeoutSeconds * 1000

    val ERROR_CONDITION_HARD_EXPIRATION = 10000

    val cookie = cookieOrEgressPort match {
        case Left(c) => Some(c)
        case Right(_) => None
    }

    implicit val requestReplyTimeout = new Timeout(5, TimeUnit.SECONDS)

    val log: LoggingAdapter = Logging.getLogger(system, this.getClass)
    val cookieStr: String = "[cookie:" + cookie.getOrElse("No Cookie") + "]"
    val lastInvalidation = FlowController.lastInvalidationEvent

    override def start(): Future[Boolean] = {
        // pipelinePath will track which code-path this packet took, so latency
        // can be tracked accordingly at the end of the workflow.
        // there are three PipelinePaths, all case objects:
        // Simulation, PacketToPortSet and WildcardTableHit
        var pipelinePath: Option[PipelinePath] = None

        log.debug("Initiating processing of packet {}", cookieStr)
        val workflowFuture = cookie match {
            case Some(cook) if (packet.getReason == FlowActionUserspace) =>
                log.debug("Simulating packet addressed to userspace {}",
                    cookieStr)
                pipelinePath = Some(Simulation)
                doSimulation()

            case Some(cook) =>
                FlowController.queryWildcardFlowTable(packet.getMatch) match {
                    case Some(wildFlow) =>
                        log.debug("Packet {} matched a wildcard flow", cookieStr)
                        pipelinePath = Some(WildcardTableHit)
                        handleWildcardTableMatch(wildFlow)
                    case None =>
                        val wildMatch = WildcardMatch.fromFlowMatch(packet.getMatch)
                        Option(wildMatch.getTunnelID) match {
                            case Some(tunnelId) =>
                                log.debug("Packet {} addressed to a port set", cookieStr)
                                pipelinePath = Some(PacketToPortSet)
                                handlePacketToPortSet()
                            case None =>
                                /* QUESTION: do we need another de-duplication
                                 *  stage here to avoid e.g. two micro-flows that
                                 *  differ only in TTL from going to the simulation
                                 *  stage? */
                                log.debug("Simulating packet {}", cookieStr)
                                pipelinePath = Some(Simulation)
                                doSimulation()
                        }
                }

            case None =>
                log.debug("Simulating generated packet")
                pipelinePath = Some(Simulation)
                doSimulation()
        }

        workflowFuture onComplete {
            case Right(bool) =>
                log.debug("Packet with {} processed.", cookieStr)
                cookie match {
                    case None =>
                    case Some(c) =>
                        throttlingGuard.tokenOut()
                        val latency = (Clock.defaultClock().tick() - packet.getStartTimeNanos).toInt
                        metrics.packetsProcessed.mark()
                        pipelinePath match {
                            case Some(WildcardTableHit) => metrics.wildcardTableHit(latency)
                            case Some(PacketToPortSet) => metrics.packetToPortSet(latency)
                            case Some(Simulation) => metrics.packetSimulated(latency)
                            case _ =>
                        }
                }
            case Left(ex) =>
                log.warning("Exception while processing packet {}, {}",
                            cookieStr, ex.getStackTraceString)
                for (c <- cookie) {
                    throttlingGuard.tokenOut()
                    metrics.packetsProcessed.mark()
                }
        }
    }

    private def noOpCallback = new NetlinkCallback[Flow] {
        def onSuccess(dpFlow: Flow) {}
        def onTimeout() {}
        def onError(ex: NetlinkException) {}
    }

    private def flowAddedCallback(promise: Promise[Boolean],
                                  flow: Flow,
                                  newWildFlow: Option[WildcardFlow] = None,
                                  tags: ROSet[Any] = Set.empty,
                                  removalCallbacks: ROSet[Callback0] = Set.empty) =
        new NetlinkCallback[Flow] {
            def onSuccess(dpFlow: Flow) {
                log.debug("Successfully created flow for {}", cookieStr)
                newWildFlow match {
                    case None =>
                        FlowController.getRef() ! FlowAdded(dpFlow)
                    case Some(wf) =>
                        FlowController.getRef() !
                            AddWildcardFlow(wf, Some(dpFlow),
                                removalCallbacks, tags, lastInvalidation)
                }
                DeduplicationActor.getRef() ! ApplyFlow(dpFlow.getActions, cookie)
                promise.success(true)
            }

            def onTimeout() {
                log.warning("Flow creation for {} timed out, deleting", cookieStr)
                datapathConnection.flowsDelete(datapath, flow, noOpCallback)
                DeduplicationActor.getRef() ! ApplyFlow(flow.getActions, cookie)
                promise.success(true)
            }

            def onError(ex: NetlinkException) {
                if (ex.getErrorCodeEnum == ErrorCode.EEXIST) {
                    log.info("File exists while adding flow for {}", cookieStr)
                    DeduplicationActor.getRef() !
                        ApplyFlow(flow.getActions, cookie)
                    runCallbacks(removalCallbacks.toArray)
                    promise.success(true)
                } else {
                    // NOTE(pino) - it'd be more correct to execute the
                    // packets with the actions found in the flow that
                    // failed to install  ...but, if the cause of the error
                    // is a busy netlink channel then this policy is more
                    // sensible.
                    log.error("Error {} while adding flow for {}. " +
                              "Dropping packets. The flow was {}",
                              ex, cookieStr, flow)
                    DeduplicationActor.getRef() ! ApplyFlow(Seq.empty, cookie)
                    promise.failure(ex)
                }
            }
    }

    @tailrec
    private def runCallbacks(callbacks: Array[Callback0], i: Int = 0) {
        if (callbacks != null && callbacks.length > i) {
            callbacks(i).call()
            runCallbacks(callbacks, i+1)
        }
    }

    private def addTranslatedFlow(wildFlow: WildcardFlow,
                                  tags: ROSet[Any] = Set.empty,
                                  removalCallbacks: ROSet[Callback0] = Set.empty,
                                  expiration: Long = 3000,
                                  priority: Short = 0): Future[Boolean] = {

        val flowPromise = Promise[Boolean]()(system.dispatcher)
        val valid = FlowController.isTagSetStillValid(lastInvalidation, tags)

        cookie match {
            case Some(cook) if (!valid) =>
                log.debug("Skipping creation of obsolete flow for cookie {} {}",
                          cookie, wildFlow.getMatch)
                DeduplicationActor.getRef() !
                    ApplyFlow(wildFlow.getActions,cookie)
                runCallbacks(removalCallbacks.toArray)
                flowPromise.success(true)

            case Some(cook) if (valid && packet.getMatch.isUserSpaceOnly) =>
                log.debug("Adding wildcard flow, for userspace only match")
                FlowController.getRef() !
                    AddWildcardFlow(wildFlow, None, removalCallbacks,
                                    tags, lastInvalidation)

                DeduplicationActor.getRef() ! ApplyFlow(wildFlow.getActions, cookie)
                flowPromise.success(true)

            case Some(cook) if (valid && !packet.getMatch.isUserSpaceOnly) =>
                val dpFlow = new Flow().setActions(wildFlow.getActions).
                                        setMatch(packet.getMatch)
                log.debug("Adding wildcard flow {} for {}", wildFlow, cookieStr)
                datapathConnection.flowsCreate(datapath, dpFlow,
                    flowAddedCallback(flowPromise, dpFlow,
                                      Some(wildFlow), tags, removalCallbacks))

            case None if (valid) =>
                log.debug("Adding wildcard flow only for {}: {}", cookieStr, wildFlow)
                FlowController.getRef() !
                    AddWildcardFlow(wildFlow, None, removalCallbacks,
                                    tags, lastInvalidation)
                flowPromise.success(true)

            case _ =>
                log.debug("Skipping creation of obsolete flow: {}", wildFlow.getMatch)
                runCallbacks(removalCallbacks.toArray)
                flowPromise.success(true)
        }

        Future.sequence(List(flowPromise, executePacket(wildFlow.getActions)))
            .map { _ => true }
            .recover { case _ => false }
    }

    private def addTranslatedFlowForActions(actions: Seq[FlowAction[_]],
                                            tags: ROSet[Any] = Set.empty,
                                            removalCallbacks: ROSet[Callback0] = Set.empty,
                                            expiration: Int = 3000,
                                            priority: Short = 0): Future[Boolean] = {

        val wildFlow = WildcardFlow(
            wcmatch = WildcardMatch.fromFlowMatch(packet.getMatch),
            idleExpirationMillis = expiration,
            actions =  actions.toList,
            priority = priority)

        addTranslatedFlow(wildFlow, tags, removalCallbacks, expiration, priority)
    }

    private def executePacket(actions: Seq[FlowAction[_]]): Future[Boolean] = {
        log.debug("Executing packet {}", cookieStr)
        packet.setActions(actions.asJava)
        if (packet.getMatch.isUserSpaceOnly) {
            log.debug("Applying userspace actions to packet {}", cookieStr)
            applyActionsAfterUserspaceMatch(packet)
        }

        if (actions != null && actions.size > 0) {
            val promise = Promise[Boolean]()(system.dispatcher)
            datapathConnection.packetsExecute(
                datapath, packet,
                new ErrorHandlingCallback[java.lang.Boolean] {
                    def onSuccess(data: java.lang.Boolean) {
                        log.debug("Packet execute success {}", cookieStr)
                        promise.success(true)
                    }

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error(ex,
                            "Failed to send a packet {} {} due to {}",
                            cookieStr, packet,
                            if (timeout) "timeout" else "error")
                        promise.failure(ex)
                    }
                })
            promise
        } else {
            Promise.successful(true)
        }
    }

    private def handleWildcardTableMatch(wildFlow: WildcardFlow): Future[Boolean] = {

        val dpFlow = new Flow().setActions(wildFlow.getActions).setMatch(packet.getMatch)

        val flowPromise = Promise[Boolean]()(system.dispatcher)
        if (packet.getMatch.isUserSpaceOnly) {
            log.debug("Won't add flow with userspace match {}", packet.getMatch)
            DeduplicationActor.getRef() ! ApplyFlow(dpFlow.getActions, cookie)
            flowPromise.success(true)
        } else {
            datapathConnection.flowsCreate(datapath, dpFlow,
                flowAddedCallback(flowPromise, dpFlow))
        }
        val execPromise = executePacket(wildFlow.getActions)
        val futures = Future.sequence(List(flowPromise, execPromise))
        futures map { _ => true } fallbackTo { Promise.successful(false) }
    }


    /** The packet arrived on a tunnel but didn't match in the WFT. It's either
     * addressed (by the tunnel key) to a local PortSet or it was mistakenly
     * routed here. Map the tunnel key to a port set (through the
     * VirtualToPhysicalMapper).
     */
    private def handlePacketToPortSet(): Future[Boolean] = {

        log.debug("Packet {} came from a tunnel port", cookieStr)
        // We currently only handle packets ingressing on tunnel ports if they
        // have a tunnel key. If the tunnel key corresponds to a local virtual
        // port then the pre-installed flow rules should have matched the
        // packet. So we really only handle cases where the tunnel key exists
        // and corresponds to a port set.
        val wMatch = WildcardMatch.fromFlowMatch(packet.getMatch)
        if (wMatch.getTunnelID == null) {
            log.error("SCREAM: dropping a flow from tunnel port {} because " +
                " it has no tunnel key.", wMatch.getInputPortNumber)
            return addTranslatedFlowForActions(Nil)
        }

        val portSetFuture = VirtualToPhysicalMapper.getRef() ?
            PortSetForTunnelKeyRequest(wMatch.getTunnelID)

        portSetFuture.mapTo[PortSet] flatMap { portSet =>
            if (portSet == null) {
                return Promise.failed[Boolean](new Exception)(system.dispatcher)
            }

            val action = FlowActionOutputToVrnPortSet(portSet.id)
            log.debug("tun => portSet, action: {}, portSet: {}",
                action, portSet)
            // egress port filter simulation

            withLocalPorts(portSet.id, portSet.localPorts) { localPorts =>
                // Take the outgoing filter for each port
                // and apply it, checking for Action.ACCEPT.
                val tags = mutable.Set[Any]()
                applyOutboundFilters(localPorts, portSet.id, wMatch, Some(tags))
                { portIDs =>
                    addTranslatedFlowForActions(
                        towardsLocalDpPorts(List(action), portSet.id,
                            portsForLocalPorts(portIDs), tags),
                        tags)
                }
            }
        } recoverWith {
            case e =>
                // for now, install a drop flow. We will invalidate
                // it if the port comes up later on.
                log.debug("PacketIn came from a tunnel port but " +
                    "the key does not map to any PortSet")
                val wildFlow = WildcardFlow(
                    wcmatch = wMatch,
                    hardExpirationMillis = ERROR_CONDITION_HARD_EXPIRATION)
                addTranslatedFlow(wildFlow,
                    Set(FlowTagger.invalidateByTunnelKey(wMatch.getTunnelID)),
                    Set.empty)
        }
    }

    private def doSimulation(): Future[Boolean] =
        (cookieOrEgressPort match {
            case Left(haveCookie) =>
                simulatePacketIn()
            case Right(haveEgress) =>
                // simulate generated packet
                val wcMatch = WildcardMatch.fromEthernetPacket(packet.getPacket)
                prepareCoordinator(wcMatch).simulate()
        }) flatMap {
            case AddVirtualWildcardFlow(flow, callbacks, tags) =>
                log.debug("Simulation phase returned: AddVirtualWildcardFlow")
                addVirtualWildcardFlow(flow, callbacks, tags)
            case SendPacket(actions) =>
                log.debug("Simulation phase returned: SendPacket")
                sendPacket(actions)
            case NoOp =>
                log.debug("Simulation phase returned: NoOp")
                DeduplicationActor.getRef() ! ApplyFlow(Nil, cookie)
                Promise.successful(true)
            case ErrorDrop =>
                log.debug("Simulation phase returned: ErrorDrop")
                addTranslatedFlowForActions(Nil, expiration = 5000)
        }

    private def simulatePacketIn(): Future[SimulationResult] = {
        log.debug("Pass packet to simulation layer {}", cookieStr)

        val wMatch = WildcardMatch.fromFlowMatch(packet.getMatch)
        val inPortNo = wMatch.getInputPortNumber
        if (inPortNo == null) {
            log.error(
                "SCREAM: got a PacketIn with no inPort number {}.", cookieStr)
            return Promise.successful(NoOp)
        }

        val port: JInteger = Unsigned.unsign(inPortNo)
        log.debug("PacketIn on port #{}", port)
        dpState.getVportForDpPortNumber(port) match {
            case Some(vportId) =>
                wMatch.setInputPortUUID(vportId)
                system.eventStream.publish(
                    PacketIn(wMatch, packet.getPacket, packet.getMatch,
                        packet.getReason, cookie getOrElse 0))

                handleDHCP(vportId) flatMap {
                    case true =>
                        Promise.successful(NoOp)
                    case false =>
                        prepareCoordinator(wMatch).simulate()
                }

            case None =>
                wMatch.setInputPort(port.toShort)
                Promise.successful(ErrorDrop)
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
        def isUdpDhcpFlowKey(k: FlowKey[_]): Boolean = k match {
            case udp: FlowKeyUDP => (udp.getUdpSrc == 68) && (udp.getUdpDst == 67)
            case _ => false
        }

        def payloadAs[T](pkt: IPacket)(implicit manifest: Manifest[T]): Option[T] = {
            val payload = pkt.getPayload
            if (manifest.erasure == payload.getClass)
                Some(payload.asInstanceOf[T])
            else
                None
        }

        if (packet.getMatch.getKeys.filter(isUdpDhcpFlowKey).isEmpty)
            return Promise.successful(false)

        val eth = packet.getPacket
        (for {
            ip4 <- payloadAs[IPv4](eth)
            udp <- payloadAs[UDP](ip4)
            dhcp <- payloadAs[DHCP](udp)
            if dhcp.getOpCode == DHCP.OPCODE_REQUEST
        } yield {
            new DhcpImpl(dataClient, inPortId, dhcp, eth.getSourceMACAddress,
                cookie).handleDHCP
        }) getOrElse { Promise.successful(false) }
    }

    private def sendPacket(origActions: List[FlowAction[_]]): Future[Boolean] = {
        log.debug("Sending packet {} {} with action list {}",
                  cookieStr, packet, origActions)
        // Empty action list drops the packet. No need to send to DP.
        if (null == origActions || origActions.isEmpty)
            return Promise.successful(true)

        val wildMatch = WildcardMatch.fromEthernetPacket(packet.getPacket)
        packet.setMatch(FlowMatches.fromEthernetPacket(packet.getPacket))
        val actionsFuture = translateActions(origActions, None, None, wildMatch)
        actionsFuture recover {
            case ex =>
                log.error(ex, "failed to translate actions")
                Nil
        } flatMap {
            case Nil =>
                Promise.successful(true)
            case actions =>
                log.debug("Translated actions to {} for {}", actions, cookieStr)
                executePacket(actions)
        }
    }

    def prepareCoordinator(wcMatch: WildcardMatch): Coordinator = {
        // FIXME (guillermo) - The launching of the coordinator is missing
        // the parentCookie params.  They will need to be given to the
        // PacketWorkflow object.
        val egressPort = cookieOrEgressPort match {
            case Right(e) => Some(e)
            case Left(_) => None
        }
        new Coordinator(wcMatch, packet.getPacket, cookie, egressPort,
            Platform.currentTime + timeout, connectionCache, traceMessageCache,
            traceIndexCache, None, traceConditions)
    }
}
