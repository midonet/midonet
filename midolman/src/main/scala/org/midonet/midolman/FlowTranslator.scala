/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.util.UUID

import scala.collection.mutable.ListBuffer
import scala.collection.{Set => ROSet, mutable}

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.util.Timeout

import org.midonet.cluster.client.Port
import org.midonet.midolman.rules.{ChainPacketContext, RuleResult}
import org.midonet.midolman.simulation.{Bridge, Chain}
import org.midonet.midolman.topology.{FlowTagger, VirtualToPhysicalMapper}
import org.midonet.midolman.topology.VirtualTopologyActor.expiringAsk
import org.midonet.odp.flows._
import org.midonet.odp.flows.FlowActions.{setKey, output}
import org.midonet.sdn.flows.VirtualActions
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.util.functors.Callback0

object FlowTranslator {

    /**
     * Dummy ChainPacketContext used in egress port set chains.
     * All that is available is the Output Port ID (there's no information
     * on the ingress port or connection tracking at the egress controller).
     * @param outPortId UUID for the output port
     */
    class EgressPortSetChainPacketContext(override val outPortId: UUID,
            val tags: Option[mutable.Set[Any]]) extends ChainPacketContext {

        override val inPortId = null
        override val portGroups = new java.util.HashSet[UUID]()
        override val isConnTracked = false
        override val isForwardFlow = true
        override val flowCookie = None
        override val parentCookie = None

        override def addTraversedElementID(id: UUID) { }
        override def addFlowRemovedCallback(cb: Callback0) {}

        override def addFlowTag(tag: Any) {
            tags match {
                case None =>
                case Some(tset) => tset += tag
            }
        }

        override def toString: String =
            mutable.StringBuilder.newBuilder
                .append("EgressPortSetChainPacketContext[")
                .append("outPortId=").append(outPortId)
                .append(", tags=").append(tags)
                .append("]").toString()
    }
}

trait FlowTranslator {

    import FlowTranslator._
    import VirtualToPhysicalMapper.PortSetRequest
    import VirtualActions._

    protected val dpState: DatapathState

    implicit protected val requestReplyTimeout: Timeout
    implicit protected def system: ActorSystem
    implicit protected def executor = system.dispatcher

    val log: LoggingAdapter
    val cookieStr: String

    protected def translateVirtualWildcardFlow(flow: WildcardFlow,
                                               tags: ROSet[Any] = Set.empty)
    : Urgent[(WildcardFlow, ROSet[Any])] = {

        val wcMatch = flow.getMatch
        val inPortId = wcMatch.getInputPortUUID

        // tags can be null
        val dpTags = new mutable.HashSet[Any]
        if (tags != null)
            dpTags ++= tags

        translateActions(
            flow.getActions, Option(inPortId), Option(dpTags), wcMatch
        ) map { translated =>
            (WildcardFlow(wcmatch = wcMatch,
                          actions = translated.toList,
                          priority = flow.priority,
                          hardExpirationMillis = flow.hardExpirationMillis,
                          idleExpirationMillis = flow.idleExpirationMillis),
             dpTags)
        }
    }

    protected def portsForLocalPorts(localVrnPorts: Seq[UUID]): Seq[Short] = {
        localVrnPorts flatMap { portNo =>
            dpState.getDpPortNumberForVport(portNo) match {
                case Some(value) => Some(value.shortValue())
                case None =>
                    log.warning("Port number {} not found {}", portNo, cookieStr)
                    None
            }
        }
    }

    protected def applyOutboundFilters(localPorts: Seq[Port],
                                       portSetID: UUID,
                                       pktMatch: WildcardMatch,
                                       tags: Option[mutable.Set[Any]])
    : Urgent[Seq[UUID]] = {
        def chainMatch(port: Port, chain: Chain): Boolean = {
            val fwdInfo = new EgressPortSetChainPacketContext(port.id, tags)
            Chain.apply(chain, fwdInfo, pktMatch, port.id, true).action match {
                case RuleResult.Action.ACCEPT =>
                    true
                case RuleResult.Action.DROP | RuleResult.Action.REJECT =>
                    false
                case other =>
                    log.error("Applying chain {} produced {} which was not " +
                        "ACCEPT, DROP, or REJECT {}", chain.id, other, cookieStr)
                    false
            }
        }

        def chainsToPortsId(chains: Seq[Chain]): Seq[UUID] =
            for { (p, c) <- localPorts zip chains if chainMatch(p, c) }
                yield p.id

        def portToChain(port: Port): Urgent[Chain] =
            if (port.outboundFilter == null)
                Ready(null)
            else
                expiringAsk[Chain](port.outboundFilter)

        Urgent flatten (localPorts map portToChain) map chainsToPortsId
    }

