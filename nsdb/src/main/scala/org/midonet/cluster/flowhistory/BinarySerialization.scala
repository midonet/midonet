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
package org.midonet.cluster.flowhistory

import scala.collection.JavaConverters._

import java.nio.ByteBuffer
import java.util.{ArrayList, List => JList, UUID}

import Actions._
import uk.co.real_logic.sbe.codec.java._

import org.midonet.cluster.flowhistory.proto.{SimulationResult => SbeSimResult, RuleResult => SbeRuleResult, _}
import org.midonet.packets.{IPv4Addr, IPv6Addr}


object BinarySerialization {
    val MessageTemplateVersion = 0
    val ActionsBufferSize = 100*1024
    val BufferSize = ActionsBufferSize + 100*1024
}

class BinarySerialization {
    val MESSAGE_HEADER = new MessageHeader
    val FLOW_SUMMARY = new FlowSummary

    val MAC_DECODE_ARRAY = new Array[Byte](
        FlowSummary.flowMatchEthernetSrcLength)
    val IP_DECODE_ARRAY = new Array[Long](
        FlowSummary.flowMatchNetworkSrcLength)

    val buffer = ByteBuffer.allocateDirect(BinarySerialization.BufferSize)
    val directBuffer = new DirectBuffer(buffer)

    val actionsBytes = new Array[Byte](BinarySerialization.ActionsBufferSize)
    val actionsBuffer = ByteBuffer.wrap(actionsBytes)

