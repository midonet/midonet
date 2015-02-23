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

package org.midonet.midolman.state

import java.io.ByteArrayInputStream
import java.util.UUID

import com.google.protobuf.ByteString

import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState._
import org.midonet.midolman.state.TraceState.TraceKey
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.IPFragmentType
import org.midonet.packets._
import org.midonet.rpc.{FlowStateProto => Proto}

object FlowStatePackets {
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

    def isStateMessage(fmatch: FlowMatch): Boolean =
        fmatch.getTunnelKey == TUNNEL_KEY

    def makeUdpShell(data: Array[Byte]): Ethernet = {
        import org.midonet.packets.util.PacketBuilder._
        { eth addr SRC_MAC -> DST_MAC } <<
            { ip4 addr SRC_IP --> DST_IP } <<
                { udp ports UDP_PORT ---> UDP_PORT } <<
                    { payload(data) }
    }

    def makeFlowStateUdpShell(data: Array[Byte]): FlowStateEthernet =
        makeFlowStateUdpShell(data, data.length)

    def makeFlowStateUdpShell(data: Array[Byte],
                              length: Int): FlowStateEthernet = {
        val flowStateUdpShell = new FlowStateEthernet
        val elasticData = new ElasticData(data, length)
        flowStateUdpShell.setCore(elasticData)
        flowStateUdpShell
    }

    implicit def ipAddressFromProto(proto: Proto.IpAddress): IPAddr = {
        if (proto.getVersion == Proto.IpAddress.IpVersion.V4) {
            new IPv4Addr(proto.getQuad0)
        } else {
            val lower = proto.getQuad0.toLong | (proto.getQuad1.toLong << 32)
            val upper = proto.getQuad2.toLong | (proto.getQuad3.toLong << 32)
            new IPv6Addr(upper, lower)
        }
    }

    implicit def ipAddressToProto(ip: IPAddr): Proto.IpAddress = ip match {
        case v4: IPv4Addr =>
            Proto.IpAddress.newBuilder().
                setVersion(Proto.IpAddress.IpVersion.V4).
                setQuad0(v4.addr).build()

        case v6: IPv6Addr =>
            val q0 = (v6.lowerWord & 0xFFFFFFFF).toInt
            val q1 = ((v6.lowerWord >> 32) & 0xFFFFFFFF).toInt
            val q2 = (v6.upperWord & 0xFFFFFFFF).toInt
            val q3 = ((v6.upperWord >> 32) & 0xFFFFFFFF).toInt

            Proto.IpAddress.newBuilder().
                setVersion(Proto.IpAddress.IpVersion.V6).
                setQuad0(q0).setQuad1(q1).setQuad2(q2).setQuad3(q3).build()

        case _ => throw new IllegalArgumentException()
    }

    implicit def uuidFromProto(proto: Proto.UUID) = new UUID(proto.getMsb, proto.getLsb)

    implicit def uuidToProto(uuid: UUID): Proto.UUID =
            Proto.UUID.newBuilder().setMsb(uuid.getMostSignificantBits).
                setLsb(uuid.getLeastSignificantBits).build()

    implicit def natKeyTypeFromProto(t: Proto.NatKey.Type): KeyType = t match {
        case Proto.NatKey.Type.FWD_SNAT => NatState.FWD_SNAT
        case Proto.NatKey.Type.FWD_DNAT => NatState.FWD_DNAT
        case Proto.NatKey.Type.FWD_STICKY_DNAT => NatState.FWD_STICKY_DNAT
        case Proto.NatKey.Type.REV_SNAT => NatState.REV_SNAT
        case Proto.NatKey.Type.REV_DNAT => NatState.REV_DNAT
        case Proto.NatKey.Type.REV_STICKY_DNAT => NatState.REV_STICKY_DNAT
    }

    implicit def natKeyTypeToProto(t: KeyType): Proto.NatKey.Type = t match {
        case NatState.FWD_SNAT => Proto.NatKey.Type.FWD_SNAT
        case NatState.FWD_DNAT => Proto.NatKey.Type.FWD_DNAT
        case NatState.FWD_STICKY_DNAT => Proto.NatKey.Type.FWD_STICKY_DNAT
        case NatState.REV_SNAT => Proto.NatKey.Type.REV_SNAT
        case NatState.REV_DNAT => Proto.NatKey.Type.REV_DNAT
        case NatState.REV_STICKY_DNAT => Proto.NatKey.Type.REV_STICKY_DNAT
    }

    def connTrackKeyFromProto(proto: Proto.ConntrackKey) =
        ConnTrackKey(proto.getSrcIp, proto.getSrcPort,
                     proto.getDstIp, proto.getDstPort,
                     proto.getProtocol.toByte, proto.getDevice)

    def connTrackKeyToProto(key: ConnTrackKey) =
        Proto.ConntrackKey.newBuilder().
            setSrcIp(key.networkSrc).
            setDstIp(key.networkDst).
            setSrcPort(key.icmpIdOrTransportSrc).
            setDstPort(key.icmpIdOrTransportDst).
            setDevice(key.deviceId).
            setProtocol(key.networkProtocol).build()

