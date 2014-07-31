/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.lang.{Integer => JInteger}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ListBuffer
import scala.collection.mutable

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.util.Timeout

import org.midonet.cluster.client.{VxLanPort, Port}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.{PacketContext, Bridge, Chain}
import org.midonet.midolman.topology.VirtualTopologyActor.expiringAsk
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.sdn.flows.{FlowTagger, WildcardFlow}
import FlowTagger.FlowTag
import org.midonet.odp.flows.FlowActions.{setKey, output}
import org.midonet.odp.flows._
import org.midonet.packets.{ICMP, IPv4, Ethernet, IPv4Addr}
import org.midonet.sdn.flows.VirtualActions

object FlowTranslator {
    val NotADpPort: JInteger = -1
}

trait FlowTranslator {

    import FlowTranslator._
    import VirtualToPhysicalMapper.PortSetRequest
    import VirtualActions._

    protected val dpState: DatapathState

    implicit protected val requestReplyTimeout = new Timeout(5, TimeUnit.SECONDS)
    implicit protected def system: ActorSystem
    implicit protected def executor = system.dispatcher

    val log: LoggingAdapter

    protected def translateVirtualWildcardFlow(pktCtx: PacketContext,
                                               flow: WildcardFlow)
    : Urgent[WildcardFlow] = {
        translateActions(pktCtx, flow.getActions) map { translated =>
            WildcardFlow(wcmatch = flow.getMatch,
                         actions = translated.toList,
                         priority = flow.priority,
                         hardExpirationMillis = flow.hardExpirationMillis,
                         idleExpirationMillis = flow.idleExpirationMillis)
        }
    }