    def bufferToFlowRecord(buffer: Array[Byte]): FlowRecord = {
        val directBuffer = new DirectBuffer(ByteBuffer.wrap(buffer))
        MESSAGE_HEADER.wrap(directBuffer, 0,
                            BinarySerialization.MessageTemplateVersion)
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

        val simResult = FLOW_SUMMARY.simResult match {
            case SbeSimResult.NoOp => SimulationResult.NOOP
            case SbeSimResult.Drop => SimulationResult.DROP
            case SbeSimResult.ShortDrop => SimulationResult.SHORT_DROP
            case SbeSimResult.ErrorDrop => SimulationResult.ERROR_DROP
            case SbeSimResult.AddVirtualWildcardFlow
                    => SimulationResult.ADD_VIRTUAL_WILDCARD_FLOW
            case SbeSimResult.StateMessage => SimulationResult.STATE_MESSAGE
            case SbeSimResult.UserspaceFlow => SimulationResult.USERSPACE_FLOW
            case SbeSimResult.FlowCreated => SimulationResult.FLOW_CREATED
            case SbeSimResult.DuplicatedFlow => SimulationResult.DUPE_FLOW
            case SbeSimResult.GeneratedPacket => SimulationResult.GENERATED_PACKET
            case _ => SimulationResult.UNKNOWN
        }
        val cookie = FLOW_SUMMARY.cookie.toInt
        val inPort = decodeUUID(i => FLOW_SUMMARY.inPort(i))
        val hostId = decodeUUID(i => FLOW_SUMMARY.hostId(i))

        val fmatch = FlowRecordMatch(FLOW_SUMMARY.flowMatchInputPort.toInt,
                                     FLOW_SUMMARY.flowMatchTunnelKey.toLong,
                                     FLOW_SUMMARY.flowMatchTunnelSrc.toInt,
                                     FLOW_SUMMARY.flowMatchTunnelDst.toInt,
                                     decodeMAC(i => FLOW_SUMMARY.flowMatchEthernetSrc(i).toByte),
                                     decodeMAC(i => FLOW_SUMMARY.flowMatchEthernetDst(i).toByte),
                                     FLOW_SUMMARY.flowMatchEtherType.toShort,
                                     decodeIPAddr(FLOW_SUMMARY.flowMatchNetworkSrcType,
                                                  i => FLOW_SUMMARY.flowMatchNetworkSrc(i)),
                                     decodeIPAddr(FLOW_SUMMARY.flowMatchNetworkDstType,
                                                  i => FLOW_SUMMARY.flowMatchNetworkDst(i)),
                                     FLOW_SUMMARY.flowMatchNetworkProto.toByte,
                                     FLOW_SUMMARY.flowMatchNetworkTTL.toByte,
                                     FLOW_SUMMARY.flowMatchNetworkTOS.toByte,
                                     FLOW_SUMMARY.flowMatchIPFragType.value.toByte,
                                     FLOW_SUMMARY.flowMatchSrcPort.toInt,
                                     FLOW_SUMMARY.flowMatchDstPort.toInt,
                                     FLOW_SUMMARY.flowMatchIcmpId.toShort,
                                     {
                                         val icmpData = FLOW_SUMMARY.flowMatchIcmpData()
                                         val data = new Array[Byte](icmpData.count())
                                         var i = 0
                                         val icmpDataIter = icmpData.iterator
                                         while (icmpDataIter.hasNext) {
                                             data(i) = icmpDataIter.next().data.toByte
                                             i += 1
                                         }
                                         data
                                     },
                                     {
                                         val vlans = new ArrayList[java.lang.Short]
                                         val vlanIter = FLOW_SUMMARY.flowMatchVlanIds.iterator
                                         while (vlanIter.hasNext) {
                                             vlans.add(vlanIter.next.vlanId.toShort)
                                         }
                                         vlans
                                     })
        val outPorts = new ArrayList[UUID]
        val outportIter = FLOW_SUMMARY.outPorts.iterator
        while (outportIter.hasNext) {
            val port = outportIter.next
            val uuid = decodeUUID(i => port.port(i))
            outPorts.add(uuid)
        }

        val rules = new ArrayList[TraversedRule]
        val rulesIter = FLOW_SUMMARY.traversedRules.iterator
        while (rulesIter.hasNext) {
            val r = rulesIter.next()
            val uuid = decodeUUID(i => r.rule(i))
            val result = r.result match {
                    case SbeRuleResult.ACCEPT => RuleResult.ACCEPT
                    case SbeRuleResult.CONTINUE => RuleResult.CONTINUE
                    case SbeRuleResult.DROP => RuleResult.DROP
                    case SbeRuleResult.JUMP => RuleResult.JUMP
                    case SbeRuleResult.REJECT => RuleResult.REJECT
                    case SbeRuleResult.RETURN => RuleResult.RETURN
                    case _ => RuleResult.UNKNOWN
                }
            rules.add(new TraversedRule(uuid, result))
        }

        val devices = new ArrayList[UUID]
        val devicesIter = FLOW_SUMMARY.traversedDevices.iterator
        while (devicesIter.hasNext) {
            val device = devicesIter.next
            val uuid = decodeUUID(i => device.device(i))
            devices.add(uuid)
        }

        val actions = decodeActions()

        FlowRecord(hostId, inPort, fmatch, cookie, devices, rules,
                   simResult, outPorts, actions)
    }

    private def decodeMAC(getByte: (Int) => Byte): Array[Byte] = {
        var i = 0
        val data = new Array[Byte](FlowSummary.flowMatchEthernetSrcLength)
        while (i < MAC_DECODE_ARRAY.length) {
            data(i) = getByte(i)
            i += 1
        }
        data
    }

