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

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.{VxLanPort, PacketContext}
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.midolman.topology.{VirtualTopology, VxLanPortMappingService}
import org.midonet.midolman.topology.devices.Host
import org.midonet.midolman.simulation.Port
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.FlowActions._
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.VirtualActions.{Decap, Encap}

object FlowTranslator {
    val NotADpPort: JInteger = -1
    type AddFlowAction = (PacketContext, FlowAction) => Unit
}

trait FlowTranslator {
    import FlowTranslator._

    protected val dpState: DatapathState
    protected val hostId: UUID
    protected val config: MidolmanConfig

    protected val numWorkers: Int
    protected val workerId: Int

    implicit protected def vt: VirtualTopology

    private var encapUniquifier = workerId
    private var decapUniquifier = workerId

    /**
     * Translates FlowActions expressed in virtual references into
     * FlowActions expressed in physical references.
     */
    def translateActions(context: PacketContext): Unit = {
        context.outPorts.clear()
        var i = 0
        val virtualActions = context.virtualFlowActions
        val actionsCount = virtualActions.size()
        var addFlowAndPacketAction =
            if (context.isRecirc) addActionBeforeRecirc else addAction
        while (i < actionsCount) {
            virtualActions.get(i) match {
                case ToPortAction(port) =>
                    expandPortAction(port, context, addFlowAndPacketAction)
                case a: FlowActionSetKey =>
                    a.getFlowKey match {
                        case k: FlowKeyICMPError =>
                            mangleIcmp(context.ethernet, k.icmp_data)
                        case k: FlowKeyICMPEcho =>
                        case _ =>
                            addFlowAndPacketAction(context, a)
                    }
                case a: FlowActionUserspace =>
                    addFlowAndPacketAction(context, a)
                case Encap(vni) =>
                    recircInnerPacket(vni, context, addFlowAndPacketAction)
                    context.log.debug(s"Translated inner actions to: ${context.recircFlowActions}")
                    addActionsToPrepareOuterPacket(context, i + 1, addActionAfterRecirc)
                    addFlowAndPacketAction = addActionAfterRecirc
                case Decap(vni) =>
                    recircOuterPacket(vni, context, addFlowAndPacketAction)
                    context.log.debug(s"Translated inner actions to: ${context.recircFlowActions}")
                    addFlowAndPacketAction = addActionAfterRecirc
                case a =>
                    addFlowAndPacketAction(context, a)
            }
            i += 1
        }
        context.log.debug(s"Translated actions to: ${context.flowActions}")
    }

    private val addAction: AddFlowAction = (c: PacketContext, a: FlowAction) => {
        c.flowActions.add(a)
        c.packetActions.add(a)
    }

    private val addActionBeforeRecirc: AddFlowAction = (c: PacketContext, a: FlowAction) => {
        c.recircFlowActions.add(a)
        c.packetActions.add(a)
    }

    private val addActionAfterRecirc: AddFlowAction = (c: PacketContext, a: FlowAction) =>
        c.flowActions.add(a)

    private def uniquifyEncap(key: FlowKeyTunnel) = {
        // TOOD(duarte): use bits from the src address too
        key.ipv4_tos = encapUniquifier.toByte
        key.ipv4_ttl = (encapUniquifier >>> 8).toByte
        encapUniquifier += numWorkers
    }

