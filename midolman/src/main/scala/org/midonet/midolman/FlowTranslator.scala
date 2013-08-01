// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.{Set => ROSet}
import akka.actor.{ActorContext, ActorSystem}
import akka.dispatch.{ExecutionContext, Promise, Future}
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.util.Timeout
import java.{util => ju}
import java.util.UUID

import org.midonet.midolman.datapath.{FlowActionOutputToVrnPort,
        FlowActionOutputToVrnPortSet}
import org.midonet.midolman.rules.{ChainPacketContext, RuleResult}
import org.midonet.midolman.simulation.Coordinator.Device
import org.midonet.midolman.simulation.{Bridge => RCUBridge, VlanAwareBridge, Chain}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.PortSetRequest
import org.midonet.midolman.topology.VirtualTopologyActor.{
        PortSetHolderRequest, ChainRequest, PortRequest}
import org.midonet.midolman.topology.rcu.PortSet
import org.midonet.midolman.topology.{
        FlowTagger, VirtualTopologyActor, VirtualToPhysicalMapper}
import org.midonet.cluster.client
import org.midonet.cluster.client.ExteriorPort
import org.midonet.odp.flows.{
        FlowActionUserspace, FlowKeys, FlowActions, FlowAction}
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.util.functors.Callback0

object FlowTranslator {
    /**
     * Dummy ChainPacketContext used in egress port set chains.
     * All that is available is the Output Port ID (there's no information
     * on the ingress port or connection tracking at the egress controller).
     * @param outportID UUID for the output port
     */
    class EgressPortSetChainPacketContext(outportID: UUID, val tags: Option[mutable.Set[Any]])
            extends ChainPacketContext {

        override def getInPortId = null
        override def getOutPortId = outportID
        override def getPortGroups = new ju.HashSet[UUID]()
        override def addTraversedElementID(id: UUID) { }
        override def isConnTracked = false
        override def isForwardFlow = true
        override def getFlowCookie = null
        override def addFlowRemovedCallback(cb: Callback0) {}
        override def getParentCookie = null

        override def addFlowTag(tag: Any) {
            tags match {
                case None =>
                case Some(tset) => tset += tag
            }
        }
    }
}

trait FlowTranslator {
    import FlowTranslator._

    protected val datapathConnection: OvsDatapathConnection
    protected val dpState: DatapathState

    implicit protected val requestReplyTimeout: Timeout
    implicit protected val context: ActorContext
    val log: LoggingAdapter

    protected def translateVirtualWildcardFlow(
            flow: WildcardFlow, tags: ROSet[Any] = Set.empty)
            (implicit ec: ExecutionContext, system: ActorSystem):
                Future[Tuple2[WildcardFlow, ROSet[Any]]] = {

        val flowMatch = flow.getMatch
        val inPortId = flowMatch.getInputPortUUID

        // tags can be null
        val dpTags = new mutable.HashSet[Any]
        if (tags != null)
            dpTags ++= tags

        dpState.getDpPortNumberForVport(inPortId) match {
            case Some(portNo) =>
                flowMatch.setInputPortNumber(portNo.shortValue())
                         .unsetInputPortUUID()
                dpTags += FlowTagger.invalidateDPPort(portNo.shortValue())
            case None =>
        }

        val actions = Option(flow.getActions) getOrElse Seq.empty

        val translatedFuture = translateActions(
                actions, Option(inPortId), Option(dpTags), flow.getMatch)

        translatedFuture fallbackTo { Promise.successful(None) } map {
            case None =>
                val newFlow = WildcardFlow(
                    wcmatch = flow.wcmatch,
                    priority = flow.priority,
                    hardExpirationMillis = 5000)
                (newFlow, dpTags)
            case Some(translated) =>
                val newFlow = WildcardFlow(
                    wcmatch = flow.wcmatch,
                    actions = translated.toList,
                    priority = flow.priority,
                    hardExpirationMillis = flow.hardExpirationMillis,
                    idleExpirationMillis = flow.idleExpirationMillis)
                (newFlow, dpTags)
        }
    }

