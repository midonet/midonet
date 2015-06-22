/*
 * Copyright 2015 Midokura SARL
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

import scala.concurrent.ExecutionContextExecutor

import akka.actor.ActorSystem
import akka.util.Timeout

import org.midonet.midolman.simulation.{Bridge, PacketContext}
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest
import org.midonet.midolman.topology.VirtualTopologyActor.tryAsk
import org.midonet.midolman.topology.devices.{Port, VxLanPort}
import org.midonet.odp.flows.FlowActions.{output, setKey}
import org.midonet.odp.flows._
import org.midonet.packets.{Ethernet, ICMP, IPv4, IPv4Addr}
import org.midonet.sdn.flows.{FlowTagger, VirtualActions}

object FlowTranslator {
    val NotADpPort: JInteger = -1
}

trait FlowTranslator {
    import FlowTranslator._
    import VirtualActions._

    protected val dpState: DatapathState
    protected val hostId: UUID

    implicit protected val requestReplyTimeout = new Timeout(5, TimeUnit.SECONDS)
    implicit protected def system: ActorSystem
    implicit protected def executor: ExecutionContextExecutor = system.dispatcher

    /**
     * Translates a Seq of FlowActions expressed in virtual references into a
     * Seq of FlowActions expressed in physical references.
     */
    def translateActions(context: PacketContext): Unit = {
        context.outPorts.clear()

        val virtualActions = context.virtualFlowActions
        val actionsCount = virtualActions.size()
        var i = 0
        while (i < actionsCount) {
            virtualActions.get(i) match {
                case FlowActionOutputToVrnBridge(id, ports) =>
                    expandFloodAction(id, ports, context)
                case FlowActionOutputToVrnPort(port) =>
                    expandPortAction(port, context)
                case a: FlowActionSetKey =>
                    a.getFlowKey match {
                        case k: FlowKeyICMPError =>
                            mangleIcmp(context.ethernet, k.icmp_data)
                        case k: FlowKeyICMPEcho =>
                        case _ =>
                            context.addFlowAndPacketAction(a)
                    }
                case a: FlowActionUserspace =>
                    context.flowActions.add(a)
                case a =>
                    context.addFlowAndPacketAction(a)
            }
            i += 1
        }

        context.log.debug(s"Translated actions to: ${context.flowActions}")
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
                        icmp.clearChecksum()
                    case _ =>
                }
            case _ =>
        }
    }

    /** Update the list of action and list of tags with the output actions
     *  for the given list of local datapath port numbers. */
    private def outputActionsForLocalPort(portNo: JInteger,
                                          context: PacketContext): Unit = {
        context.log.debug(s"Emitting towards local dp port $portNo")
        if (portNo != NotADpPort) {
            context.addFlowAndPacketAction(output(portNo))
            context.addFlowTag(FlowTagger tagForDpPort portNo)
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  actions for the given list of remote host and tunnel key. */
    def outputActionsToPeer(key: Long, peer: UUID,
                            context: PacketContext): Unit = {
        context.log.debug(s"Emitting towards remote host $peer with tunnel key $key")
        val routeInfo = dpState.peerTunnelInfo(peer)
        if (routeInfo.isEmpty) {
            context.log.warn("Unable to tunnel to peer {}, is the peer "+
                "in the same tunnel zone as the current node?", peer)
        } else {
            val src = routeInfo.get.srcIp
            val dst = routeInfo.get.dstIp
            context.addFlowTag(FlowTagger.tagForTunnelRoute(src, dst))
            // Each FlowActionSetKey must be followed by a corresponding
            // FlowActionOutput.
            if (context.tracingEnabled) {
                context.flowActions.add(
                    setKey(FlowKeys.tunnel(key, src, dst, 0)))
                context.packetActions.add(
                    setKey(FlowKeys.tunnel(context.setTraceTunnelBit(key),
                                           src, dst, 0)))
            } else {
                context.addFlowAndPacketAction(
                    setKey(FlowKeys.tunnel(key, src, dst, 0)))
            }
            context.addFlowAndPacketAction(routeInfo.get.output)
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  action for the given vtep tunnel addr and vni key. The tzId is the
     *  id of the tunnel zone specified by this VTEP's config which allows
     *  us to determine which of the host's IP we should use */
    private def outputActionsToVtep(vni: Int, vtepIp: IPv4Addr, tzId: UUID,
                                    context: PacketContext): Unit = {
        context.log.debug(s"Emitting towards vtep at $vtepIp with vni $vni")

        val host = VirtualToPhysicalMapper.tryAsk(new HostRequest(hostId))
        val tzMembership = host.tunnelZones.get(tzId)

        if (tzMembership eq None) {
            context.log.warn(s"Can't output to VTEP with tunnel IP: $vtepIp, host not in "
                             + s"VTEP's tunnel zone: $tzId")
            return
        }

        val localIp =  tzMembership.get.asInstanceOf[IPv4Addr].toInt
        val vtepIntIp = vtepIp.toInt
        context.addFlowTag(FlowTagger.tagForTunnelRoute(localIp, vtepIntIp))
        context.addFlowAndPacketAction(setKey(FlowKeys.tunnel(vni.toLong, localIp, vtepIntIp, 0)))
        context.addFlowAndPacketAction(dpState.vtepTunnellingOutputAction)
    }

    private def expandFloodAction(bridge: UUID, portIds: List[UUID],
                                  context: PacketContext): Unit = {
        def addLocal(allPorts: List[Port]) {
            var ports = allPorts
            while (ports.nonEmpty) {
                val port = ports.head
                ports = ports.tail
                if (port.hostId == hostId) {
                    val portNo = dpState.getDpPortNumberForVport(port.id)
                    if (portNo ne null) {
                        context.outPorts.add(port.id)
                        outputActionsForLocalPort(portNo, context)
                    }
                }
            }
        }

        def addRemote(allPorts: List[Port]) {
            var ports = allPorts
            while (ports.nonEmpty) {
                val port = ports.head
                ports = ports.tail
                if (port.hostId != hostId) {
                    context.outPorts.add(port.id)
                    outputActionsToPeer(port.tunnelKey, port.hostId, context)
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
        def addVtepActions(br: Bridge): Unit = {
            var i = 0
            while (i < br.vxlanPortIds.size) {
                val vxlanPortId = br.vxlanPortIds(i)
                i += 1
                tryAsk[Port](vxlanPortId) match {
                    case p: VxLanPort =>
                        outputActionsToVtep(p.vtepVni, p.vtepTunnelIp,
                                            p.vtepTunnelZoneId, context)
                    case _ =>
                        context.log.warn("Bridge {} was expected to be bound to"
                                         + "VTEP through port {} that isn't "
                                         + "found", vxlanPortId, br)
                }
            }
        }

        val ports = portIds.map(tryAsk[Port])
        addLocal(ports)
        addRemote(ports)

        // FIXME: at the moment (v1.5), this is need for
        // flooding traffic from a bridge. With mac
        // syncing, it will become unnecessary.
        addVtepActions(tryAsk[Bridge](bridge))
    }

    private def expandPortAction(port: UUID, context: PacketContext): Unit =
        dpState.getDpPortNumberForVport(port) match {
            case null => // Translate to a remote port or a vtep peer.
                tryAsk[Port](port) match {
                    case p: VxLanPort => // Always exterior
                        outputActionsToVtep(p.vtepVni, p.vtepTunnelIp,
                                            p.vtepTunnelZoneId, context)
                    case p: Port if p.isExterior =>
                        context.outPorts.add(port)
                        outputActionsToPeer(p.tunnelKey, p.hostId, context)
                    case _ =>
                        context.log.warn("Port {} was not exterior", port)
                }
            case portNum =>
                context.outPorts.add(port)
                // If the DPC has a local DP port for this UUID, translate
                outputActionsForLocalPort(portNum, context)
        }
}
