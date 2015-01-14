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

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import akka.actor.ActorSystem
import akka.util.Timeout

import org.midonet.cluster.client.{Port, VxLanPort}
import org.midonet.midolman.simulation.{Bridge, PacketContext}
import org.midonet.midolman.topology.VirtualTopologyActor.tryAsk
import org.midonet.odp.flows.FlowActions.{output, setKey}
import org.midonet.odp.flows._
import org.midonet.packets.{Ethernet, ICMP, IPv4, IPv4Addr}
import org.midonet.sdn.flows.{VirtualActions, FlowTagger}
import org.midonet.sdn.flows.FlowTagger.FlowTag

object FlowTranslator {
    val NotADpPort: JInteger = -1
}

trait FlowTranslator {
    import FlowTranslator._
    import VirtualActions._

    protected val dpState: DatapathState

    implicit protected val requestReplyTimeout = new Timeout(5, TimeUnit.SECONDS)
    implicit protected def system: ActorSystem
    implicit protected def executor = system.dispatcher

    /**
     * Translates a Seq of FlowActions expressed in virtual references into a
     * Seq of FlowActions expressed in physical references.
     */
    def translateActions(context: PacketContext, actions: Seq[FlowAction])
    : Seq[FlowAction] = {
        context.outPorts.clear()

        val translatedActs = actions map {
            case FlowActionOutputToVrnBridge(id, ports) =>
                    expandFloodAction(id, ports, context)
                case FlowActionOutputToVrnPort(port) =>
                    expandPortAction(port, context)
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
    private def outputActionsForLocalPort(portNo: JInteger,
                                          actions: ListBuffer[FlowAction],
                                          dpTags: mutable.Set[FlowTag]) {
        if (portNo != NotADpPort) {
            actions += output(portNo)
            dpTags += FlowTagger tagForDpPort portNo
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  actions for the given list of remote host and tunnel key. */
    def outputActionsToPeer(key: Long, peer: UUID,
                            actions: ListBuffer[FlowAction],
                            dpTags: mutable.Set[FlowTag],
                            context: PacketContext) {
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
            actions += setKey(FlowKeys.tunnel(key, src, dst, 0))
            actions += routeInfo.get.output
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
        val localIp =  tzMembership.get.asInstanceOf[IPv4Addr].toInt
        dpTags += FlowTagger.tagForTunnelRoute(localIp, vtepIp)
        actions += setKey(FlowKeys.tunnel(vni.toLong, localIp, vtepIp, 0))
        actions += dpState.vtepTunnellingOutputAction
    }

    private def expandFloodAction(bridge: UUID, portIds: List[UUID],
                                  context: PacketContext): Seq[FlowAction] = {
        def addLocal(allPorts: List[Port], actions: ListBuffer[FlowAction]) {
            var ports = allPorts
            while (!ports.isEmpty) {
                val port = ports.head
                ports = ports.tail
                if (port.hostID == dpState.host.id) {
                    val portNo = dpState.getDpPortNumberForVport(port.id)
                    if (portNo.isDefined) {
                        context.outPorts.add(port.id)
                        outputActionsForLocalPort(portNo.get, actions, context.flowTags)
                    }
                }
            }
        }

        def addRemote(allPorts: List[Port], actions: ListBuffer[FlowAction]) {
            var ports = allPorts
            while (!ports.isEmpty) {
                val port = ports.head
                ports = ports.tail
                if (port.hostID != dpState.host.id) {
                    context.outPorts.add(port.id)
                    outputActionsToPeer(port.tunnelKey, port.hostID, actions,
                        context.flowTags, context)
                }
            }
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

        val actions = ListBuffer[FlowAction]()
        val ports = portIds.map(tryAsk[Port])
        addLocal(ports, actions)
        addRemote(ports, actions)

        // FIXME: at the moment (v1.5), this is need for
        // flooding traffic from a bridge. With mac
        // syncing, it will become unnecessary.
        addVtepActions(tryAsk[Bridge](bridge), actions)
        actions
    }

    private def expandPortAction(port: UUID, context: PacketContext)
    : Seq[FlowAction] =
        dpState.getDpPortNumberForVport(port) map { portNum =>
            context.outPorts.add(port)
            // If the DPC has a local DP port for this UUID, translate
            towardsLocalDpPort(portNum, context.flowTags, context)
        } getOrElse {
            // Otherwise we translate to a remote port or a vtep peer
            // VxLanPort is a subtype of exterior port,
            // therefore it needs to be matched first.
            tryAsk[Port](port) match {
                case p: VxLanPort =>
                    towardsVtepPeer(p.vni, p.vtepTunAddr, p.tunnelZoneId,
                                    context.flowTags, context)
                case p: Port if p.isExterior =>
                    context.outPorts.add(port)
                    towardsRemoteHost(p.tunnelKey, p.hostID,
                                      context.flowTags, context)
                case _ =>
                    context.log.warn("Port {} was not exterior", port)
                    Seq.empty[FlowAction]
            }
        }

    /** Generates a list of output FlowActions for the given list of local
     *  datapath ports. */
    protected def towardsLocalDpPort(localPortNo: JInteger,
                                     dpTags: mutable.Set[FlowTag],
                                     context: PacketContext)
    : Seq[FlowAction] = {
        context.log.debug("Emitting towards local dp port {}", localPortNo)
        val actions = ListBuffer[FlowAction]()
        outputActionsForLocalPort(localPortNo, actions, dpTags)
        actions
    }

    /** Emits a list of output FlowActions for tunnelling traffic to the given
     *  remote host with the given tunnel key. */
    private def towardsRemoteHost(tunnelKey: Long, peerHostId: UUID,
                                  dpTags: mutable.Set[FlowTag],
                                  context: PacketContext): Seq[FlowAction] = {
        context.log.debug(
            s"Emitting towards remote host $peerHostId with tunnel key $tunnelKey")
        val actions = ListBuffer[FlowAction]()
        outputActionsToPeer(tunnelKey, peerHostId, actions, dpTags, context)
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
