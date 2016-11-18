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

import scala.collection.JavaConverters._

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import org.midonet.cluster.flowhistory._
import org.midonet.midolman.PacketWorkflow
import org.midonet.midolman.PacketWorkflow.{SimulationResult => MMSimRes}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.rules.{RuleResult => MMRuleResult}
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows._
import org.midonet.sdn.flows.FlowTagger._

trait FlowRecorder {
    def record(pktContext: PacketContext, simRes: MMSimRes): Unit
}

object FlowRecorder {
    val log = Logger(LoggerFactory.getLogger(classOf[FlowRecorder]))

    def apply(config: MidolmanConfig, hostId: UUID,
              flowSenderWorker: FlowSenderWorker): FlowRecorder = {
        log.info("Creating flow recorder with " +
                     s"(${config.flowHistory.encoding}) encoding")
        if (config.flowHistory.enabled &&
            config.flowHistory.endpointService.nonEmpty) {
            config.flowHistory.encoding match {
                case "json" => new JsonFlowRecorder(
                    hostId, flowSenderWorker)
                case "binary" => new BinaryFlowRecorder(hostId,
                                                        flowSenderWorker)
                case "none" => NullFlowRecorder()
                case other =>
                    log.error(s"Invalid encoding ($other) specified")
                    NullFlowRecorder()
            }
        } else {
            if (config.flowHistory.enabled) {
                log.warn("Flow history disabled because no endpoint service " +
                             "specified")
            }
            NullFlowRecorder()
        }
    }
}

/**
  * Null implementation of flow recorder, for use when flow recording
  * is disabled.
  */
class NullFlowRecorder extends FlowRecorder {
    override def record(pktContext: PacketContext, simRes: MMSimRes):
            Unit = {
        // do nothing
    }
}

object NullFlowRecorder {
    def apply(): FlowRecorder = new NullFlowRecorder
}

/**
  * Abstract flow recorder example that sends summaries over a tcp port.
  */
abstract class AbstractFlowRecorder(flowSenderWorker: FlowSenderWorker)
    extends FlowRecorder {
    private val log = Logger(LoggerFactory.getLogger("org.midonet.history"))

    final override def record(pktContext: PacketContext, simRes: MMSimRes):
    Unit = {
        try {
            val buffer = encodeRecord(pktContext: PacketContext, simRes)
            flowSenderWorker.submit(buffer)
        } catch {
            case ex: IndexOutOfBoundsException =>
                log.info(s"Too many information to encode: " +
                         "drop the packet history. " + ex.toString)
            case t: Throwable =>
                log.warn("FlowHistory: Error sending data", t)
        }
    }

    def encodeRecord(pktContext: PacketContext,
                     simRes: MMSimRes): ByteBuffer
}

object FlowRecordBuilder {
    def buildRecord(hostId: UUID,
                    pktContext: PacketContext,
                    simRes: MMSimRes): FlowRecord = {
        FlowRecord(hostId, pktContext.inputPort,
                   buildFlowRecordMatch(pktContext.origMatch),
                   pktContext.cookie, buildDevices(pktContext.flowTags),
                   buildRules(pktContext), buildSimResult(simRes),
                   pktContext.outPorts, buildActions(pktContext.flowActions))
    }

    private def buildFlowRecordMatch(fmatch: FlowMatch): FlowRecordMatch = {
        FlowRecordMatch(fmatch.getInputPortNumber,
                        fmatch.getTunnelKey,
                        fmatch.getTunnelSrc,
                        fmatch.getTunnelDst,
                        if(fmatch.getEthSrc == null) {
                            Array.emptyByteArray
                        } else {
                            fmatch.getEthSrc.getAddress
                        },
                        if(fmatch.getEthDst == null) {
                            Array.emptyByteArray
                        } else {
                            fmatch.getEthDst.getAddress
                        },
                        fmatch.getEtherType,
                        if(fmatch.getNetworkSrcIP == null) {
                            Array.emptyByteArray
                        } else {
                            fmatch.getNetworkSrcIP.toBytes
                        },
                        if (fmatch.getNetworkDstIP == null) {
                            Array.emptyByteArray
                        } else {
                            fmatch.getNetworkDstIP.toBytes
                        },
                        fmatch.getNetworkProto,
                        fmatch.getNetworkTTL,
                        fmatch.getNetworkTOS,
                        fmatch.getIpFragmentType.value,
                        fmatch.getSrcPort,
                        fmatch.getDstPort,
                        fmatch.getIcmpIdentifier.toShort,
                        fmatch.getIcmpData,
                        fmatch.getVlanIds)
    }

