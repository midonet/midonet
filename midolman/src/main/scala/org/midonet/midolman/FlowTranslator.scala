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

import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.midolman.topology.devices.Host

import akka.util.Timeout

import org.midonet.midolman.simulation.{VxLanPort, PacketContext}
import org.midonet.midolman.topology.{VirtualTopology, VxLanPortMappingService}
import org.midonet.midolman.topology.VirtualTopology.tryGet
import org.midonet.midolman.simulation.Port
import org.midonet.odp.flows.FlowActions.{output, setKey}
import org.midonet.odp.flows._
import org.midonet.packets.{Ethernet, ICMP, IPv4, IPv4Addr}
import org.midonet.sdn.flows.FlowTagger

object FlowTranslator {
    val NotADpPort: JInteger = -1
}

trait FlowTranslator {
    import FlowTranslator._

    protected val dpState: DatapathState
    protected val hostId: UUID

    implicit protected val requestReplyTimeout = new Timeout(5, TimeUnit.SECONDS)
    implicit protected def vt: VirtualTopology

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
                case ToPortAction(port) =>
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
    private def outputActionsToVtep(portId: UUID,  context: PacketContext): Unit = {
        context.log.debug("Forwarding to VXLAN port: {}", portId)

        val tunnel = VxLanPortMappingService.tunnelOf(portId) match {
            case Some(t) =>
                context.log.debug("VXLAN port {} has VTEP tunnel zone: {} " +
                                  "tunnel IP: {} VNI: {}", portId, t.tunnelZoneId,
                                  t.tunnelIp, Int.box(t.vni))
                t
            case None =>
                context.log.warn("No VTEP tunnel found for VXLAN port {}: " +
                                 "dropping packet", portId)
                return
        }

        val host = vt.tryGet[Host](hostId)
        val tzMembership = host.tunnelZones.get(tunnel.tunnelZoneId)

        if (tzMembership eq None) {
            context.log.warn( "Cannot forward to VTEP with tunnel IP {}: host " +
                              "not in VTEP's tunnel zone: {}", tunnel.tunnelIp,
                              tunnel.tunnelZoneId)
            return
        }

        val localIp = tzMembership.get.asInstanceOf[IPv4Addr].toInt
        val vtepIntIp = tunnel.tunnelIp.toInt
        context.addFlowTag(FlowTagger.tagForTunnelRoute(localIp, vtepIntIp))
        context.addFlowAndPacketAction(setKey(
            FlowKeys.tunnel(tunnel.vni.toLong, localIp, vtepIntIp, 0)))
        context.addFlowAndPacketAction(dpState.vtepTunnellingOutputAction)
    }

    private def expandPortAction(port: UUID, context: PacketContext): Unit =
        dpState.getDpPortNumberForVport(port) match {
            case null => // Translate to a remote port or a vtep peer.
                tryGet[Port](port) match {
                    case p: VxLanPort => // Always exterior
                        outputActionsToVtep(p.id, context)
                    case p: Port if p.isExterior =>
                        context.outPorts.add(port)
                        outputActionsToPeer(p.tunnelKey, p.hostId, context)
                    case p =>
                        context.log.warn("Port is not exterior: {}", p)
                }
            case portNum =>
                context.outPorts.add(port)
                // If the DPC has a local DP port for this UUID, translate
                outputActionsForLocalPort(portNum, context)
        }
}