    private def uniquifyDecap(key: FlowKeyIPv4) = {
        // TOOD(duarte): use bits from the src address too
        key.ipv4_tos = decapUniquifier.toByte
        key.ipv4_ttl = (decapUniquifier >>> 8).toByte
        decapUniquifier += numWorkers
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
                                          context: PacketContext,
                                          addFlowAndPacketAction: AddFlowAction): Unit = {
        context.log.debug(s"Emitting towards local dp port $portNo")
        if (portNo != NotADpPort) {
            addFlowAndPacketAction(context, output(portNo))
            context.addFlowTag(FlowTagger tagForDpPort portNo)
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  actions for the given list of remote host and tunnel key. */
    def outputActionsToPeer(key: Long, peer: UUID,
                            context: PacketContext,
                            addFlowAndPacketAction: AddFlowAction): Unit = {
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
            if (context.tracingEnabled && !context.isRecirc) {
                context.flowActions.add(
                    setKey(FlowKeys.tunnel(key, src, dst, 0)))
                context.packetActions.add(
                    setKey(FlowKeys.tunnel(context.setTraceTunnelBit(key),
                                           src, dst, 0)))
            } else {
                addFlowAndPacketAction(context,
                    setKey(FlowKeys.tunnel(key, src, dst, 0)))
            }
            addFlowAndPacketAction(context, routeInfo.get.output)
        }
    }

    /** Update the list of action and list of tags with the output tunnelling
     *  action for the given vtep tunnel addr and vni key. The tzId is the
     *  id of the tunnel zone specified by this VTEP's config which allows
     *  us to determine which of the host's IP we should use */
    private def outputActionsToVtep(
            portId: UUID,
            context: PacketContext,
            addFlowAndPacketAction: AddFlowAction): Unit = {
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
        addFlowAndPacketAction(context, setKey(
            FlowKeys.tunnel(tunnel.vni.toLong, localIp, vtepIntIp, 0)))
        addFlowAndPacketAction(context, dpState.vtepTunnellingOutputAction)
    }

    private def expandPortAction(
            port: UUID,
            context: PacketContext,
            addFlowAndPacketAction: AddFlowAction): Unit =
        dpState.getDpPortNumberForVport(port) match {
            case null => // Translate to a remote port or a vtep peer.
                vt.tryGet[Port](port) match {
                    case p: VxLanPort => // Always exterior
                        outputActionsToVtep(
                            p.id, context, addFlowAndPacketAction)
                    case p: Port if p.isExterior =>
                        context.outPorts.add(port)
                        outputActionsToPeer(
                            p.tunnelKey, p.hostId, context, addFlowAndPacketAction)
                    case p =>
                        context.log.warn("Port is not exterior: {}", p)
                }
            case portNum =>
                context.outPorts.add(port)
                // If the DPC has a local DP port for this UUID, translate
                outputActionsForLocalPort(
                    portNum, context, addFlowAndPacketAction)
        }

    private def recircInnerPacket(
            vni: Int,
            context: PacketContext,
            addFlowAndPacketAction: AddFlowAction): Unit = {
        val innerFlowKey = FlowKeys.tunnel(
            vni,
            config.datapath.recircHostCidr.getAddress.toInt,
            config.datapath.recircMnAddr.toInt,
            0)
        uniquifyEncap(innerFlowKey)
        addFlowAndPacketAction(context, setKey(innerFlowKey))
        addFlowAndPacketAction(context, dpState.recircTunnelOutputAction)
        // origMatch describes the outer packet
        matchOnOuterPacket(context.origMatch, innerFlowKey)
    }

    private def recircOuterPacket(
            vni: Int,
            context: PacketContext,
            addFlowAndPacketAction: AddFlowAction): Unit = {
        val ethKey = FlowKeys.ethernet(
            config.datapath.recircMnMac,
            config.datapath.recircHostMac)
        val outerFlowKey = FlowKeys.ipv4(
            config.datapath.recircMnAddr,
            config.datapath.recircHostCidr.getAddress,
            context.recircMatch.getNetworkProto,
            0.toByte,
            0.toByte,
            context.recircMatch.getIpFragmentType)
        uniquifyDecap(outerFlowKey)
        val udpKey = FlowKeys.udp(12345, dpState.tunnelRecircVxLan.getDestinationPort)
        // Make sure these fields are seen so we can apply the actions above.
        context.recircMatch.fieldSeen(Field.EtherType)
        context.recircMatch.fieldSeen(Field.NetworkProto)
        addFlowAndPacketAction(context, setKey(ethKey))
        addFlowAndPacketAction(context, setKey(outerFlowKey))
        addFlowAndPacketAction(context, setKey(udpKey))
        addFlowAndPacketAction(context, dpState.hostRecircOutputAction)
        // origMatch describes the inner packet
        matchOnInnerPacket(vni, context.origMatch, outerFlowKey)
    }

    private def matchOnOuterPacket(fmatch: FlowMatch, tunnelFk: FlowKeyTunnel): Unit = {
        // Create relevant flow keys to identify the flow.
        fmatch.clear()
        fmatch.addKey(FlowKeys.inPort(dpState.hostRecircPort.getPortNo))
        fmatch.addKey(FlowKeys.ethernet(MAC.ALL_ZEROS, MAC.ALL_ZEROS))
        fmatch.addKey(FlowKeys.etherType(IPv4.ETHERTYPE))
        fmatch.addKey(FlowKeys.ipv4(
            tunnelFk.ipv4_src,
            tunnelFk.ipv4_dst,
            UDP.PROTOCOL_NUMBER,
            tunnelFk.ipv4_tos,
            tunnelFk.ipv4_ttl,
            IPFragmentType.None.value))
        fmatch.addKey(FlowKeys.udp(0, dpState.tunnelRecircVxLan.getDestinationPort))
        // Mark fields as seen.
        fmatch.fieldSeen(Field.NetworkSrc)
        fmatch.fieldSeen(Field.NetworkDst)
        fmatch.fieldSeen(Field.NetworkTOS)
        fmatch.fieldSeen(Field.NetworkTTL)
        fmatch.fieldSeen(Field.DstPort)
    }

    private def matchOnInnerPacket(vni: Int, fmatch: FlowMatch, ipFk: FlowKeyIPv4): Unit = {
        // Other flow keys already added by PacketContext::decap
        fmatch.addKey(FlowKeys.inPort(dpState.tunnelRecircVxLan.getPortNo))
        fmatch.addKey(FlowKeys.tunnel(
            vni,
            ipFk.ipv4_src,
            ipFk.ipv4_dst,
            ipFk.ipv4_tos,
            ipFk.ipv4_ttl))
        // Other fields already populated in the PacketContext.
        fmatch.fieldSeen(Field.TunnelSrc)
        fmatch.fieldSeen(Field.TunnelDst)
        fmatch.fieldSeen(Field.TunnelKey)
        // This vni may already be used to identify a port, so a megaflow
        // may exist. Thus we match on tos and tll too.
        fmatch.fieldSeen(Field.TunnelTOS)
        fmatch.fieldSeen(Field.TunnelTTL)
    }

    private def addActionsToPrepareOuterPacket(
            context: PacketContext,
            virtualActionIndex: Int,
            addFlowAndPacketAction: AddFlowAction): Unit = {
        // Now that the packet has recirculated, reset the fields to their
        // original values, as seen by the simulation of the outer packet.
        var needsEth = true
        var needsIp = true
        var needsUdp = true
        var i = virtualActionIndex
        val virtualActionsSize = context.virtualFlowActions.size()
        while (i < virtualActionsSize) {
            context.virtualFlowActions.get(i) match {
                case setKey: FlowActionSetKey =>
                    setKey.getFlowKey match {
                        case _: FlowKeyEthernet =>
                            needsEth = false
                        case _: FlowKeyIPv4 =>
                            needsIp = false
                        case _: FlowKeyUDP =>
                            needsUdp = false
                        case _ =>
                    }
                case _ =>
            }
            i += 1
        }
        if (needsEth) {
            addFlowAndPacketAction(context, setKey(
                FlowKeys.ethernet(
                    context.wcmatch.getEthSrc,
                    context.wcmatch.getEthDst)))
        }
        if (needsIp) {
            addFlowAndPacketAction(context, setKey(
                FlowKeys.ipv4(
                    context.wcmatch.getNetworkSrcIP.asInstanceOf[IPv4Addr].toInt,
                    context.wcmatch.getNetworkDstIP.asInstanceOf[IPv4Addr].toInt,
                    UDP.PROTOCOL_NUMBER,
                    context.wcmatch.getNetworkTOS,
                    context.wcmatch.getNetworkTTL,
                    context.wcmatch.getIpFragmentType.value)))
        }
        if (needsUdp) {
            addFlowAndPacketAction(context, setKey(
                FlowKeys.udp(
                    context.wcmatch.getSrcPort,
                    context.wcmatch.getDstPort)))
        }
    }
}