    protected def portsForLocalPorts(localVrnPorts: Seq[UUID]): Seq[Short] = {
        localVrnPorts flatMap {
            dpState.getDpPortNumberForVport(_) match {
                case Some(value) => Some(value.shortValue())
                case None =>
                    // TODO(pino): log that the port number was not found.
                    None
            }
        }
    }

    protected def applyOutboundFilters[A](localPorts: Seq[client.Port[_]],
                                          portSetID: UUID,
                                          pktMatch: WildcardMatch,
                                          tags: Option[mutable.Set[Any]],
                                          thunk: Seq[UUID] => Future[A])
                                         (implicit ec: ExecutionContext,
                                          system: ActorSystem): Future[A] = {
        // Fetch all of the chains.
        val chainFutures = localPorts map { port =>
            if (port.outFilterID == null)
                Promise.successful(null)(ec)
            else
                ask(VirtualTopologyActor.getRef,
                    ChainRequest(port.outFilterID, update = false)).mapTo[Chain]
        }
        // Apply the chains.
        Future.sequence(chainFutures)(Seq.canBuildFrom[Chain], ec) flatMap {
            chains =>
                val egressPorts = (localPorts zip chains) filter { portchain =>
                    val port = portchain._1
                    val chain = portchain._2
                    val fwdInfo = new EgressPortSetChainPacketContext(port.id, tags)

                    // apply chain and check result is ACCEPT.
                    val result =
                        Chain.apply(chain, fwdInfo, pktMatch, port.id, true)
                            .action
                    if (result != RuleResult.Action.ACCEPT &&
                        result != RuleResult.Action.DROP &&
                        result != RuleResult.Action.REJECT)
                        log.error("Applying chain {} produced {}, not " +
                            "ACCEPT, DROP, or REJECT", chain.id, result)
                    result == RuleResult.Action.ACCEPT
                }

                thunk(egressPorts map {portchain => portchain._1.id})
        } recoverWith {
            case e =>
                log.error("Error getting chains for PortSet {}", portSetID)
                Promise.failed(e)(system.dispatcher)
        }
    }

    protected def translateToDpPorts(acts: Seq[FlowAction[_]], port: UUID,
            localPorts: Seq[Short], tunnelKey: Option[Long],
            tunnelPorts: Seq[Short], dpTags: mutable.Set[Any])(
            implicit ec: ExecutionContext, system: ActorSystem): Seq[FlowAction[_]] = {

        tunnelKey match {
            case Some(k) =>
                log.debug("Translating output actions for vport (or set) {}," +
                    " having tunnel key {}, and corresponding to local dp " +
                    "ports {}, and tunnel ports {}",
                    port, k, localPorts, tunnelPorts)

            case None =>
                log.debug("No tunnel key provided. Translating output " +
                    "action for vport {}, corresponding to local dp port {}",
                    port, localPorts)
        }
        // TODO(pino): when we detect the flow won't have output actions,
        // set the flow to expire soon so that we can retry.
        if (localPorts.length == 0 && tunnelPorts.length == 0)
            log.warning("No local datapath ports or tunnels found. Expected " +
                        "only for forked actions, otherwise this flow will " +
                        "be dropped because we cannot make Output actions.")
        val newActs = ListBuffer[FlowAction[_]]()
        var newTags = new mutable.HashSet[Any]

        var translatablePort = port
        var translatedActions = localPorts.map { id =>
            FlowActions.output(id).asInstanceOf[FlowAction[_]]
        }
        log.debug("translated actions {}", translatedActions)
        // add tag for flow invalidation
        val localPortsIter = localPorts.iterator
        while (localPortsIter.hasNext) {
            newTags += FlowTagger.invalidateDPPort(localPortsIter.next())
        }

        if (null != tunnelPorts && tunnelPorts.length > 0) {
            translatedActions = translatedActions ++ tunnelKey.map { key =>
                FlowActions.setKey(FlowKeys.tunnelID(key))
                    .asInstanceOf[FlowAction[_]]
            } ++ tunnelPorts.map { id =>
                FlowActions.output(id).asInstanceOf[FlowAction[_]]
            }
            val tunnelPortsIter = tunnelPorts.iterator
            while (tunnelPortsIter.hasNext) {
                newTags += FlowTagger.invalidateDPPort(tunnelPortsIter.next())
            }
        }

        val actionsIter = acts.iterator
        while (actionsIter.hasNext) {
            actionsIter.next() match {
                case p: FlowActionOutputToVrnPort if (p.portId == translatablePort) =>
                    newActs ++= translatedActions
                    translatablePort = null
                    if (dpTags != null)
                        dpTags ++= newTags

                case p: FlowActionOutputToVrnPortSet if (p.portSetId == translatablePort) =>
                    newActs ++= translatedActions
                    translatablePort = null
                    if (dpTags != null)
                        dpTags ++= newTags

                // we only translate the first ones.
                case x: FlowActionOutputToVrnPort =>
                case x: FlowActionOutputToVrnPortSet =>

                case a => newActs += a
            }
        }
        newActs
    }