    /** Applies filters on a sequence of virtual port uuids for the given
     *  wildcard match and return a sequence of corresponding datapath port
     *  index numbers for passing chains, or -1 otherwise for non passing chains
     *  and unknown datapath ports. */
    protected def applyOutboundFilters(localPorts: Seq[Port],
                                       pktCtx: PacketContext) = {
        def applyChainOn(chain: Chain, port: Port): JInteger = {
            pktCtx.outPortId = port.id
            Chain.apply(chain, pktCtx, port.id, true).action match {
                case RuleResult.Action.ACCEPT =>
                    dpState.getDpPortNumberForVport(port.id)
                           .getOrElse(NotADpPort)
                case RuleResult.Action.DROP | RuleResult.Action.REJECT =>
                    NotADpPort
                case other =>
                    log.error("Applying chain {} produced {} which " +
                              "was not ACCEPT, DROP, or REJECT {}",
                              chain.id, other, pktCtx.cookieStr)
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
    def translateActions(pktCtx: PacketContext, actions: Seq[FlowAction])
    : Urgent[Seq[FlowAction]] = {

        val translatedActs = Urgent.flatten (
            actions map {
                case s: FlowActionOutputToVrnPortSet =>
                    // expandPortSetAction
                    epsa(s.portSetId, pktCtx)
                case p: FlowActionOutputToVrnPort =>
                    expandPortAction(p.portId, pktCtx)
                case a: FlowActionSetKey =>
                    a.getFlowKey match {
                        case k: FlowKeyICMPError =>
                            mangleIcmp(pktCtx.ethernet, k.getIcmpData)
                            Ready(Nil)
                        case k: FlowKeyICMPEcho =>
                            Ready(Nil)
                        case _ =>
                            Ready(Seq[FlowAction](a))
                    }
                case a =>
                    Ready(Seq[FlowAction](a))
            }
        )

        translatedActs match {
            case NotYet(_) =>
                log.debug("Won't translate actions: required data is not local")
            case Ready(r) =>
                log.debug("Translated actions to: {} {}", r, pktCtx.cookieStr)
        }

        translatedActs map { acts => acts.flatten }
    }

    // This is very limited but we don't really need more
    // This method takes a Ethernet packet and modifies it if it carries an
    // icmp payload
    private def mangleIcmp(eth: Ethernet, data: Array[Byte]) {
        eth.getPayload match {
            case ipv4: IPv4 =>
                ipv4.getPayload match {
                    case icmp: ICMP =>
                        icmp.setData(data)
                    case _ =>
                }
            case _ =>
        }
    }

    /** Update the list of action and list of tags with the output actions
     *  for the given list of local datapath port numbers. */
    private def outputActionsForLocalPorts(ports: Seq[JInteger],
                                           actions: ListBuffer[FlowAction],
                                           dpTags: mutable.Set[FlowTag]) {
        val iter = ports.iterator
        while (iter.hasNext) {
            val portNo = iter.next()
            if (portNo != NotADpPort) {
                actions += output(portNo)
                dpTags += FlowTagger tagForDpPort portNo
            }
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  actions for the given list of remote host and tunnel key. */
    def outputActionsToPeers(key: Long, peerIds: Set[UUID],
                             actions: ListBuffer[FlowAction],
                             dpTags: mutable.Set[FlowTag]) {
        val peerIter = peerIds.iterator
        while (peerIter.hasNext) {
            val peer = peerIter.next()
            val routeInfo = dpState.peerTunnelInfo(peer)
            if (routeInfo.isEmpty) {
                log.warning("Unable to tunnel to peer {}, is the peer in the " +
                            "same tunnel zone as the current node ?", peer)
            } else {
                val src = routeInfo.get.srcIp
                val dst = routeInfo.get.dstIp
                dpTags += FlowTagger.tagForTunnelRoute(src, dst)
                // Each FlowActionSetKey must be followed by a corresponding
                // FlowActionOutput.
                actions += setKey(FlowKeys.tunnel(key, src, dst))
                actions += routeInfo.get.output
            }
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  action for the given vtep tunnel addr and vni key. The tzId is the
     *  id of the tunnel zone specified by this VTEP's config which allows
     *  us to determine which of the host's IP we should use */
    private def outputActionsToVtep(vni: Int, vtepIp: Int, tzId: UUID,
                                    actions: ListBuffer[FlowAction],
                                    dpTags: mutable.Set[FlowTag]) {
        val tzMembership = dpState.host.zones.get(tzId)
        if (tzMembership eq None) {
            log.warning("Can't output to VTEP with tunnel IP: {}, host not in "
                        + "VTEP's tunnel zone: {}", vtepIp, tzId)
            return
        }
        val localIp =  tzMembership.get.getIp.toInt
        dpTags += FlowTagger.tagForTunnelRoute(localIp, vtepIp)
        actions += setKey(FlowKeys.tunnel(vni.toLong, localIp, vtepIp))
        actions += dpState.vtepTunnellingOutputAction
    }

    // expandPortSetAction, name shortened to avoid an ENAMETOOLONG on ecryptfs
    // from a triply nested anonfun.
    private def epsa(portSetId: UUID,
                     pktCtx: PacketContext): Urgent[Seq[FlowAction]] = {
        def addLocalActions(localPorts: Set[UUID],
                            actions: ListBuffer[FlowAction]) = {
            val outputPorts = localPorts - pktCtx.inputPort
            activePorts(outputPorts, pktCtx.flowTags) flatMap {
                ports =>
                    applyOutboundFilters(ports, pktCtx)
            } map {
                ports =>
                    outputActionsForLocalPorts(ports, actions, pktCtx.flowTags)
                    actions
            }
        }
        def addRemoteActions(br: Bridge, peerIds: Set[UUID],
                             actions: ListBuffer[FlowAction]) {
            // add flow invalidation tag because if the packet
            // comes from a tunnel it won't be tagged
            pktCtx.addFlowTag(FlowTagger.tagForBroadcast(br.id, br.id))
            outputActionsToPeers(br.tunnelKey, peerIds, actions, pktCtx.flowTags)
        }

        /* This is an awkward step, but necessary. After we figure out all the
         * actions for local and remote ports, we need to consider the case
         * where portset includes a bridge's VxLanPort. What we want is
         * - If there is no VxLanPort, do nothing
         * - If there is, but it was the ingress port, do nothing
         * - Else, fetch the destination VTEP and VNI, craft the output action
         *   through the dpPort dedicated to vxLan tunnels to VTEPs, and inject
         *   this action in the result set
         */
        def addVtepActions(br: Bridge, actions: ListBuffer[FlowAction])
        : Urgent[Seq[FlowAction]] = {
            if (br.vxlanPortId.isEmpty || br.vxlanPortId.get == pktCtx.inputPort) {
                Ready(actions)
            } else {
                val vxlanPortId = br.vxlanPortId.get
                expiringAsk[Port](vxlanPortId, log) map {
                    case p: VxLanPort =>
                        outputActionsToVtep(p.vni, p.vtepTunAddr.addr,
                                            p.tunnelZoneId, actions,
                                            pktCtx.flowTags)
                        actions
                    case _ =>
                        log.warning("could not find VxLanPort {} " +
                                    "of bridge {}", vxlanPortId, br)
                        actions
                }
            }
        }
        val req = PortSetRequest(portSetId, update = false)
        VirtualToPhysicalMapper.expiringAsk(req) flatMap {
            portSet =>
                val actions = ListBuffer[FlowAction]()
                addLocalActions(portSet.localPorts, actions) flatMap {
                    actions =>
                        expiringAsk[Bridge](portSetId, log) flatMap {
                            br =>
                                addRemoteActions(br, portSet.hosts, actions)
                                // FIXME: at the moment (v1.5), this is need for
                                // flooding traffic from a bridge. With mac
                                // syncing, it will become unnecessary.
                                addVtepActions(br, actions)
                        }
                }
        }
    }

    /**
     * Retrieves all the given ports, if they are all immediately available
     * it will filter the active ones and return them. Otherwise it will return
     * a NotYet wrapping the future that completes all required ports.
     *
     * @param portIds the desired ports
     * @param dpTags will be mutated adding tagForDevice tags for
     *               each port id that is examined
     * @return all the active ports, already fetched, or the future that
     *         prevents us from achieving it
     */
    protected def activePorts(portIds: Set[UUID], dpTags: mutable.Set[FlowTag])
    : Urgent[Seq[Port]] = {
        portIds map {
            id => expiringAsk[Port](id, log)
        } partition { _.isReady } match {   // partition by Ready / NonYet
            case (ready, nonReady) if nonReady.isEmpty =>
                val active = ready filter {
                    case Ready(p) if p != null =>
                        dpTags += FlowTagger.tagForDevice(p.id)
                        p.adminStateUp
                    case _ => false // should not happen
                }
                Urgent.flatten(active.toSeq)
            case (_, nonReady) =>              // Some ports not cached, ensure
                Urgent.flatten(nonReady.toSeq) // we report all missing futures
        }
    }

    private def expandPortAction(port: UUID, pktCtx: PacketContext)
    : Urgent[Seq[FlowAction]] =
        dpState.getDpPortNumberForVport(port) map { portNum =>
            // If the DPC has a local DP port for this UUID, translate
            Urgent(
                towardsLocalDpPorts(List(portNum.shortValue()), pktCtx.flowTags)
            )
        } getOrElse {
            // Otherwise we translate to a remote port or a vtep peer
            // VxLanPort is a subtype of exterior port,
            // therefore it needs to be matched first.
            expiringAsk[Port](port, log) map {
                case p: VxLanPort =>
                    towardsVtepPeer(p.vni, p.vtepTunAddr,
                                    p.tunnelZoneId, pktCtx.flowTags)
                case p: Port if p.isExterior =>
                    towardsRemoteHost(p.tunnelKey, p.hostID, pktCtx.flowTags)
                case _ =>
                    log.warning("Port {} was not exterior {}", port, pktCtx.cookieStr)
                    Seq.empty[FlowAction]
            }
        }

    /** Generates a list of output FlowActions for the given list of local
     *  datapath ports. */
    protected def towardsLocalDpPorts(localPorts: Seq[JInteger],
                                      dpTags: mutable.Set[FlowTag])
    : Seq[FlowAction] = {
        log.debug("Emitting towards local dp ports {}", localPorts)
        val actions = ListBuffer[FlowAction]()
        outputActionsForLocalPorts(localPorts, actions, dpTags)
        actions
    }

    /** Emits a list of output FlowActions for tunnelling traffic to the given
     *  remote host with the given tunnel key. */
    private def towardsRemoteHost(tunnelKey: Long, peerHostId: UUID,
                                  dpTags: mutable.Set[FlowTag]): Seq[FlowAction] = {
        log.debug("Emitting towards remote host {} with tunnek key {}",
                  peerHostId, tunnelKey)
        val actions = ListBuffer[FlowAction]()
        outputActionsToPeers(tunnelKey, Set(peerHostId), actions, dpTags)
        actions
    }

    /** Emits a list of output FlowActions for tunnelling traffic to a remote
     *  vtep gateway given its ip address and a vni key. */
    private def towardsVtepPeer(vni: Int, vtepTunIp: IPv4Addr, tzId: UUID,
                                dpTags: mutable.Set[FlowTag]): Seq[FlowAction] = {
        log.debug("Emitting towards vtep at {} with vni {}", vtepTunIp, vni)
        val actions = ListBuffer[FlowAction]()
        outputActionsToVtep(vni, vtepTunIp.addr, tzId, actions, dpTags)
        actions
    }
}