    /**
     * Translates a list of FlowActions into a format that Open vSwitch can
     * understand, and updates DP tags. Handles two types of FlowActions:
     * FlowActions to local ports and FlowActions to peer hosts. For local port
     * flows, generates a FlowActionOutput per each local host. For flows to
     * peer hosts, generates a FlowActionSetKey per peer host to set up
     * flow-based tunneling, with each FlowActionSetKey followed by a
     * FlowActionOutput as required by Open vSwitch.
     *
     * If a non-null set of data path tags are given, this updates it with
     * corresponding flow invalidation tags.
     */
    private def translateFlowActions(
            localPorts: Seq[Short], tunnelKey: Option[Long],
            peerHostIds: Set[UUID], dpTags: mutable.Set[Any]): Seq[FlowAction] = {

        // TODO(pino): when we detect the flow won't have output actions,
        // set the flow to expire soon so that we can retry.
        if (localPorts.isEmpty && peerHostIds.isEmpty)
            log.warning("No local datapath ports or tunnels found. Expected " +
                        "only for forked actions, otherwise this flow will " +
                        "be dropped because we cannot make Output actions.")

        val tags = if (dpTags == null) mutable.Set[Any]() else dpTags
        val actions = ListBuffer[FlowAction]()
        outputActionsForLocalPorts(localPorts, actions, tags)
        if (tunnelKey.isDefined && dpState.greOutputAction.isDefined) {
            outputActionsToPeers(tunnelKey.get, peerHostIds,
                                 dpState.greOutputAction.get,
                                 actions, tags)
        }
        actions.toList
    }

    /** translates a Seq of FlowActions expressed in virtual references into a
     *  Seq of FlowActions expressed in physical references. Returns the
     *  results as an Urgent. */
    protected def translateActions(actions: Seq[FlowAction],
                                   inPortUUID: Option[UUID],
                                   dpTags: Option[mutable.Set[Any]],
                                   wMatch: WildcardMatch)
    : Urgent[Seq[FlowAction]] = {

        if (inPortUUID.isDefined) {
            dpState.getDpPortNumberForVport(inPortUUID.get) match {
                case Some(portNo) =>
                    wMatch.unsetInputPortUUID() // Not used for flow matching
                    wMatch.setInputPortNumber(portNo.shortValue)
                    if (dpTags.isDefined)
                        dpTags.get += FlowTagger.invalidateDPPort(portNo.shortValue)
                case None =>
                    // Return, as the flow is no longer valid.
                    return Ready(Seq.empty)
            }
        }

        val translatedActs = Urgent.flatten (
            actions map {
                case s: FlowActionOutputToVrnPortSet =>
                    // expandPortSetAction
                    epsa(s.portSetId, inPortUUID, dpTags, wMatch)
                case p: FlowActionOutputToVrnPort =>
                    expandPortAction(p.portId, dpTags)
                case a =>
                    Ready(Seq[FlowAction](a))
            }
        )

        translatedActs match {
            case NotYet(_) =>
                log.debug("Won't translate actions: required data is not local")
            case Ready(r) =>
                log.debug("Translated actions to: {} {}", r, cookieStr)
        }

        translatedActs map { acts => acts.flatten }
    }