    private def decodeIPAddr(iptype: InetAddrType,
                             getLong: (Int) => Long): Array[Byte] = {
        var i = 0

        while (i < IP_DECODE_ARRAY.length) {
            IP_DECODE_ARRAY(i) = getLong(i)
            i += 1
        }
        iptype match {
            case InetAddrType.IPv4 => new IPv4Addr(IP_DECODE_ARRAY(0).toInt).toBytes
            case InetAddrType.IPv6 => new IPv6Addr(IP_DECODE_ARRAY(0),
                                                   IP_DECODE_ARRAY(1)).toBytes
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

    private def decodeActions(): JList[Actions.FlowAction] = {
        val bytes = FLOW_SUMMARY.getFlowActions(actionsBytes, 0,
                                                actionsBytes.length)
        actionsBuffer.position(0)
        actionsBuffer.limit(bytes)

        val encoder = new ActionEncoder()
        encoder.decode(actionsBuffer)
    }
}

object ActionEncoder {
    val OUTPUT: Byte = 1
    val POP_VLAN: Byte = 2
    val PUSH_VLAN: Byte = 3
    val USERSPACE: Byte = 4
    val ARP: Byte = 5
    val ETHERNET: Byte = 6
    val ETHERTYPE: Byte = 7
    val ICMPECHO: Byte = 8
    val ICMPERROR: Byte = 9
    val ICMP: Byte = 10
    val IPV4: Byte = 11
    val TCP: Byte = 12
    val TUNNEL: Byte = 13
    val UDP: Byte = 14
    val VLAN: Byte = 15
    val UNKNOWN: Byte = Byte.MaxValue
}

class ActionEncoder {
    import ActionEncoder._

    def writeCount(count: Byte)(implicit buffer: ByteBuffer): Unit = {
        buffer.put(count)
    }

    def output(portNo: Int)(implicit buffer: ByteBuffer): Unit = {
        buffer.put(OUTPUT)
        buffer.putInt(portNo)
    }

    def popVlan()(implicit buffer: ByteBuffer): Unit = {
        buffer.put(POP_VLAN)
    }

    def pushVlan(tpid: Short, tci: Short)(implicit buffer: ByteBuffer): Unit = {
        buffer.put(PUSH_VLAN)
        buffer.putShort(tpid)
        buffer.putShort(tci)
    }

    def userspace(uplinkId: Int, userData: Long)
                 (implicit buffer: ByteBuffer): Unit = {
        buffer.put(USERSPACE)
        buffer.putInt(uplinkId)
        buffer.putLong(userData)
    }

    def arp(sip: Int, tip: Int, op: Short, sha: Array[Byte], tha: Array[Byte])
           (implicit buffer: ByteBuffer): Unit = {
        buffer.put(ARP)
        buffer.putInt(sip)
        buffer.putInt(tip)
        buffer.putShort(op)
        buffer.put(sha.length.toByte)
        buffer.put(sha)
        buffer.put(tha.length.toByte)
        buffer.put(tha)
    }

    def ethernet(src: Array[Byte], dst: Array[Byte])
                (implicit buffer: ByteBuffer): Unit = {
        buffer.put(ETHERNET)
        buffer.put(src.length.toByte)
        buffer.put(src)
        buffer.put(dst.length.toByte)
        buffer.put(dst)
    }

    def etherType(`type`: Short)(implicit buffer: ByteBuffer): Unit = {
        buffer.put(ETHERTYPE)
        buffer.putShort(`type`)
    }

    def icmpEcho(`type`: Byte, code: Byte, id: Short)
                (implicit buffer: ByteBuffer): Unit = {
        buffer.put(ICMPECHO)
        buffer.put(`type`)
        buffer.put(code)
        buffer.putShort(id)
    }

    def icmpError(`type`: Byte, code: Byte, data: Array[Byte])
                 (implicit buffer: ByteBuffer): Unit = {
        buffer.put(ICMPERROR)
        buffer.put(`type`)
        buffer.put(code)
        buffer.put(data.length.toByte)
        buffer.put(data)
    }

    def icmp(`type`: Byte, code: Byte)(implicit buffer: ByteBuffer): Unit = {
        buffer.put(ICMP)
        buffer.put(`type`)
        buffer.put(code)
    }

    def ipv4(src: Int, dst: Int, proto: Byte, tos: Byte, ttl: Byte, frag: Byte)
            (implicit buffer: ByteBuffer): Unit = {
        buffer.put(IPV4)
        buffer.putInt(src)
        buffer.putInt(dst)
        buffer.put(proto)
        buffer.put(tos)
        buffer.put(ttl)
        buffer.put(frag)
    }

    def tcp(src: Short, dst: Short)(implicit buffer: ByteBuffer): Unit = {
        buffer.put(TCP)
        buffer.putShort(src)
        buffer.putShort(dst)
    }

    def tunnel(id: Long, ipv4_src: Int, ipv4_dst: Int,
               flags: Short, ipv4_tos: Byte,
               ipv4_ttl: Byte)(implicit buffer: ByteBuffer): Unit = {
        buffer.put(TUNNEL)
        buffer.putLong(id)
        buffer.putInt(ipv4_src)
        buffer.putInt(ipv4_dst)
        buffer.putShort(flags)
        buffer.put(ipv4_tos)
        buffer.put(ipv4_ttl)
    }

    def udp(src: Short, dst: Short)(implicit buffer: ByteBuffer): Unit = {
        buffer.put(UDP)
        buffer.putShort(src)
        buffer.putShort(dst)
    }

    def vlan(id: Short)(implicit buffer: ByteBuffer): Unit = {
        buffer.put(VLAN)
        buffer.putShort(id)
    }

    def unknown()(implicit buffer: ByteBuffer): Unit = {
        buffer.put(UNKNOWN)
    }

    def decodeAction(buffer: ByteBuffer): Actions.FlowAction = {
        buffer.get() match {
            case OUTPUT => Output(buffer.getInt())
            case POP_VLAN => PopVlan()
            case PUSH_VLAN => PushVlan(buffer.getShort(), buffer.getShort())
            case USERSPACE => Userspace(buffer.getInt(), buffer.getLong())
            case ARP => Arp(buffer.getInt(), buffer.getInt(), buffer.getShort(),
                            {
                                val sha = new Array[Byte](buffer.get())
                                buffer.get(sha)
                                sha
                            },
                            {
                                val tha = new Array[Byte](buffer.get())
                                buffer.get(tha)
                                tha
                            })
            case ETHERNET => Ethernet({
                                          val src = new Array[Byte](buffer.get())
                                          buffer.get(src)
                                          src
                                      },
                                      {
                                          val dst = new Array[Byte](buffer.get())
                                          buffer.get(dst)
                                          dst
                                      })
            case ETHERTYPE => EtherType(buffer.getShort())
            case ICMPECHO => IcmpEcho(buffer.get(), buffer.get(), buffer.getShort())
            case ICMPERROR => IcmpError(buffer.get(), buffer.get(),
                                        {
                                            val data = new Array[Byte](buffer.get())
                                            buffer.get(data)
                                            data
                                        })
            case ICMP => Icmp(buffer.get(), buffer.get())
            case IPV4 => Actions.IPv4(buffer.getInt(), buffer.getInt(),
                                      buffer.get(), buffer.get(),
                                      buffer.get(), buffer.get())
            case TCP => Actions.TCP(buffer.getShort(), buffer.getShort())
            case TUNNEL => Tunnel(buffer.getLong(), buffer.getInt(),
                                  buffer.getInt(), buffer.getShort(),
                                  buffer.get(), buffer.get())
            case UDP => Actions.UDP(buffer.getShort(), buffer.getShort())
            case VLAN => VLan(buffer.getShort())
            case UNKNOWN => Unknown()
            case unhandled : Byte => throw new IllegalArgumentException(
                s"Unhandled action type ${unhandled},"
                    + " server and client compatible?")
        }
    }

    def decode(buffer: ByteBuffer): JList[Actions.FlowAction] = {
        val actions = new ArrayList[Actions.FlowAction]
        val count = buffer.get()
        var i = 0
        while (i < count) {
            actions.add(decodeAction(buffer))
            i += 1
        }
        actions
    }
}

