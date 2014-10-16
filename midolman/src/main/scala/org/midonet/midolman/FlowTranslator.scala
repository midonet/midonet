/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman

import java.lang.{Integer => JInteger}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ListBuffer
import scala.collection.mutable

import akka.actor.ActorSystem
import akka.util.Timeout

import org.midonet.cluster.client.{VxLanPort, Port}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.{PacketContext, Bridge, Chain}
import org.midonet.midolman.topology.VirtualTopologyActor.tryAsk
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

    protected def translateVirtualWildcardFlow(context: PacketContext,
                                               flow: WildcardFlow)
    : WildcardFlow = {
        val translated = translateActions(context, flow.getActions)
        WildcardFlow(wcmatch = flow.getMatch,
                     actions = translated.toList,
                     priority = flow.priority,
                     hardExpirationMillis = flow.hardExpirationMillis,
                     idleExpirationMillis = flow.idleExpirationMillis)
    }


    /** Applies filters on a sequence of virtual port uuids for the given
     *  wildcard match and return a sequence of corresponding datapath port
     *  index numbers for passing chains, or -1 otherwise for non passing chains
     *  and unknown datapath ports. */
    protected def applyOutboundFilters(localPorts: Seq[Port],
                                       context: PacketContext): Seq[Integer] = {
        def applyChainOn(chain: Chain, port: Port): JInteger = {
            context.outPortId = port.id
            Chain.apply(chain, context, port.id, true).action match {
                case RuleResult.Action.ACCEPT =>
                    dpState.getDpPortNumberForVport(port.id)
                           .getOrElse(NotADpPort)
                case RuleResult.Action.DROP | RuleResult.Action.REJECT =>
                    NotADpPort
                case other =>
                    context.log.error("Applying chain {} produced {} which " +
                                      "was not ACCEPT, DROP, or REJECT",
                                      chain.id, other)
                    NotADpPort
            }
        }

        val outPortSet = context.outPortId
        val result = localPorts map { port =>
                val filterId = port.outboundFilter
                if (filterId == null) {
                    val portNo = dpState.getDpPortNumberForVport(port.id)
                                        .getOrElse(NotADpPort)
                    portNo
                } else {
                    applyChainOn(tryAsk[Chain](filterId), port)
                }
        }
        context.outPortId = outPortSet
        result
    }

    /**
     * Translates a Seq of FlowActions expressed in virtual references into a
     * Seq of FlowActions expressed in physical references.
     */
    def translateActions(context: PacketContext, actions: Seq[FlowAction])
    : Seq[FlowAction] = {

        val translatedActs = actions map {
                case s: FlowActionOutputToVrnPortSet =>
                    // expandPortSetAction
                    epsa(s.portSetId, context)
                case p: FlowActionOutputToVrnPort =>
                    expandPortAction(p.portId, context)
                case a: FlowActionSetKey =>
                    a.getFlowKey match {
                        case k: FlowKeyICMPError =>
                            mangleIcmp(context.ethernet, k.getIcmpData)
                            Nil
                        case k: FlowKeyICMPEcho =>
                            Nil
                        case _ =>
                            Seq[FlowAction](a)
                    }
                case a =>
                    Seq[FlowAction](a)
            }

        context.log.debug("Translated actions to: {}", translatedActs)
        translatedActs.flatten
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
                             dpTags: mutable.Set[FlowTag],
                             context: PacketContext) {
        val peerIter = peerIds.iterator
        while (peerIter.hasNext) {
            val peer = peerIter.next()
            val routeInfo = dpState.peerTunnelInfo(peer)
            if (routeInfo.isEmpty) {
                context.log.warn("Unable to tunnel to peer {}, is the peer "+
                    "in the same tunnel zone as the current node?", peer)
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
                                    dpTags: mutable.Set[FlowTag],
                                    context: PacketContext): Unit = {
        val tzMembership = dpState.host.zones.get(tzId)
        if (tzMembership eq None) {
            context.log.warn(s"Can't output to VTEP with tunnel IP: $vtepIp, host not in "
                             + s"VTEP's tunnel zone: $tzId")
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
                     context: PacketContext): Seq[FlowAction] = {
        def addLocalActions(localPorts: Set[UUID],
                            actions: ListBuffer[FlowAction]): Unit = {
            val outputPorts = localPorts - context.inputPort
            val ports = activePorts(outputPorts, context.flowTags, context)
            val filteredPorts = applyOutboundFilters(ports, context)
            outputActionsForLocalPorts(filteredPorts, actions, context.flowTags)
        }

        def addRemoteActions(br: Bridge, peerIds: Set[UUID],
                             actions: ListBuffer[FlowAction]) {
            // add flow invalidation tag because if the packet
            // comes from a tunnel it won't be tagged
            context.addFlowTag(FlowTagger.tagForBroadcast(br.id, br.id))
            outputActionsToPeers(br.tunnelKey, peerIds, actions,
                                 context.flowTags, context)
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
        def addVtepActions(br: Bridge, actions: ListBuffer[FlowAction]): Unit =
            if (br.vxlanPortId.isDefined && br.vxlanPortId.get != context.inputPort) {
                val vxlanPortId = br.vxlanPortId.get
                tryAsk[Port](vxlanPortId) match {
                    case p: VxLanPort =>
                        outputActionsToVtep(p.vni, p.vtepTunAddr.addr,
                                            p.tunnelZoneId, actions,
                                            context.flowTags, context)
                    case _ =>
                        context.log.warn("could not find VxLanPort {} of bridge {}",
                                         vxlanPortId, br)
                }
            }

        val req = PortSetRequest(portSetId, update = false)
        val portSet = VirtualToPhysicalMapper.tryAsk(req)
        val actions = ListBuffer[FlowAction]()
        addLocalActions(portSet.localPorts, actions)
        val br = tryAsk[Bridge](portSetId)
        addRemoteActions(br, portSet.hosts, actions)
        // FIXME: at the moment (v1.5), this is need for
        // flooding traffic from a bridge. With mac
        // syncing, it will become unnecessary.
        addVtepActions(br, actions)
        actions
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
    protected def activePorts(portIds: Set[UUID],
                              dpTags: mutable.Set[FlowTag],
                              context: PacketContext): Seq[Port] =
        portIds.foldLeft(ListBuffer[Port]()) { (buf, id) =>
            val port = tryAsk[Port](id)
            dpTags += FlowTagger.tagForDevice(id)
            if (port.adminStateUp) {
                buf += port
            }
            buf
        }

    private def expandPortAction(port: UUID, context: PacketContext)
    : Seq[FlowAction] =
        dpState.getDpPortNumberForVport(port) map { portNum =>
            // If the DPC has a local DP port for this UUID, translate
            towardsLocalDpPorts(List(portNum.shortValue()),
                                context.flowTags, context)
        } getOrElse {
            // Otherwise we translate to a remote port or a vtep peer
            // VxLanPort is a subtype of exterior port,
            // therefore it needs to be matched first.
            tryAsk[Port](port) match {
                case p: VxLanPort =>
                    towardsVtepPeer(p.vni, p.vtepTunAddr, p.tunnelZoneId,
                                    context.flowTags, context)
                case p: Port if p.isExterior =>
                    towardsRemoteHost(p.tunnelKey, p.hostID,
                                      context.flowTags, context)
                case _ =>
                    context.log.warn("Port {} was not exterior", port)
                    Seq.empty[FlowAction]
            }
        }

    /** Generates a list of output FlowActions for the given list of local
     *  datapath ports. */
    protected def towardsLocalDpPorts(localPorts: Seq[JInteger],
                                      dpTags: mutable.Set[FlowTag],
                                      context: PacketContext)
    : Seq[FlowAction] = {
        context.log.debug("Emitting towards local dp ports {}", localPorts)
        val actions = ListBuffer[FlowAction]()
        outputActionsForLocalPorts(localPorts, actions, dpTags)
        actions
    }

    /** Emits a list of output FlowActions for tunnelling traffic to the given
     *  remote host with the given tunnel key. */
    private def towardsRemoteHost(tunnelKey: Long, peerHostId: UUID,
                                  dpTags: mutable.Set[FlowTag],
                                  context: PacketContext): Seq[FlowAction] = {
        context.log.debug(
            s"Emitting towards remote host $peerHostId with tunnek key $tunnelKey")
        val actions = ListBuffer[FlowAction]()
        outputActionsToPeers(tunnelKey, Set(peerHostId), actions, dpTags, context)
        actions
    }

    /** Emits a list of output FlowActions for tunnelling traffic to a remote
     *  vtep gateway given its ip address and a vni key. */
    private def towardsVtepPeer(vni: Int, vtepTunIp: IPv4Addr, tzId: UUID,
                                dpTags: mutable.Set[FlowTag],
                                context: PacketContext): Seq[FlowAction] = {
        context.log.debug(s"Emitting towards vtep at $vtepTunIp with vni $vni")
        val actions = ListBuffer[FlowAction]()
        outputActionsToVtep(vni, vtepTunIp.addr, tzId, actions, dpTags, context)
        actions
    }
}