    def natKeyToProto(key: NatKey) =
        Proto.NatKey.newBuilder().
            setSrcIp(key.networkSrc).
            setSrcPort(key.transportSrc).
            setDstIp(key.networkDst).
            setDstPort(key.transportDst).
            setDevice(key.deviceId).
            setProtocol(key.networkProtocol).
            setType(key.keyType).build()

    def natKeyFromProto(proto: Proto.NatKey) =
        NatKey(proto.getType,
               ipAddressFromProto(proto.getSrcIp).asInstanceOf[IPv4Addr],
               proto.getSrcPort,
               ipAddressFromProto(proto.getDstIp).asInstanceOf[IPv4Addr],
               proto.getDstPort,
               proto.getProtocol.toByte, proto.getDevice)

    def natBindingToProto(key: NatBinding) =
        Proto.NatValue.newBuilder().
            setIp(key.networkAddress).
            setPort(key.transportPort).build()

    def natBindingFromProto(proto: Proto.NatValue) =
        NatBinding(ipAddressFromProto(proto.getIp).asInstanceOf[IPv4Addr],
                   proto.getPort)

    def traceKeyToProto(key: TraceKey, proto: Proto.TraceEntry.Builder): Unit = {
        val m = key.flowMatch

        if (m.isUsed(Field.EthSrc)) { proto.setEthSrc(m.getEthSrc.asLong) }
        if (m.isUsed(Field.EthDst)) { proto.setEthDst(m.getEthDst.asLong) }
        if (m.isUsed(Field.EtherType)) { proto.setEtherType(m.getEtherType) }
        if (m.isUsed(Field.NetworkSrc)) { proto.setIpSrc(m.getNetworkSrcIP) }
        if (m.isUsed(Field.NetworkDst)) { proto.setIpDst(m.getNetworkDstIP) }
        if (m.isUsed(Field.NetworkProto)) {
            proto.setIpProto(m.getNetworkProto)
        }
        if (m.isUsed(Field.NetworkTTL)) { proto.setIpTtl(m.getNetworkTTL) }
        if (m.isUsed(Field.NetworkTOS)) { proto.setIpTos(m.getNetworkTOS) }
        if (m.isUsed(Field.FragmentType)) {
            proto.setIpFrag(m.getIpFragmentType().value)
        }
        if (m.isUsed(Field.SrcPort)) { proto.setTpSrc(m.getSrcPort) }
        if (m.isUsed(Field.DstPort)) { proto.setTpDst(m.getDstPort) }
        if (m.isUsed(Field.IcmpId)) { proto.setIcmpId(m.getIcmpIdentifier) }
        if (m.isUsed(Field.IcmpData)) {
            proto.setIcmpData(ByteString.copyFrom(m.getIcmpData()))
        }
        if (m.isUsed(Field.VlanId)) {
            val vlans = m.getVlanIds
            var i = vlans.size
            while (i > 0) {
                i -= 1
                proto.addVlanIds(vlans.get(i).toInt)
            }
        }
    }

    def traceKeyFromProto(proto: Proto.TraceEntry): TraceKey = {
        val m = new FlowMatch
        if (proto.hasEthSrc) { m.setEthSrc(new MAC(proto.getEthSrc)) }
        if (proto.hasEthDst) { m.setEthDst(new MAC(proto.getEthDst)) }
        if (proto.hasEtherType) { m.setEtherType(proto.getEtherType.toShort) }
        if (proto.hasIpSrc) {
            m.setNetworkSrc(
                ipAddressFromProto(proto.getIpSrc()).asInstanceOf[IPv4Addr])
        }
        if (proto.hasIpDst) {
            m.setNetworkDst(
                ipAddressFromProto(proto.getIpDst()).asInstanceOf[IPv4Addr])
        }
        if (proto.hasIpProto) { m.setNetworkProto(proto.getIpProto.toByte) }
        if (proto.hasIpTtl) { m.setNetworkTTL(proto.getIpTtl.toByte) }
        if (proto.hasIpTos) { m.setNetworkTOS(proto.getIpTos.toByte) }
        if (proto.hasIpFrag) {
            m.setIpFragmentType(IPFragmentType.fromByte(proto.getIpFrag.toByte))
        }
        if (proto.hasTpSrc) { m.setSrcPort(proto.getTpSrc) }
        if (proto.hasTpDst) { m.setDstPort(proto.getTpDst) }
        if (proto.hasIcmpId) { m.setIcmpIdentifier(proto.getIcmpId.toShort) }
        if (proto.hasIcmpData) { m.setIcmpData(proto.getIcmpData.toByteArray) }

        val vlans = proto.getVlanIdsList()
        var i = vlans.size()
        while (i > 0) {
            i -= 1
            m.addVlanId(vlans.get(i).toShort)
        }
        new TraceKey(m)
    }

    def parseDatagram(p: Ethernet): Proto.StateMessage  = {
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
                            case d: Data =>
                                val input = new ByteArrayInputStream(d.getData)
                                Proto.StateMessage.parseDelimitedFrom(input)
                            case _ => null
                        }

                    case _ => null
                }

            case _ => null
        }
    }
}
