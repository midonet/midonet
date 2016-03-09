/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.packets

import java.util.UUID

import uk.co.real_logic.sbe.codec.java.DirectBuffer

import org.midonet.cluster.flowstate.proto.{FlowState, InetAddrType, MessageHeader}

object FlowStateSbeEncoder {

    /**
      * Frame format for state replication messages:
      *
      *     +---------------------------------------+
      *     | Payload (protobufs message)           |
      *     +---------------------------------------+
      *     | UDP (arbitrary IANA-unnassigned port) |
      *     +---------------------------------------+
      *     | IPv4 (link-local addresses)           |
      *     +---------------------------------------+
      *     | Ethernet frame (mido addresses)       |
      *     +---------------------------------------+
      *     | Tunnel encap (key = 0xFFFFFF)         |
      *     +---------------------------------------+
      */
    val TUNNEL_KEY = 0xFFFFFF
    val SRC_MAC = MAC.fromString("AC:CA:BA:00:15:01")
    val DST_MAC = MAC.fromString("AC:CA:BA:00:15:02")
    val SRC_IP = IPv4Addr.fromString("169.254.15.1")
    val DST_IP = IPv4Addr.fromString("169.254.15.2")
    val UDP_PORT: Short = 2925

    val MTU = 1500
    // Ethernet(IEE 802.3): http://standards.ieee.org/about/get/802/802.3.html
    // IP: https://tools.ietf.org/html/rfc791
    // UDP: http://tools.ietf.org/html/rfc768
    // GRE: http://tools.ietf.org/html/rfc2784
    // 20(IP) + 8(GRE+Key)
    val GRE_ENCAPUSULATION_OVERHEAD = 28
    // 20(IP) + 8(GRE+Key) + 14(Ethernet w/o preamble and CRC) + 20(IP) + 8(UDP)
    val OVERHEAD = 70

    val MessageHeaderVersion = 1

    def makeUdpShell(data: Array[Byte]): Ethernet = {
        import org.midonet.packets.util.PacketBuilder._
        { eth addr SRC_MAC -> DST_MAC } <<
            { ip4 addr SRC_IP --> DST_IP } <<
                { udp ports UDP_PORT ---> UDP_PORT } <<
                    { payload(data) }
    }

    def uuidFromSbe(getter: (Int) => Long): UUID =
        new UUID(getter(0), getter(1))

    def uuidToSbe(uuid: UUID, setter: (Int, Long) => Unit) {
        if (uuid != null) {
            setter(0, uuid.getMostSignificantBits)
            setter(1, uuid.getLeastSignificantBits)
        }
    }

    def ipFromSbe(ipType: InetAddrType, getter: (Int) => Long): IPAddr =
        ipType match {
            case InetAddrType.IPv4 => new IPv4Addr(getter(0).toInt)
            case InetAddrType.IPv6 => new IPv6Addr(getter(0), getter(1))
            case _ => null
        }

    def ipToSbe(ip: IPAddr, setter: (Int, Long) => Unit) = ip match {
        case ip4: IPv4Addr => setter(0, ip4.addr)
        case ip6: IPv6Addr => {
            setter(0, ip6.upperWord)
            setter(1, ip6.lowerWord)
        }
        case _ =>
    }

    def ipSbeType(ip: IPAddr): InetAddrType = ip match {
        case ip4: IPv4Addr => InetAddrType.IPv4
        case ip6: IPv6Addr => InetAddrType.IPv6
        case _ => null
    }

    def macFromSbe(getter: (Int) => Int): MAC = {
        val address: Long =
            ((getter(0).toLong << 32) & 0xFFFF00000000L) |
            ((getter(1) << 16) & 0xFFFF0000L) |
            (getter(2) & 0xFFFFL)
        new MAC(address)
    }

    def macToSbe(mac: MAC, setter: (Int, Int) => Unit) {
        val address = mac.asLong
        setter(0, ((address >> 32).toInt & 0xFFFF))
        setter(1, ((address >> 16).toInt & 0xFFFF))
        setter(2, (address.toInt & 0xFFFF))
    }

    def parseDatagram(p: Ethernet): Data = {
        if (p.getDestinationMACAddress != DST_MAC ||
            p.getSourceMACAddress != SRC_MAC) {
            return null
        }

        p.getPayload match {
            case ip: IPv4 if ip.getSourceIPAddress == SRC_IP &&
                             ip.getDestinationIPAddress == DST_IP =>
                ip.getPayload match {
                    case udp: UDP if udp.getDestinationPort == UDP_PORT &&
                                     udp.getSourcePort == UDP_PORT =>
                        udp.getPayload match {
                            case d: Data => d
                            case _ => null
                        }
                    case _ => null
                }
            case _ => null
        }
    }
}

class FlowStateSbeEncoder {
    val flowStateHeader = new MessageHeader
    val flowStateMessage = new FlowState
    val flowStateBuffer = new DirectBuffer(new Array[Byte](0))

    def encodeTo(bytes: Array[Byte]): FlowState = {
        flowStateBuffer.wrap(bytes)
        flowStateHeader.wrap(flowStateBuffer, 0,
                             FlowStateSbeEncoder.MessageHeaderVersion)
            .blockLength(flowStateMessage.sbeBlockLength)
            .templateId(flowStateMessage.sbeTemplateId)
            .schemaId(flowStateMessage.sbeSchemaId)
            .version(flowStateMessage.sbeSchemaVersion)
        flowStateMessage.wrapForEncode(flowStateBuffer, flowStateHeader.size)
        flowStateMessage
    }

    def encodedLength(): Int = flowStateHeader.size + flowStateMessage.size

    def decodeFrom(bytes: Array[Byte]): FlowState = {
        flowStateBuffer.wrap(bytes)
        flowStateHeader.wrap(flowStateBuffer, 0,
                             FlowStateSbeEncoder.MessageHeaderVersion)
        val templateId = flowStateHeader.templateId
        if (templateId != FlowState.TEMPLATE_ID) {
            throw new IllegalArgumentException(
                s"Invalid template id for flow state $templateId")
        }
        flowStateMessage.wrapForDecode(flowStateBuffer, flowStateHeader.size,
                                       flowStateHeader.blockLength,
                                       flowStateHeader.version)
        flowStateMessage
    }
}