    protected def translateActions(actions: Seq[FlowAction[_]],
                                   inPortUUID: Option[UUID],
                                   dpTags: Option[mutable.Set[Any]],
                                   wMatch: WildcardMatch)
                                  (implicit ec: ExecutionContext,
                                   system: ActorSystem): Future[Option[Seq[FlowAction[_]]]] = {

        val actionsFutures: Seq[Future[Option[Seq[FlowAction[_]]]]] =
            actions map {
                case s: FlowActionOutputToVrnPortSet =>
                    // expandPortSetAction
                    epsa(Seq(s), s.portSetId, inPortUUID, dpTags,
                         wMatch)(ec, system)
                case p: FlowActionOutputToVrnPort =>
                    expandPortAction(Seq(p), p.portId, inPortUUID, dpTags,
                        wMatch)(ec, system)
                case u: FlowActionUserspace =>
                    u.setUplinkPid(datapathConnection.getChannel.getLocalAddress.getPid)
                    Promise.successful(Some(Seq(u)))(ec)
                case a =>
                    Promise.successful(Some(Seq[FlowAction[_]](a)))(ec)
            }
        val actionsResults = Promise[Option[Seq[FlowAction[_]]]]()
        Future.sequence(actionsFutures) flatMap {
            case results =>
                val filteredResults = results.flatMap {
                    case Some(s) => s
                    case None => Nil
                }
                log.debug("Results of translated actions {}", filteredResults)
                actionsResults.success(Some(filteredResults))
        }
        actionsResults.future
    }

