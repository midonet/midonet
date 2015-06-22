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

import scala.collection.JavaConverters._

import java.nio.ByteBuffer
import java.util.{ArrayList, HashSet, List, Set, UUID}

import org.midonet.cluster.flowhistory._
import org.midonet.midolman.PacketWorkflow
import org.midonet.midolman.PacketWorkflow.{SimulationResult => MMSimRes}
import org.midonet.midolman.config.FlowHistoryConfig
import org.midonet.midolman.rules.{RuleResult => MMRuleResult}
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows._
import org.midonet.packets.MAC
import org.midonet.sdn.flows.FlowTagger.{DeviceTag, FlowTag}

class JsonFlowRecorder(hostId: UUID, config: FlowHistoryConfig)
        extends AbstractFlowRecorder(config) {

    val serializer = new JsonSerialization

    override def encodeRecord(pktContext: PacketContext,
                              simRes: MMSimRes): ByteBuffer = {
        ByteBuffer.wrap(
            serializer.flowRecordToBuffer(buildRecord(pktContext, simRes)))
    }

    private def buildRecord(pktContext: PacketContext,
                            simRes: MMSimRes): FlowRecord = {
        FlowRecord(hostId, pktContext.inPortId,
                   buildFlowRecordMatch(pktContext.origMatch),
                   pktContext.cookie, buildDevices(pktContext.flowTags),
                   buildRules(pktContext), buildSimResult(simRes),
                   pktContext.outPorts, buildActions(pktContext.flowActions))
    }

    private def buildFlowRecordMatch(fmatch: FlowMatch): FlowRecordMatch = {
        FlowRecordMatch(fmatch.getInputPortNumber, fmatch.getTunnelKey,
                        fmatch.getTunnelSrc, fmatch.getTunnelDst,
                        fmatch.getEthSrc.getAddress, fmatch.getEthDst.getAddress,
                        fmatch.getEtherType, fmatch.getNetworkSrcIP.toBytes,
                        fmatch.getNetworkDstIP.toBytes, fmatch.getNetworkProto,
                        fmatch.getNetworkTTL, fmatch.getNetworkTOS,
                        fmatch.getIpFragmentType.value,
                        fmatch.getSrcPort, fmatch.getDstPort,
                        fmatch.getIcmpIdentifier, fmatch.getIcmpData,
                        fmatch.getVlanIds)
    }

    private def buildActions(actions: List[FlowAction]): Set[Actions.FlowAction] = {
        val recActions = new HashSet[Actions.FlowAction]

        def setKeyAction(action: FlowActionSetKey): Unit = {
            action.getFlowKey match {
                case a: FlowKeyARP =>
                    recActions.add(Actions.Arp(a.arp_sip, a.arp_tip, a.arp_op,
                                               a.arp_sha, a.arp_tha))
                case a: FlowKeyEthernet =>
                    recActions.add(Actions.Ethernet(a.eth_src, a.eth_dst))
                case a: FlowKeyEtherType =>
                    recActions.add(Actions.EtherType(a.etherType))
                case a: FlowKeyICMPEcho =>
                    recActions.add(Actions.IcmpEcho(a.icmp_type,
                                                    a.icmp_code, a.icmp_id))
                case a: FlowKeyICMPError =>
                    recActions.add(Actions.IcmpError(a.icmp_type,
                                                     a.icmp_code,
                                                     a.icmp_data))
                case a: FlowKeyICMP =>
                    recActions.add(Actions.Icmp(a.icmp_type, a.icmp_code))
                case a: FlowKeyIPv4 =>
                    recActions.add(Actions.IPv4(a.ipv4_src, a.ipv4_dst,
                                                a.ipv4_proto, a.ipv4_tos,
                                                a.ipv4_ttl, a.ipv4_frag))
                case a: FlowKeyTCP =>
                    recActions.add(Actions.TCP(a.tcp_src.toShort,
                                               a.tcp_dst.toShort))
                case a: FlowKeyTunnel =>
                    recActions.add(Actions.Tunnel(a.tun_id.toInt, a.ipv4_src,
                                                  a.ipv4_dst, a.tun_flags,
                                                  a.ipv4_tos, a.ipv4_ttl))
                case a: FlowKeyUDP =>
                    recActions.add(Actions.UDP(a.udp_src.toShort,
                                               a.udp_dst.toShort))
                case a: FlowKeyVLAN =>
                    recActions.add(Actions.VLan(a.vlan))
                case _ =>
            }
        }

        var i = 0
        while (i < actions.size) {
            actions.get(i) match {
                case a: FlowActionOutput =>
                    recActions.add(Actions.Output(a.getPortNumber))
                case a: FlowActionPopVLAN =>
                    recActions.add(Actions.PopVlan())
                case a: FlowActionPushVLAN =>
                    recActions.add(Actions.PushVlan(a.getTagProtocolIdentifier,
                                            a.getTagControlIdentifier))
                case a: FlowActionSetKey =>
                    setKeyAction(a)
                case a: FlowActionUserspace =>
                    recActions.add(Actions.Userspace(a.uplinkPid, a.userData))
                case _ =>
            }
            i += 1
        }
        recActions
    }

    def buildDevices(tags: List[FlowTag]): List[UUID] = {
        tags.asScala.collect({ case d: DeviceTag => d.device }).asJava
    }

    def buildRules(pktContext: PacketContext): List[TraversedRule] = {
        var i = 0
        val rules = new ArrayList[TraversedRule]

        def convertResult(result: MMRuleResult): RuleResult.RuleResult =
            result.action match {
                case MMRuleResult.Action.ACCEPT => RuleResult.ACCEPT
                case MMRuleResult.Action.CONTINUE => RuleResult.CONTINUE
                case MMRuleResult.Action.DROP => RuleResult.DROP
                case MMRuleResult.Action.JUMP => RuleResult.JUMP
                case MMRuleResult.Action.REJECT => RuleResult.REJECT
                case MMRuleResult.Action.RETURN => RuleResult.RETURN
                case _ => RuleResult.UNKNOWN
            }
        while (i < pktContext.traversedRules.size) {
            rules.add(TraversedRule(
                          pktContext.traversedRules.get(i),
                          convertResult(pktContext.traversedRuleResults.get(i))))
            i += 1
        }
        rules
    }

    def buildSimResult(simRes: MMSimRes): SimulationResult.SimulationResult = {
        simRes match {
            case PacketWorkflow.NoOp => SimulationResult.NOOP
            case PacketWorkflow.Drop => SimulationResult.DROP
            case PacketWorkflow.ErrorDrop => SimulationResult.TEMP_DROP
            case PacketWorkflow.AddVirtualWildcardFlow =>
                SimulationResult.ADD_VIRTUAL_WILDCARD_FLOW
            case PacketWorkflow.StateMessage => SimulationResult.STATE_MESSAGE
            case PacketWorkflow.UserspaceFlow =>
                SimulationResult.USERSPACE_FLOW
            case PacketWorkflow.FlowCreated => SimulationResult.FLOW_CREATED
            case PacketWorkflow.DuplicatedFlow => SimulationResult.DUPE_FLOW
            case PacketWorkflow.GeneratedPacket =>
                SimulationResult.GENERATED_PACKET
            case _ =>
                SimulationResult.UNKNOWN
        }
    }

}

