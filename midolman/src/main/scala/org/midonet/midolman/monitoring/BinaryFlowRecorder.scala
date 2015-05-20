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
import java.util.{ArrayList, List, UUID}

import uk.co.real_logic.sbe.codec.java._

import org.midonet.midolman.PacketWorkflow
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.config.FlowHistoryConfig
import org.midonet.midolman.monitoring.proto.{SimulationResult => SbeSimResult}
import org.midonet.midolman.monitoring.proto.{RuleResult => SbeRuleResult, _}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows._
import org.midonet.packets.{IPAddr, IPv4Addr, IPv6Addr, MAC}
import org.midonet.sdn.flows.FlowTagger.DeviceTag

object BinaryFlowRecorder {
    val MessageTemplateVersion = 0
    val ActionsBufferSize = 100*1024
    val BufferSize = ActionsBufferSize + 100*1024
}

class BinaryFlowRecorder(hostId: UUID, config: FlowHistoryConfig)
        extends AbstractFlowRecorder(config) {
    val MESSAGE_HEADER = new MessageHeader
    val FLOW_SUMMARY = new FlowSummary
    val buffer = ByteBuffer.allocateDirect(BinaryFlowRecorder.BufferSize)
    val directBuffer = new DirectBuffer(buffer)

    val actionsBytes = new Array[Byte](BinaryFlowRecorder.ActionsBufferSize)
    val actionsBuffer = ByteBuffer.wrap(actionsBytes)

    override def encodeRecord(pktContext: PacketContext,
                              simRes: SimulationResult): ByteBuffer = {
        buffer.clear
        var bufferOffset = 0
        MESSAGE_HEADER.wrap(directBuffer, 0,
                            BinaryFlowRecorder.MessageTemplateVersion)
            .blockLength(FLOW_SUMMARY.sbeBlockLength)
            .templateId(FLOW_SUMMARY.sbeTemplateId)
            .schemaId(FLOW_SUMMARY.sbeSchemaVersion)
            .version(FLOW_SUMMARY.sbeSchemaVersion)
        bufferOffset += MESSAGE_HEADER.size

        FLOW_SUMMARY.wrapForEncode(directBuffer, bufferOffset)

        encodeSimpleValues(pktContext, simRes)
        encodeIcmpData(pktContext)
        encodeVlanIds(pktContext)
        encodeOutPorts(pktContext)
        encodeRules(pktContext)
        encodeDevices(pktContext)
        encodeFlowActions(pktContext.flowActions)

        buffer.limit(MESSAGE_HEADER.size + FLOW_SUMMARY.size)
        buffer
    }

    private def encodeSimpleValues(pktContext: PacketContext,
                                   simRes: SimulationResult): Unit = {
        val fmatch = pktContext.origMatch
        FLOW_SUMMARY.simResult(simRes match {
                           case PacketWorkflow.NoOp => SbeSimResult.NoOp
                           case PacketWorkflow.Drop => SbeSimResult.Drop
                           case PacketWorkflow.TemporaryDrop
                                   => SbeSimResult.TemporaryDrop
                           case PacketWorkflow.AddVirtualWildcardFlow
                                   => SbeSimResult.AddVirtualWildcardFlow
                           case PacketWorkflow.StateMessage
                                   => SbeSimResult.StateMessage
                           case PacketWorkflow.UserspaceFlow
                                   => SbeSimResult.UserspaceFlow
                           case PacketWorkflow.FlowCreated
                                   => SbeSimResult.FlowCreated
                           case PacketWorkflow.DuplicatedFlow
                                   => SbeSimResult.DuplicatedFlow
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
            .flowMatchNetworkProto(fmatch.getNetworkProto)
            .flowMatchNetworkTOS(fmatch.getNetworkTOS)
            .flowMatchNetworkTTL(fmatch.getNetworkTTL)
            .flowMatchIcmpId(fmatch.getIcmpIdentifier)

        encodeUUID(hostId, FLOW_SUMMARY.hostId)
        encodeUUID(pktContext.inputPort, FLOW_SUMMARY.inPort)
    }

    private def encodeIcmpData(pktContext: PacketContext): Unit = {
        var i = 0
        val data = pktContext.origMatch.getIcmpData()
        val iter = FLOW_SUMMARY.flowMatchIcmpDataCount(data.length)
        while (i < data.length) {
            iter.next().data(data(i))
            i += 1
        }
    }

    private def encodeMAC(address: MAC, setter: (Int, Short) => Unit): Unit = {
        if (address != null) {
            var i = 0
            var bytes = address.getAddress
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
                case ip4: IPv4Addr => {
                    typeSetter(InetAddrType.IPv4)
                    setter(0, ip4.addr)
                }
                case ip6: IPv6Addr => {
                    typeSetter(InetAddrType.IPv6)
                    setter(0, ip6.upperWord)
                    setter(1, ip6.lowerWord)
                }
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

    private def encodeRules(pktContext: PacketContext): Unit = {
        var i = 0
        val rules = pktContext.traversedRules
        val results = pktContext.traversedRuleResults
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
            }
            i += 1
        }
    }

    private def encodeDevices(pktContext: PacketContext): Unit = {
        var i = 0
        var count = 0
        val devices = pktContext.flowTags
        while (i < devices.size) {
            devices.get(i) match {
                case d: DeviceTag => {
                    count += 1
                }
            }
            i += 1
        }
        i = 0
        val iter = FLOW_SUMMARY.traversedDevicesCount(count)
        while (i < devices.size) {
            devices.get(i) match {
                case d: DeviceTag => {
                    var dev = iter.next()
                    dev.device(0, d.device.getMostSignificantBits)
                    dev.device(1, d.device.getLeastSignificantBits)
                }
                case _ =>
            }
            i += 1
        }
    }

    private def encodeFlowActions(actions: List[FlowAction]): Unit = {
        var i = 0
        var offset = 0
        while (i < actions.size) {
            val action = actions.get(i)
            val attrId = action.attrId
            actionsBuffer.position(offset+4)
            val size = action.serializeInto(actionsBuffer).toShort
            actionsBuffer.putShort(offset+0, size)
            actionsBuffer.putShort(offset+2, attrId)
            offset += size + 4
            i += 1
        }
        FLOW_SUMMARY.putFlowActions(actionsBytes, 0,
                                    actionsBuffer.position)
    }
}

class BinaryFlowRecord extends FlowRecord {
    val MESSAGE_HEADER = new MessageHeader
    val FLOW_SUMMARY = new FlowSummary
    val ACTIONS_DECODE_ARRAY = new Array[Byte](
        BinaryFlowRecorder.ActionsBufferSize)
    val actionsDecodeBuffer = ByteBuffer.wrap(ACTIONS_DECODE_ARRAY)
    val MAC_DECODE_ARRAY = new Array[Byte](
        FlowSummary.flowMatchEthernetSrcLength)
    val IP_DECODE_ARRAY = new Array[Long](
        FlowSummary.flowMatchNetworkSrcLength)

    def reset(buffer: ByteBuffer): Unit = {
        clear()
        val record = new FlowRecord

        val directBuffer = new DirectBuffer(buffer)
        MESSAGE_HEADER.wrap(directBuffer, 0,
                            BinaryFlowRecorder.MessageTemplateVersion)

        val templateId = MESSAGE_HEADER.templateId()
        if (templateId != FlowSummary.TEMPLATE_ID) {
            throw new IllegalArgumentException(
                s"TemplateId ${templateId} should be ${FlowSummary.TEMPLATE_ID}")
        }

        val actingBlockLength = MESSAGE_HEADER.blockLength()
        val schemaId = MESSAGE_HEADER.schemaId()
        val actingVersion = MESSAGE_HEADER.version();
        FLOW_SUMMARY.wrapForDecode(directBuffer, MESSAGE_HEADER.size,
                                   actingBlockLength, actingVersion)

        simResult = FLOW_SUMMARY.simResult match {
            case SbeSimResult.NoOp => PacketWorkflow.NoOp
            case SbeSimResult.Drop => PacketWorkflow.Drop
            case SbeSimResult.TemporaryDrop => PacketWorkflow.TemporaryDrop
            case SbeSimResult.AddVirtualWildcardFlow
                    => PacketWorkflow.AddVirtualWildcardFlow
            case SbeSimResult.StateMessage => PacketWorkflow.StateMessage
            case SbeSimResult.UserspaceFlow => PacketWorkflow.UserspaceFlow
            case SbeSimResult.FlowCreated => PacketWorkflow.FlowCreated
            case SbeSimResult.DuplicatedFlow => PacketWorkflow.DuplicatedFlow
            case SbeSimResult.GeneratedPacket => PacketWorkflow.GeneratedPacket
            case _ => null
        }
        cookie = FLOW_SUMMARY.cookie.toInt
        inPort = decodeUUID(i => FLOW_SUMMARY.inPort(i))

        flowMatch.setInputPortNumber(FLOW_SUMMARY.flowMatchInputPort.toInt)
        flowMatch.setTunnelKey(FLOW_SUMMARY.flowMatchTunnelKey.toInt)
        flowMatch.setTunnelSrc(FLOW_SUMMARY.flowMatchTunnelSrc.toInt)
        flowMatch.setTunnelDst(FLOW_SUMMARY.flowMatchTunnelDst.toInt)
        flowMatch.setEthSrc(
            decodeMAC(i => FLOW_SUMMARY.flowMatchEthernetSrc(i).toByte))
        flowMatch.setEthDst(
            decodeMAC(i => FLOW_SUMMARY.flowMatchEthernetDst(i).toByte))
        flowMatch.setEtherType(FLOW_SUMMARY.flowMatchEtherType.toShort)
        flowMatch.setNetworkSrc(
            decodeIPAddr(FLOW_SUMMARY.flowMatchNetworkSrcType,
                         i => FLOW_SUMMARY.flowMatchNetworkSrc(i)))
        flowMatch.setNetworkDst(
            decodeIPAddr(FLOW_SUMMARY.flowMatchNetworkDstType,
                         i => FLOW_SUMMARY.flowMatchNetworkDst(i)))
        flowMatch.setNetworkProto(FLOW_SUMMARY.flowMatchNetworkProto.toByte)
        flowMatch.setNetworkTOS(FLOW_SUMMARY.flowMatchNetworkTOS.toByte)
        flowMatch.setNetworkTTL(FLOW_SUMMARY.flowMatchNetworkTTL.toByte)
        flowMatch.setIcmpIdentifier(FLOW_SUMMARY.flowMatchIcmpId.toShort)

        val icmpData = FLOW_SUMMARY.flowMatchIcmpData()

        val data = new Array[Byte](icmpData.count())
        var i = 0
        val icmpDataIter = icmpData.iterator
        while (icmpDataIter.hasNext) {
            data(i) = icmpDataIter.next().data.toByte
            i += 1
        }
        flowMatch.setIcmpData(data)
        val vlanIter = FLOW_SUMMARY.flowMatchVlanIds.iterator
        while (vlanIter.hasNext) {
            flowMatch.getVlanIds.add(vlanIter.next.vlanId.toShort)
        }

        val outportIter = FLOW_SUMMARY.outPorts.iterator
        while (outportIter.hasNext) {
            val port = outportIter.next
            val uuid = decodeUUID(i => port.port(i))
            outPorts.add(uuid)
        }

        val rulesIter = FLOW_SUMMARY.traversedRules.iterator
        while (rulesIter.hasNext) {
            val r = rulesIter.next()
            val uuid = decodeUUID(i => r.rule(i))
            val result = new RuleResult(
                r.result match {
                    case SbeRuleResult.ACCEPT =>
                        RuleResult.Action.ACCEPT
                    case SbeRuleResult.CONTINUE =>
                        RuleResult.Action.CONTINUE
                    case SbeRuleResult.DROP =>
                        RuleResult.Action.DROP
                    case SbeRuleResult.JUMP =>
                        RuleResult.Action.JUMP
                    case SbeRuleResult.REJECT =>
                        RuleResult.Action.REJECT
                    case SbeRuleResult.RETURN =>
                        RuleResult.Action.RETURN
                    case _ => null
                }, null)
            rules.add(new TraversedRule(uuid,
                                        result))
        }

        val devicesIter = FLOW_SUMMARY.traversedDevices.iterator
        while (devicesIter.hasNext) {
            val device = devicesIter.next
            val uuid = decodeUUID(i => device.device(i))
            devices.add(uuid)
        }

        decodeActions()
    }

    private def decodeMAC(getByte: (Int) => Byte): MAC = {
        var i = 0
        while (i < MAC_DECODE_ARRAY.length) {
            MAC_DECODE_ARRAY(i) = getByte(i)
            i += 1
        }
        MAC.fromAddress(MAC_DECODE_ARRAY)
    }

    private def decodeIPAddr(iptype: InetAddrType,
                             getLong: (Int) => Long): IPAddr = {
        var i = 0
        while (i < IP_DECODE_ARRAY.length) {
            IP_DECODE_ARRAY(i) = getLong(i)
            i += 1
        }
        iptype match {
            case InetAddrType.IPv4 => new IPv4Addr(IP_DECODE_ARRAY(0).toInt)
            case InetAddrType.IPv6 => new IPv6Addr(IP_DECODE_ARRAY(0),
                                                   IP_DECODE_ARRAY(1))
            case _ => null
        }
    }

    private def decodeUUID(getLong: (Int) => Long): UUID = {
        var i = 0
        while (i < IP_DECODE_ARRAY.length) {
            IP_DECODE_ARRAY(i) = getLong(i)
            i += 1
        }
        new UUID(IP_DECODE_ARRAY(0), IP_DECODE_ARRAY(1))
    }

    private def decodeActions(): Unit = {
        val bytes = FLOW_SUMMARY.getFlowActions(ACTIONS_DECODE_ARRAY, 0,
                                                ACTIONS_DECODE_ARRAY.length)
        actionsDecodeBuffer.position(0)
        actionsDecodeBuffer.limit(bytes)
        while (actionsDecodeBuffer.remaining >= 4) {
            val size = actionsDecodeBuffer.getShort()
            val attr = actionsDecodeBuffer.getShort()
            val action = FlowActions.newBlankInstance(attr)
            if (action == null) {
                throw new IllegalStateException(
                    s"Found an illegal attr type ${attr}")
            }
            val newlimit = actionsDecodeBuffer.position() + size
            val oldlimit = actionsDecodeBuffer.limit
            actionsDecodeBuffer.limit(newlimit)
            action.deserializeFrom(actionsDecodeBuffer)
            actions.add(action)

            actionsDecodeBuffer.position(newlimit)
            actionsDecodeBuffer.limit(oldlimit)
        }
    }
}
