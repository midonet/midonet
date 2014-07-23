/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import java.util.UUID

import java.io.ByteArrayInputStream

import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState._
import org.midonet.packets.{Data, Ethernet, IPAddr, IPv4, IPv4Addr, IPv6Addr, MAC, UDP}
import org.midonet.rpc.{FlowStateProto => Proto}
import org.midonet.odp.{Packet, FlowMatch}
import org.midonet.odp.flows.FlowKeyTunnel

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

    /* 14 (eth) + 20 (ip) + 8 (udp) */
    val OVERHEAD = 42

    def isStateMessage(packet: Packet): Boolean = {
        var i = 0
        val flowKeys = packet.getMatch.getKeys
        while (i < flowKeys.size) {
            val key = flowKeys.get(i)
            key match {
                case tunnel: FlowKeyTunnel =>
                    return tunnel.getTunnelID == TUNNEL_KEY
                case _ => i += 1
            }
        }
        false
    }

    def makeUdpShell(data: Array[Byte]): Ethernet = {
        import org.midonet.packets.util.PacketBuilder._
        { eth addr SRC_MAC -> DST_MAC } <<
            { ip4 addr SRC_IP --> DST_IP } <<
                { udp ports UDP_PORT ---> UDP_PORT } <<
                    { payload(data) }
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

    implicit def natKeyTypeFromProto(t: Proto.NatKey.Type): NatKey.Type = t match {
        case Proto.NatKey.Type.FWD_SNAT => NatKey.FWD_SNAT
        case Proto.NatKey.Type.FWD_DNAT => NatKey.FWD_DNAT
        case Proto.NatKey.Type.FWD_STICKY_DNAT => NatKey.FWD_STICKY_DNAT
        case Proto.NatKey.Type.REV_SNAT => NatKey.REV_SNAT
        case Proto.NatKey.Type.REV_DNAT => NatKey.REV_DNAT
        case Proto.NatKey.Type.REV_STICKY_DNAT => NatKey.REV_STICKY_DNAT
    }

    implicit def natKeyTypeToProto(t: NatKey.Type): Proto.NatKey.Type = t match {
        case NatKey.FWD_SNAT => Proto.NatKey.Type.FWD_SNAT
        case NatKey.FWD_DNAT => Proto.NatKey.Type.FWD_DNAT
        case NatKey.FWD_STICKY_DNAT => Proto.NatKey.Type.FWD_STICKY_DNAT
        case NatKey.REV_SNAT => Proto.NatKey.Type.REV_SNAT
        case NatKey.REV_DNAT => Proto.NatKey.Type.REV_DNAT
        case NatKey.REV_STICKY_DNAT => Proto.NatKey.Type.REV_STICKY_DNAT
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
            setProtocol(key.networkProtocol).
            setType(key.keyType).build()

    def natKeyFromProto(proto: Proto.NatKey) =
        NatKey(proto.getType, proto.getSrcIp, proto.getSrcPort,
               proto.getDstIp, proto.getDstPort, proto.getProtocol.toByte)

    def natBindingToProto(key: NatBinding) =
        Proto.NatValue.newBuilder().
            setIp(key.networkAddress).
            setPort(key.transportPort).build()

    def natBindingFromProto(proto: Proto.NatValue) =
        NatBinding(proto.getIp, proto.getPort)

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