    // expandPortSetAction, name shortened to avoid an ENAMETOOLONG on ecryptfs
    // from a triply nested anonfun.
    private def epsa(actions: Seq[FlowAction[_]], portSet: UUID,
                     inPortUUID: Option[UUID], dpTags: Option[mutable.Set[Any]],
                     wMatch: WildcardMatch)
                    (implicit ec: ExecutionContext, system: ActorSystem):
            Future[Option[Seq[FlowAction[_]]]] = {

        val translated = Promise[Option[Seq[FlowAction[_]]]]()

        val portSetFuture = ask(
            VirtualToPhysicalMapper.getRef(),
            PortSetRequest(portSet, update = false)).mapTo[PortSet]

        // The PortSet may be in a bridge, or a vlan-bridge
        val deviceFuture = VirtualTopologyActor
            .expiringAsk(PortSetHolderRequest(portSet, update = false))
            .mapTo[Device]

        portSetFuture map {
            set => deviceFuture onComplete {
                case Right(br) =>
                    val (deviceId, tunnelKey) = br match {
                        case b: RCUBridge =>
                            log.info("It is a bridge")
                            (b.id, b.tunnelKey)
                        case b: VlanAwareBridge =>
                            log.info("It is a vlan bridge")
                            (b.id, b.tunnelKey)
                    }
                    // Don't include the input port in the expanded
                    // port set.
                    var outPorts = set.localPorts
                    log.debug("hosts {}", set.hosts)
                    inPortUUID match {
                        case Some(p) => outPorts -= p
                        case None =>
                    }
                    log.debug("Flooding on (vlan-bridge|bridge) {}. " +
                        "inPort: {},  local ports: {}, remote hosts " +
                        "having ports on it: {}", deviceId, inPortUUID,
                        set.localPorts, set.hosts)
                    // add tag for flow invalidation because if the packet comes
                    // from a tunnel it won't be tagged
                    dpTags match {
                        case None =>
                        case Some(tags) =>
                            tags += FlowTagger.invalidateBroadcastFlows(
                                deviceId, deviceId)
                    }
                    val localPortFutures =
                        outPorts.toSeq map {
                            portID =>
                                VirtualTopologyActor.expiringAsk(
                                    PortRequest(portID, update = false))(ec, system)
                                    .mapTo[client.Port[_]]
                        }

                    Future.sequence(localPortFutures)(Seq.canBuildFrom[client.Port[_]], ec) onComplete {

                        case Right(localPorts) =>
                            applyOutboundFilters(localPorts,
                            portSet, wMatch, dpTags,
                            { portIDs => translated.success(
                                Some(translateToDpPorts(
                                    actions, portSet,
                                    portsForLocalPorts(portIDs),
                                    Some(tunnelKey),
                                    tunnelsForHosts(set.hosts.toSeq),
                                    dpTags.orNull)))
                            })(ec, system)

                        case _ => log.error("Error getting configurations of " +
                                           "local ports of PortSet {}", portSet)
                        translated.success(None)
                    }
                case Left(ex) =>
                    log.warning("Error retrieving portset future {}", portSet)
                    translated.failure(ex)
            }
        }
        translated.future
    }

    private def expandPortAction(actions: Seq[FlowAction[_]],
                                 port: UUID,
                                 inPortUUID: Option[UUID],
                                 dpTags: Option[mutable.Set[Any]],
                                 wMatch: WildcardMatch)
                                (implicit ec: ExecutionContext,
                                 system: ActorSystem): Future[Option[Seq[FlowAction[_]]]] = {

        val translated = Promise[Option[Seq[FlowAction[_]]]]()

        // we need to translate a single port
        dpState.getDpPortNumberForVport(port) match {
            case Some(portNum) =>
                translated.success(Some(translateToDpPorts(
                    actions, port, List(portNum.shortValue()),
                    None, Nil, dpTags.orNull)))
            case None =>
                VirtualTopologyActor.expiringAsk(
                    PortRequest(port, update = false))(ec, system)
                    .mapTo[client.Port[_]] map {
                    case p: ExteriorPort[_] =>
                        translated.success(Some(translateToDpPorts(
                            actions, port, Nil,
                            Some(p.tunnelKey),
                            tunnelsForHosts(List(p.hostID)),
                            dpTags.orNull)))
                }
        }
        translated.future
    }

    protected def tunnelForHost(host: UUID): Option[Short] = {
        dpState.peerToTunnels.get(host).flatMap {
            mappings => mappings.values.headOption.map {
                port => port.getPortNo.shortValue
            }
        }
    }

    protected def tunnelsForHosts(hosts: Seq[UUID]): Seq[Short] = {
        val tunnels = mutable.ListBuffer[Short]()

        val hostIter = hosts.iterator
        while (hostIter.hasNext)
            tunnels ++= tunnelForHost(hostIter.next()).toList

        tunnels
    }
}