    private def buildActions(actions: List[FlowAction]): List[Actions.FlowAction] = {
        val recActions = new ArrayList[Actions.FlowAction]

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
                                                    a.icmp_code,
                                                    a.icmp_id.toShort))
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
                    recActions.add(Actions.Tunnel(a.tun_id, a.ipv4_src,
                                                  a.ipv4_dst, a.tun_flags,
                                                  a.ipv4_tos, a.ipv4_ttl))
                case a: FlowKeyUDP =>
                    recActions.add(Actions.UDP(a.udp_src.toShort,
                                               a.udp_dst.toShort))
                case a: FlowKeyVLAN =>
                    recActions.add(Actions.VLan(a.vlan))
                case _ =>
                    recActions.add(Actions.Unknown())
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
                    recActions.add(Actions.Userspace(a.uplinkPid,
                                                     if (a.userData == null) 0
                                                     else a.userData))
                case _ =>
                    recActions.add(Actions.Unknown())
            }
            i += 1
        }
        recActions
    }

    def buildDevices(tags: List[FlowTag]): List[TraversedDevice] = {
        tags.asScala.collect(
            {
                case t: LoadBalancerDeviceTag =>
                    TraversedDevice(t.device, DeviceType.LOAD_BALANCER)
                case t: PoolDeviceTag =>
                    TraversedDevice(t.device, DeviceType.POOL)
                case t: PortGroupDeviceTag =>
                    TraversedDevice(t.device, DeviceType.PORT_GROUP)
                case t: BridgeDeviceTag =>
                    TraversedDevice(t.device, DeviceType.BRIDGE)
                case t: RouterDeviceTag =>
                    TraversedDevice(t.device, DeviceType.ROUTER)
                case t: PortDeviceTag =>
                    TraversedDevice(t.device, DeviceType.PORT)
                case t: ChainDeviceTag =>
                    TraversedDevice(t.device, DeviceType.CHAIN)
                case t: MirrorDeviceTag =>
                    TraversedDevice(t.device, DeviceType.MIRROR)
            }).asJava
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
                case MMRuleResult.Action.REDIRECT => RuleResult.REDIRECT
                case MMRuleResult.Action.REJECT => RuleResult.REJECT
                case MMRuleResult.Action.RETURN => RuleResult.RETURN
                case _ => RuleResult.UNKNOWN
            }
        while (i < pktContext.traversedRules.size) {
            rules.add(TraversedRule(
                          pktContext.traversedRules.get(i),
                          convertResult(pktContext.traversedRuleResults.get(i)),
                          pktContext.traversedRulesMatched.get(i),
                          pktContext.traversedRulesApplied.get(i)))
            i += 1
        }
        rules
    }

    def buildSimResult(simRes: MMSimRes): SimulationResult.SimulationResult = {
        simRes match {
            case PacketWorkflow.NoOp => SimulationResult.NOOP
            case PacketWorkflow.Drop => SimulationResult.DROP
            case PacketWorkflow.ErrorDrop => SimulationResult.ERROR_DROP
            case PacketWorkflow.ShortDrop => SimulationResult.SHORT_DROP
            case PacketWorkflow.AddVirtualWildcardFlow =>
                SimulationResult.ADD_VIRTUAL_WILDCARD_FLOW
            case PacketWorkflow.UserspaceFlow =>
                SimulationResult.USERSPACE_FLOW
            case PacketWorkflow.FlowCreated => SimulationResult.FLOW_CREATED
            case PacketWorkflow.GeneratedPacket =>
                SimulationResult.GENERATED_PACKET
            case _ =>
                SimulationResult.UNKNOWN
        }
    }
}
