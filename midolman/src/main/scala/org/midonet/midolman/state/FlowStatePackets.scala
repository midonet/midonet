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

import java.util.UUID

import org.midonet.cluster.flowstate.proto.{FlowState => FlowStateSbe, _}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState._
import org.midonet.midolman.state.TraceState.TraceKey
import org.midonet.odp.FlowMatch
import org.midonet.packets._


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

    val MessageHeaderVersion = FlowStateSbeEncoder.MessageHeaderVersion

    def isStateMessage(fmatch: FlowMatch): Boolean =
        fmatch.getTunnelKey == TUNNEL_KEY

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

    def natKeyTypeFromSbe(t: NatKeyType): KeyType = t match {
        case NatKeyType.FWD_SNAT => NatState.FWD_SNAT
        case NatKeyType.FWD_DNAT => NatState.FWD_DNAT
        case NatKeyType.FWD_STICKY_DNAT => NatState.FWD_STICKY_DNAT
        case NatKeyType.REV_SNAT => NatState.REV_SNAT
        case NatKeyType.REV_DNAT => NatState.REV_DNAT
        case NatKeyType.REV_STICKY_DNAT => NatState.REV_STICKY_DNAT
        case _ => null
    }

    def natKeySbeType(t: KeyType): NatKeyType = t match {
        case NatState.FWD_SNAT => NatKeyType.FWD_SNAT
        case NatState.FWD_DNAT => NatKeyType.FWD_DNAT
        case NatState.FWD_STICKY_DNAT => NatKeyType.FWD_STICKY_DNAT
        case NatState.REV_SNAT => NatKeyType.REV_SNAT
        case NatState.REV_DNAT => NatKeyType.REV_DNAT
        case NatState.REV_STICKY_DNAT => NatKeyType.REV_STICKY_DNAT
    }

    def connTrackKeyFromSbe(conntrack: FlowStateSbe.Conntrack): ConnTrackKey = {
        ConnTrackKey(ipFromSbe(conntrack.srcIpType, conntrack.srcIp),
                     conntrack.srcPort,
                     ipFromSbe(conntrack.dstIpType, conntrack.dstIp),
                     conntrack.dstPort,
                     conntrack.protocol.toByte,
                     uuidFromSbe(conntrack.device))
    }

    def connTrackKeyToSbe(key: ConnTrackKey,
                          conntrack: FlowStateSbe.Conntrack): Unit = {
        uuidToSbe(key.deviceId, conntrack.device)
        ipToSbe(key.networkSrc, conntrack.srcIp)
        ipToSbe(key.networkDst, conntrack.dstIp)
        conntrack.srcPort(key.icmpIdOrTransportSrc)
        conntrack.dstPort(key.icmpIdOrTransportDst)
        conntrack.protocol(key.networkProtocol)
        conntrack.srcIpType(ipSbeType(key.networkSrc))
        conntrack.dstIpType(ipSbeType(key.networkDst))
    }

    def natKeyFromSbe(nat: FlowStateSbe.Nat): NatKey =
        NatKey(natKeyTypeFromSbe(nat.keyType),
               ipFromSbe(nat.keySrcIpType, nat.keySrcIp).asInstanceOf[IPv4Addr],
               nat.keySrcPort,
               ipFromSbe(nat.keyDstIpType, nat.keyDstIp).asInstanceOf[IPv4Addr],
               nat.keyDstPort, nat.keyProtocol.toByte,
               uuidFromSbe(nat.keyDevice))

    def natBindingFromSbe(nat: FlowStateSbe.Nat): NatBinding =
        NatBinding(
            ipFromSbe(nat.valueIpType, nat.valueIp).asInstanceOf[IPv4Addr],
            nat.valuePort)

    def natToSbe(key: NatKey, value: NatBinding,
                 nat: FlowStateSbe.Nat): Unit = {
        uuidToSbe(key.deviceId, nat.keyDevice)
        ipToSbe(key.networkSrc, nat.keySrcIp)
        ipToSbe(key.networkDst, nat.keyDstIp)
        ipToSbe(value.networkAddress, nat.valueIp)
        nat.keySrcPort(key.transportSrc)
        nat.keyDstPort(key.transportDst)
        nat.valuePort(value.transportPort)
        nat.keyProtocol(key.networkProtocol)
        nat.keySrcIpType(ipSbeType(key.networkSrc))
        nat.keyDstIpType(ipSbeType(key.networkDst))
        nat.valueIpType(ipSbeType(value.networkAddress))
        nat.keyType(natKeySbeType(key.keyType))
    }

    def traceFromSbe(trace: FlowStateSbe.Trace): TraceKey =
        TraceKey(macFromSbe(trace.srcMac),
                 macFromSbe(trace.dstMac),
                 trace.etherType.toShort,
                 ipFromSbe(trace.srcIpType, trace.srcIp),
                 ipFromSbe(trace.dstIpType, trace.dstIp),
                 trace.protocol.toByte,
                 trace.srcPort, trace.dstPort)

    def traceToSbe(flowTraceId: UUID, key: TraceKey,
                   trace: FlowStateSbe.Trace): Unit = {
        uuidToSbe(flowTraceId, trace.flowTraceId)
        ipToSbe(key.networkSrc, trace.srcIp)
        ipToSbe(key.networkDst, trace.dstIp)
        macToSbe(key.ethSrc, trace.srcMac)
        macToSbe(key.ethDst, trace.dstMac)
        trace.srcPort(key.srcPort)
        trace.dstPort(key.dstPort)
        trace.etherType(key.etherType)
        trace.protocol(key.networkProto)
        trace.srcIpType(ipSbeType(key.networkSrc))
        trace.dstIpType(ipSbeType(key.networkDst))
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