    /** Update the list of action and list of tags with the output actions
     *  for the given list of local datapath port numbers. */
    private def outputActionsForLocalPorts(ports: Seq[Short],
                                           actions: ListBuffer[FlowAction],
                                           dpTags: mutable.Set[Any]) {
        val iter = ports.iterator
        while (iter.hasNext) {
            val portNo = iter.next
            actions += output(portNo)
            dpTags += FlowTagger invalidateDPPort portNo
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  actions for the given list of remote host and tunnel key. */
    def outputActionsToPeers(key: Long, peerIds: Set[UUID], output: FlowAction,
                             actions: ListBuffer[FlowAction],
                             dpTags: mutable.Set[Any]) {
        val peerIter = peerIds.iterator
        while (peerIter.hasNext) {
            val peer = peerIter.next
            dpState.peerTunnelInfo(peer) match {
                case None => log.warning("Unable to tunnel to peer UUID" +
                    " {} - check that the peer host is in the same tunnel" +
                    " zone as the current node.", peer)
                case Some(route) =>
                    dpTags += FlowTagger.invalidateTunnelPort(route)
                    // Each FlowActionSetKey must be followed by a corresponding
                    // FlowActionOutput.
                    actions += setKey(FlowKeys.tunnel(key, route._1, route._2))
                    actions += output
            }
        }
    }

    // expandPortSetAction, name shortened to avoid an ENAMETOOLONG on ecryptfs
    // from a triply nested anonfun.
    private def epsa(portSetId: UUID,
                     inPortUUID: Option[UUID], dpTags: Option[mutable.Set[Any]],
                     wMatch: WildcardMatch): Urgent[Seq[FlowAction]] = {

        val portSet = VirtualToPhysicalMapper expiringAsk
                        PortSetRequest(portSetId, update = false)

        val device = expiringAsk[Bridge](portSetId, log)

        portSet flatMap { set => device flatMap { br =>
            val deviceId = br.id
            val tunnelKey = br.tunnelKey

            log.debug("Flooding on bridge {} to {} from inPort: {} {}",
                deviceId, set, inPortUUID, cookieStr)

            // Don't include the input port in the expanded port set.
            var outPorts = set.localPorts
            inPortUUID match {
                case Some(p) => outPorts -= p
                case None =>
            }
            // add tag for flow invalidation because if the packet comes
            // from a tunnel it won't be tagged
            dpTags match {
                case None =>
                case Some(tags) =>
                    tags += FlowTagger.invalidateBroadcastFlows(deviceId,
                        deviceId)
            }

            activePorts(outPorts, dpTags.orNull) flatMap {
                localPorts =>
                    applyOutboundFilters(localPorts, portSetId, wMatch, dpTags)
            } map {
                portIDs =>
                    toPortSet(portsForLocalPorts(portIDs),
                        Some(tunnelKey), set.hosts, dpTags.orNull)
            }

        }}

    }

    /**
     * Retrieves all the given ports, if they are all immediately available
     * it will filter the active ones and return them. Otherwise it will return
     * a NotYet wrapping the future that completes all required ports.
     *
     * @param portIds the desired ports
     * @param dpTags will be mutated adding invalidateFlowsByDevice tags for
     *               each port id that is examined
     * @return all the active ports, already fetched, or the future that
     *         prevents us from achieving it
     */
    protected def activePorts(portIds: Set[UUID],
                              dpTags: mutable.Set[Any])
    : Urgent[Seq[Port]] = {

        portIds map {
            id => expiringAsk[Port](id, log)
        } partition { _.isReady } match {   // partition by Ready / NonYet
            case (ready, nonReady) if nonReady.isEmpty =>
                val active = ready filter {
                    case Ready(p) if p != null =>
                        if (dpTags != null)
                            dpTags += FlowTagger.invalidateFlowsByDevice(p.id)
                        p.adminStateUp
                    case _ => false // should not happen
                }
                Urgent.flatten(active.toSeq)
            case (_, nonReady) =>              // Some ports not cached, ensure
                Urgent.flatten(nonReady.toSeq) // we report all missing futures
        }
    }

    private def expandPortAction(port: UUID,
                                 dpTags: Option[mutable.Set[Any]])
    : Urgent[Seq[FlowAction]] = {

        val tags = dpTags.orNull

        dpState.getDpPortNumberForVport(port) map { portNum =>
            // if the DPC has a local DP port for this UUUID, translate
            Urgent(
                towardsLocalDpPorts(List(portNum.shortValue()), tags)
            )
        } getOrElse {
            // otherwise we translate to a remote port
            expiringAsk[Port](port, log) map {
                case p: Port if p.isExterior =>
                    towardsRemoteHosts(p.tunnelKey, p.hostID, tags)
                case _ =>
                    log.warning("Port {} was not exterior {}", port, cookieStr)
                    Seq.empty[FlowAction]
            }
        }
    }

    /** forwards to translateToDpPorts for a set of local ports. */
    protected def towardsLocalDpPorts(localPorts: Seq[Short],
                                      dpTags: mutable.Set[Any]) = {
        log.debug("Emitting towards local dp ports {}", localPorts)
        translateFlowActions(localPorts, None, Set.empty, dpTags)
    }

    /** forwards to translateToDpPorts for a set of remote ports. */
    private def towardsRemoteHosts(tunnelKey: Long, peerHostId: UUID,
                                   dpTags: mutable.Set[Any]) = {
        log.debug("Emitting towards remote host {} with tunnek key {}",
                  peerHostId, tunnelKey)
        translateFlowActions(Nil, Some(tunnelKey), Set(peerHostId), dpTags)
    }

    /** forwards to translateToDpPorts for a port set. */
    private def toPortSet(localPorts: Seq[Short], tunnelKey: Option[Long],
                          peerHostIds: Set[UUID], dpTags: mutable.Set[Any]) = {
        log.debug("Emitting towards local dp ports {} and remote hosts {} " +
                  "with tunnel key {}", localPorts, peerHostIds, tunnelKey)
        translateFlowActions(localPorts, tunnelKey, peerHostIds, dpTags)
    }

}
