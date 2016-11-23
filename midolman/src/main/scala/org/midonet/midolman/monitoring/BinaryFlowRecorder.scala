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

package org.midonet.midolman.monitoring

import java.nio.ByteBuffer
import java.util.{ArrayList, List, UUID}

import uk.co.real_logic.sbe.codec.java._

import org.midonet.cluster.flowhistory.{ActionEncoder, BinarySerialization}
import org.midonet.cluster.flowhistory.proto.{DeviceType => SbeDeviceType, RuleResult => SbeRuleResult, SimulationResult => SbeSimResult, _}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.PacketWorkflow
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.config.FlowHistoryConfig
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.flows._
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr, MAC}
import org.midonet.sdn.flows.FlowTagger._


class BinaryFlowRecorder(val hostId: UUID, config: FlowHistoryConfig,
                         backend: MidonetBackend)
        extends AbstractFlowRecorder(config, backend) {
    val MESSAGE_HEADER = new MessageHeader
    val FLOW_SUMMARY = new FlowSummary
    val buffer = ByteBuffer.allocateDirect(BinarySerialization.BufferSize)
    val directBuffer = new DirectBuffer(buffer)

    val actionsBytes = new Array[Byte](BinarySerialization.ActionsBufferSize)
    val actionsBuffer = ByteBuffer.wrap(actionsBytes)

    val deviceStaging = new ArrayList[DeviceTag]
    val actionEnc = new ActionEncoder

    override def encodeRecord(pktContext: PacketContext,
                              simRes: SimulationResult): ByteBuffer = {
        buffer.clear
        var bufferOffset = 0
        MESSAGE_HEADER.wrap(directBuffer, 0,
                            BinarySerialization.MessageTemplateVersion)
            .blockLength(FLOW_SUMMARY.sbeBlockLength)
            .templateId(FLOW_SUMMARY.sbeTemplateId)
            .schemaId(FLOW_SUMMARY.sbeSchemaId)
            .version(FLOW_SUMMARY.sbeSchemaVersion)
        bufferOffset += MESSAGE_HEADER.size

        FLOW_SUMMARY.wrapForEncode(directBuffer, bufferOffset)

        encodeSimpleValues(pktContext, simRes)
        encodeIcmpData(pktContext)
        encodeVlanIds(pktContext)
        encodeOutPorts(pktContext)
        encodeRules(pktContext)
        encodeDevices(pktContext)

        actionsBuffer.clear()
        encodeFlowActions(pktContext.flowActions)(actionsBuffer)

        buffer.limit(MESSAGE_HEADER.size + FLOW_SUMMARY.size)
        buffer
    }

    private def encodeSimpleValues(pktContext: PacketContext,
                                   simRes: SimulationResult): Unit = {
        val fmatch = pktContext.origMatch
        FLOW_SUMMARY.simResult(simRes match {
                           case PacketWorkflow.NoOp => SbeSimResult.NoOp
                           case PacketWorkflow.Drop => SbeSimResult.Drop
                           case PacketWorkflow.ErrorDrop
                                   => SbeSimResult.ErrorDrop
                           case PacketWorkflow.ShortDrop
                                   => SbeSimResult.ShortDrop
                           case PacketWorkflow.AddVirtualWildcardFlow
                                   => SbeSimResult.AddVirtualWildcardFlow
                           case PacketWorkflow.UserspaceFlow
                                   => SbeSimResult.UserspaceFlow
                           case PacketWorkflow.FlowCreated
                                   => SbeSimResult.FlowCreated
                           case PacketWorkflow.GeneratedPacket
                                   => SbeSimResult.GeneratedPacket
                       })
            .cookie(pktContext.cookie)
            .flowMatchInputPort(fmatch.getInputPortNumber)
            .flowMatchTunnelKey(fmatch.getTunnelKey)
            .flowMatchTunnelSrc(fmatch.getTunnelSrc)
            .flowMatchTunnelDst(fmatch.getTunnelDst)

        encodeMAC(fmatch.getEthSrc, FLOW_SUMMARY.flowMatchEthernetSrc)
        encodeMAC(fmatch.getEthDst, FLOW_SUMMARY.flowMatchEthernetDst)

        FLOW_SUMMARY.flowMatchEtherType(fmatch.getEtherType)

        encodeIP(fmatch.getNetworkSrcIP, FLOW_SUMMARY.flowMatchNetworkSrcType,
                 FLOW_SUMMARY.flowMatchNetworkSrc)
        encodeIP(fmatch.getNetworkDstIP,
                 FLOW_SUMMARY.flowMatchNetworkDstType,
                 FLOW_SUMMARY.flowMatchNetworkDst
                 )

        FLOW_SUMMARY
            .flowMatchSrcPort(fmatch.getSrcPort)
            .flowMatchDstPort(fmatch.getDstPort)
            .flowMatchNetworkProto(fmatch.getNetworkProto)
            .flowMatchNetworkTOS(fmatch.getNetworkTOS)
            .flowMatchNetworkTTL(fmatch.getNetworkTTL)
            .flowMatchIcmpId(fmatch.getIcmpIdentifier)

        encodeUUID(hostId, FLOW_SUMMARY.hostId)
        encodeUUID(pktContext.inputPort, FLOW_SUMMARY.inPort)
    }

    private def encodeIcmpData(pktContext: PacketContext): Unit = {
        var i = 0
        val data = pktContext.origMatch.getIcmpData
        if (data != null) {
            val iter = FLOW_SUMMARY.flowMatchIcmpDataCount(data.length)
            while (i < data.length) {
                iter.next().data(data(i))
                i += 1
            }
        } else {
            FLOW_SUMMARY.flowMatchIcmpDataCount(0)
        }
    }

    private def encodeMAC(address: MAC, setter: (Int, Short) => Unit): Unit = {
        if (address != null) {
            var i = 0
            val bytes = address.getAddress
            while (i < bytes.length) {
                setter(i, bytes(i))
                i += 1
            }
        }
    }

    private def encodeIP(address: IPAddr,
                         typeSetter: InetAddrType => FlowSummary,
                         setter: (Int, Long) => Unit): Unit = {
        if (address != null) {
            address match {
                case ip4: IPv4Addr =>
                    typeSetter(InetAddrType.IPv4)
                    setter(0, ip4.addr)
                case ip6: IPv6Addr =>
                    typeSetter(InetAddrType.IPv6)
                    setter(0, ip6.upperWord)
                    setter(1, ip6.lowerWord)
            }
        }
    }

    private def encodeUUID(uuid: UUID, setter: (Int, Long) => Unit) {
        if (uuid != null) {
            setter(0, uuid.getMostSignificantBits)
            setter(1, uuid.getLeastSignificantBits)
        }
    }

    private def encodeVlanIds(pktContext: PacketContext): Unit = {
        val fmatch = pktContext.origMatch
        var i = 0
        val vlans = fmatch.getVlanIds
        val iter = FLOW_SUMMARY.flowMatchVlanIdsCount(vlans.size)
        while (i < vlans.size) {
            iter.next().vlanId(vlans.get(i).toInt)
            i += 1
        }
    }

    private def encodeOutPorts(pktContext: PacketContext): Unit = {
        var i = 0
        val outPorts = pktContext.outPorts
        val iter = FLOW_SUMMARY.outPortsCount(outPorts.size)
        while (i < outPorts.size) {
            val p = outPorts.get(i)
            val port = iter.next()
            port.port(0, p.getMostSignificantBits)
            port.port(1, p.getLeastSignificantBits)
            i += 1
        }
    }

    private def encodeBoolean(value: Boolean): BooleanType = value match {
        case true => BooleanType.T
        case false => BooleanType.F
    }

    private def encodeRules(pktContext: PacketContext): Unit = {
        var i = 0
        val rules = pktContext.traversedRules
        val results = pktContext.traversedRuleResults
        val rulesMatched = pktContext.traversedRulesMatched
        val rulesApplied = pktContext.traversedRulesApplied
        val iter = FLOW_SUMMARY.traversedRulesCount(rules.size)
        while (i < rules.size) {
            val r = rules.get(i)
            val rule = iter.next()
            rule.rule(0, r.getMostSignificantBits)
            rule.rule(1, r.getLeastSignificantBits)
            results.get(i).action match {
                case RuleResult.Action.ACCEPT =>
                    rule.result(SbeRuleResult.ACCEPT)
                case RuleResult.Action.CONTINUE =>
                    rule.result(SbeRuleResult.CONTINUE)
                case RuleResult.Action.DROP =>
                    rule.result(SbeRuleResult.DROP)
                case RuleResult.Action.JUMP =>
                    rule.result(SbeRuleResult.JUMP)
                case RuleResult.Action.REJECT =>
                    rule.result(SbeRuleResult.REJECT)
                case RuleResult.Action.RETURN =>
                    rule.result(SbeRuleResult.RETURN)
                case RuleResult.Action.REDIRECT =>
                    rule.result(SbeRuleResult.REDIRECT)
                case _ =>
            }
            rule.matched(encodeBoolean(rulesMatched.get(i)))
            rule.applied(encodeBoolean(rulesApplied.get(i)))
            i += 1
        }
    }

    private def encodeDevices(pktContext: PacketContext): Unit = {
        deviceStaging.clear()
        var i = 0
        val devices = pktContext.flowTags
        while (i < devices.size) {
            devices.get(i) match {
                case t: LoadBalancerDeviceTag => deviceStaging.add(t)
                case t: PoolDeviceTag => deviceStaging.add(t)
                case t: PortGroupDeviceTag => deviceStaging.add(t)
                case t: BridgeDeviceTag => deviceStaging.add(t)
                case t: RouterDeviceTag => deviceStaging.add(t)
                case t: PortDeviceTag => deviceStaging.add(t)
                case t: ChainDeviceTag => deviceStaging.add(t)
                case t: MirrorDeviceTag => deviceStaging.add(t)
                case _ =>
            }
            i += 1
        }

        i = 0
        val iter = FLOW_SUMMARY.traversedDevicesCount(deviceStaging.size)
        while (i < deviceStaging.size) {
            val tag = deviceStaging.get(i)
            val dev = iter.next()
            dev.device(0, tag.device.getMostSignificantBits)
            dev.device(1, tag.device.getLeastSignificantBits)

            tag match {
                case t: LoadBalancerDeviceTag =>
                    dev.`type`(SbeDeviceType.LOAD_BALANCER)
                case t: PoolDeviceTag =>
                    dev.`type`(SbeDeviceType.POOL)
                case t: PortGroupDeviceTag =>
                    dev.`type`(SbeDeviceType.PORT_GROUP)
                case t: BridgeDeviceTag =>
                    dev.`type`(SbeDeviceType.BRIDGE)
                case t: RouterDeviceTag =>
                    dev.`type`(SbeDeviceType.ROUTER)
                case t: PortDeviceTag =>
                    dev.`type`(SbeDeviceType.PORT)
                case t: ChainDeviceTag =>
                    dev.`type`(SbeDeviceType.CHAIN)
                case t: MirrorDeviceTag =>
                    dev.`type`(SbeDeviceType.MIRROR)
                case _ =>
            }
            i += 1
        }
    }

    def encodeFlowActions(actions: List[FlowAction])
                         (implicit buffer: ByteBuffer): Unit = {
        var i = 0
        val count = Math.min(actions.size, Byte.MaxValue)
        actionEnc.writeCount(count.toByte)
        while (i < count) {
            actions.get(i) match {
                case a: FlowActionOutput =>
                    actionEnc.output(a.getPortNumber)
                case a: FlowActionPopVLAN =>
                    actionEnc.popVlan()
                case a: FlowActionPushVLAN =>
                    actionEnc.pushVlan(a.getTagProtocolIdentifier,
                                       a.getTagControlIdentifier)
                case a: FlowActionUserspace =>
                    actionEnc.userspace(a.uplinkPid,
                                        if (a.userData == null) 0
                                        else a.userData)
                case a: FlowActionSetKey =>
                    a.getFlowKey match {
                        case k: FlowKeyARP =>
                            actionEnc.arp(k.arp_sip, k.arp_tip,
                                          k.arp_op, k.arp_sha, k.arp_tha)
                        case k: FlowKeyEthernet =>
                            actionEnc.ethernet(k.eth_src, k.eth_dst)
                        case k: FlowKeyEtherType =>
                            actionEnc.etherType(k.etherType)
                        case k: FlowKeyICMPEcho =>
                            actionEnc.icmpEcho(k.icmp_type,
                                               k.icmp_code, k.icmp_id.toShort)
                        case k: FlowKeyICMPError =>
                            actionEnc.icmpError(k.icmp_type, k.icmp_code,
                                                k.icmp_data)
                        case k: FlowKeyICMP =>
                            actionEnc.icmp(k.icmp_type, k.icmp_code)
                        case k: FlowKeyIPv4 =>
                            actionEnc.ipv4(k.ipv4_src, k.ipv4_dst,
                                           k.ipv4_proto, k.ipv4_tos, k.ipv4_ttl,
                                           k.ipv4_frag)
                        case k: FlowKeyTCP =>
                            actionEnc.tcp(k.tcp_src.toShort, k.tcp_dst.toShort)
                        case k: FlowKeyTunnel =>
                            actionEnc.tunnel(k.tun_id, k.ipv4_src, k.ipv4_dst,
                                             k.tun_flags, k.ipv4_tos, k.ipv4_ttl)
                        case k: FlowKeyUDP =>
                            actionEnc.udp(k.udp_src.toShort, k.udp_dst.toShort)
                        case k: FlowKeyVLAN => actionEnc.vlan(k.vlan)
                        case _ => actionEnc.unknown()
                    }
                case _ => actionEnc.unknown()
            }
            i += 1
        }
        FLOW_SUMMARY.putFlowActions(actionsBytes, 0,
                                    actionsBuffer.position)
    }
}

