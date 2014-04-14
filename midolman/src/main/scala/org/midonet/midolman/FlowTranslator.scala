/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.lang.{Integer => JInteger}
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.collection.{Set => ROSet, mutable}

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.util.Timeout

import org.midonet.cluster.client.Port
import org.midonet.midolman.rules.{ChainPacketContext, RuleResult}
import org.midonet.midolman.simulation.{Bridge, Chain}
import org.midonet.midolman.topology.VirtualTopologyActor.expiringAsk
import org.midonet.midolman.topology.{FlowTagger, VirtualToPhysicalMapper}
import org.midonet.odp.flows.FlowActions.{setKey, output, userspace}
import org.midonet.odp.flows._
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
            val tags: mutable.Set[Any]) extends ChainPacketContext {

        override val inPortId = null
        override val portGroups = new java.util.HashSet[UUID]()
        override val isConnTracked = false
        override val isForwardFlow = true
        override val flowCookie = None
        override val parentCookie = None

        override def addTraversedElementID(id: UUID) { }
        override def addFlowRemovedCallback(cb: Callback0) {}

        override def addFlowTag(tag: Any) {
            tags += tag
        }

        override def toString: String =
            mutable.StringBuilder.newBuilder
                .append("EgressPortSetChainPacketContext[")
                .append("outPortId=").append(outPortId)
                .append(", tags=").append(tags)
                .append("]").toString()
    }

    val NotADpPort: JInteger = -1
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
                                               tags: ROSet[Any])
    : Urgent[(WildcardFlow, ROSet[Any])] = {

        val wcMatch = flow.getMatch
        val inPortId = wcMatch.getInputPortUUID

        val dpTags = mutable.HashSet[Any]()
        if (tags != null)
            dpTags ++= tags // tags can be null

        translateActions(flow.getActions, Option(inPortId), dpTags, wcMatch)
            .map { translated =>
                (WildcardFlow(wcmatch = wcMatch,
                              actions = translated.toList,
                              priority = flow.priority,
                              hardExpirationMillis = flow.hardExpirationMillis,
                              idleExpirationMillis = flow.idleExpirationMillis),
                 dpTags)
            }
    }


    /** Applies filters on a sequence of virtual port uuids for the given
     *  wildcard match and return a sequence of corresponding datapath port
     *  index numbers for passing chains, or -1 otherwise for non passing chains
     *  and unknown datapath ports. */
    protected def applyOutboundFilters(localPorts: Seq[Port],
                                       pktMatch: WildcardMatch,
                                       tags: mutable.Set[Any]) = {
        def applyChainOn(chain: Chain, port: Port): JInteger = {
            val fwdInfo = new EgressPortSetChainPacketContext(port.id, tags)
            Chain.apply(chain, fwdInfo, pktMatch, port.id, true).action match {
                case RuleResult.Action.ACCEPT =>
                    dpState.getDpPortNumberForVport(port.id)
                           .getOrElse(NotADpPort)
                case RuleResult.Action.DROP | RuleResult.Action.REJECT =>
                    NotADpPort
                case other =>
                    log.error("Applying chain {} produced {} which " +
                              "was not ACCEPT, DROP, or REJECT {}",
                              chain.id, other, cookieStr)
                    NotADpPort
            }
        }
        Urgent flatten localPorts.map {
            port =>
                val filterId = port.outboundFilter
                if (filterId == null) {
                    val portNo = dpState.getDpPortNumberForVport(port.id)
                                        .getOrElse(NotADpPort)
                    Ready(portNo)
                } else {
                    expiringAsk[Chain](filterId) map { applyChainOn(_, port) }
                }
        }
    }

    /** translates a Seq of FlowActions expressed in virtual references into a
     *  Seq of FlowActions expressed in physical references. Returns the
     *  results as an Urgent. */
    protected def translateActions(actions: Seq[FlowAction],
                                   inPortUUID: Option[UUID],
                                   dpTags: mutable.Set[Any],
                                   wMatch: WildcardMatch)
    : Urgent[Seq[FlowAction]] = {

        if (inPortUUID.isDefined) {
            dpState.getDpPortNumberForVport(inPortUUID.get) match {
                case Some(portNo) =>
                    wMatch.unsetInputPortUUID() // Not used for flow matching
                    wMatch.setInputPortNumber(portNo.shortValue)
                    dpTags += FlowTagger.invalidateDPPort(portNo.shortValue)
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
    private def outputActionsForLocalPorts(ports: Seq[JInteger],
                                           actions: ListBuffer[FlowAction],
                                           dpTags: mutable.Set[Any]) {
        val iter = ports.iterator
        while (iter.hasNext) {
            val portNo = iter.next
            if (portNo != NotADpPort) {
                actions += output(portNo)
                dpTags += FlowTagger invalidateDPPort portNo
            }
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  actions for the given list of remote host and tunnel key. */
    def outputActionsToPeers(key: Long, peerIds: Set[UUID],
                             actions: ListBuffer[FlowAction],
                             dpTags: mutable.Set[Any]) {
        if (dpState.greOutputAction.isEmpty) {
            log.warning("Gre Tunnel port was not found, could not translate " +
                        "action to remote hosts {}", peerIds)
            return
        }
        val peerIter = peerIds.iterator
        val output = dpState.greOutputAction.get
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
                     inPortUUID: Option[UUID], dpTags: mutable.Set[Any],
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
            dpTags += FlowTagger.invalidateBroadcastFlows(deviceId, deviceId)

            activePorts(outPorts, dpTags) flatMap {
                localPorts =>
                    applyOutboundFilters(localPorts, wMatch, dpTags)
            } map {
                portNumbers =>
                    towardsPortSet(portNumbers, tunnelKey, set.hosts, dpTags)
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
    protected def activePorts(portIds: Set[UUID], dpTags: mutable.Set[Any])
    : Urgent[Seq[Port]] = {

        portIds map {
            id => expiringAsk[Port](id, log)
        } partition { _.isReady } match {   // partition by Ready / NonYet
            case (ready, nonReady) if nonReady.isEmpty =>
                val active = ready filter {
                    case Ready(p) if p != null =>
                        dpTags += FlowTagger.invalidateFlowsByDevice(p.id)
                        p.adminStateUp
                    case _ => false // should not happen
                }
                Urgent.flatten(active.toSeq)
            case (_, nonReady) =>              // Some ports not cached, ensure
                Urgent.flatten(nonReady.toSeq) // we report all missing futures
        }
    }

    private def expandPortAction(port: UUID, dpTags: mutable.Set[Any])
    : Urgent[Seq[FlowAction]] =
        dpState.getDpPortNumberForVport(port) map { portNum =>
            // if the DPC has a local DP port for this UUUID, translate
            Urgent(
                towardsLocalDpPorts(List(portNum.shortValue()), dpTags)
            )
        } getOrElse {
            // otherwise we translate to a remote port
            expiringAsk[Port](port, log) map {
                case p: Port if p.isExterior =>
                    towardsRemoteHost(p.tunnelKey, p.hostID, dpTags)
                case _ =>
                    log.warning("Port {} was not exterior {}", port, cookieStr)
                    Seq.empty[FlowAction]
            }
        }

    /** Generates a list of output FlowActions for the given list of local
     *  datapath ports. */
    protected def towardsLocalDpPorts(localPorts: Seq[JInteger],
                                      dpTags: mutable.Set[Any])
    : Seq[FlowAction] = {
        log.debug("Emitting towards local dp ports {}", localPorts)
        val actions = ListBuffer[FlowAction]()
        outputActionsForLocalPorts(localPorts, actions, dpTags)
        actions
    }

    /** Emits a list of output FlowActions for tunnelling traffic to the given
     *  remote host with the given tunnel key. */
    private def towardsRemoteHost(tunnelKey: Long, peerHostId: UUID,
                                  dpTags: mutable.Set[Any])
    : Seq[FlowAction] = {
        log.debug("Emitting towards remote host {} with tunnek key {}",
                  peerHostId, tunnelKey)
        val actions = ListBuffer[FlowAction]()
        outputActionsToPeers(tunnelKey, Set(peerHostId), actions, dpTags)
        actions
    }

    /** Generates a list of output FlowActions for forwarding traffic to a
     *  logical port set, both locally to local dp ports and to remote hosts
     *  through tunnels. */
    private def towardsPortSet(localPorts: Seq[JInteger], tunnelKey: Long,
                               peerHostIds: Set[UUID], dpTags: mutable.Set[Any])
    : Seq[FlowAction] = {
        log.debug("Emitting towards local dp ports {} and remote hosts {} " +
                  "with tunnel key {}", localPorts, peerHostIds, tunnelKey)
        val actions = ListBuffer[FlowAction]()
        outputActionsForLocalPorts(localPorts, actions, dpTags)
        outputActionsToPeers(tunnelKey, peerHostIds, actions, dpTags)
        actions
    }

}
