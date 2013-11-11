/*
 * Copyright 2013 Midokura KK
 */

package org.midonet.packets

import scala.collection.mutable

import org.junit.runner.RunWith
import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

import org.midonet.packets.util.PacketBuilder._
import java.nio.ByteBuffer

@RunWith(classOf[JUnitRunner])
class PacketsTest extends Suite with ShouldMatchers {
    private def shouldBeDifferent(a: IPacket, b: IPacket) {
        a should not be(b)
        java.util.Arrays.equals(a.serialize, b.serialize) should be(false)
    }

    private def checkSerialization(a: IPacket, deserializer: Array[Byte] => IPacket) {
        val b: IPacket = deserializer(a.serialize)

        a.## should be === b.##
        a should be === b
        java.util.Arrays.equals(a.serialize, b.serialize) should be(true)
    }

    private def verifyEquality(packets: Seq[IPacket], deserializer: ByteBuffer => IPacket) {
        for (pkt <- packets) { checkSerialization(pkt, (bytes) => deserializer(ByteBuffer.wrap(bytes, 0, bytes.length))) }
        for (pair <- packets.toList.combinations(2)) { shouldBeDifferent(pair.head, pair.last) }
    }

    private def verifyHashes(packets: Seq[IPacket]) =
        for (pair <- packets.toList.combinations(2)) { pair.head.## should not be(pair.last.##) }

    def testEthernet() {
        val packets = mutable.ArrayBuffer[Ethernet]()

        packets += eth addr "02:02:02:01:01:01" -> eth_zero
        packets += eth addr "02:02:02:01:01:01" -> eth_bcast
        packets += eth addr "02:02:02:01:01:01" -> eth_bcast priority 0x02 vlan 0x200
        packets += eth addr "02:02:02:01:01:01" -> eth_bcast priority 0x02 vlan 0x200 ether_type 0x01

        verifyEquality(packets, (buf) => new Ethernet().deserialize(buf).setPayload(null))
        verifyHashes(packets)

        (eth.with_pad addr "02:02:02:01:01:01" -> eth_bcast).serialize.size should be === 60
    }

    def testIPv4() {
        val packets = mutable.ArrayBuffer[IPv4]()

        packets += ip4 addr ip4_zero --> ip4_bcast
        packets += ip4 addr "192.168.100.1" --> ip4_bcast
        packets += ip4 addr "192.168.100.1" --> "192.168.100.2"
        packets += ip4 addr "192.168.100.1" --> "192.168.100.2" ttl 32
        packets += ip4 addr "192.168.100.1" --> "192.168.100.2" ttl 32 version 5
        packets += ip4 addr "192.168.100.1" --> "192.168.100.2" ttl 32 version 5 diff_serv 0x10
        packets += ip4 addr "192.168.100.1" --> "192.168.100.2" ttl 32 version 5 diff_serv 0x10 flags 0x01
        packets += ip4 addr "192.168.100.1" --> "192.168.100.2" ttl 32 version 5 diff_serv 0x10 flags 0x02 frag_offset 1200
        packets += ip4 addr "192.168.100.1" --> "192.168.100.2" ttl 32 version 5 diff_serv 0x10 flags 0x01 proto 0x20
        packets += ip4 addr "192.168.100.1" --> "192.168.100.2" ttl 32 version 5 diff_serv 0x10 flags 0x01 proto 0x20 options Array[Byte](0x10, 0x20, 0x0f, 0x2f)


        verifyEquality(packets, (buf) => new IPv4().deserialize(buf).setPayload(null))
        verifyHashes(packets)
    }

    def testARP() {
        val packets = mutable.ArrayBuffer[ARP]()

        packets += arp.req mac "00:02:00:44:00:44" -> eth_bcast ip "192.168.0.1" --> "192.168.0.2"
        packets += arp.req mac "00:02:00:44:00:55" -> eth_bcast ip "192.168.0.1" --> "192.168.0.2"
        packets += arp.req mac "00:02:00:44:00:55" -> eth_bcast ip "192.168.0.1" --> "192.168.0.3"
        packets += arp.req mac "00:02:00:44:00:55" -> eth_bcast ip "192.168.0.2" --> "192.168.0.3"
        packets += arp.req mac "00:02:00:44:00:55" -> eth_zero ip "192.168.0.2" --> "192.168.0.3"
        packets += arp.reply mac "00:02:00:44:00:55" -> eth_zero ip "192.168.0.2" --> "192.168.0.3"

        verifyEquality(packets, (buf) => new ARP().deserialize(buf).setPayload(null))
        verifyHashes(packets)
    }

    def testUDP() {
        val packets = mutable.ArrayBuffer[UDP]()

        packets += { udp src 4001 dst 5002 } << payload("abcd")
        packets += { udp src 5001 dst 5002 } << payload("abcd")
        packets += { udp src 5001 dst 4002 } << payload("abcd")
        packets += { udp src 5001 dst 4002 } << payload("abcdefgh")

        verifyEquality(packets, (buf) => new UDP().deserialize(buf))
        verifyHashes(packets)
    }

    def testICMP() {
        val packets = mutable.ArrayBuffer[ICMP]()
        val tcpPkt = { ip4 addr "192.168.0.1" --> "192.168.0.2" } << { tcp src 20000 dst 80 }

        packets += icmp.echo.request id 0x200 seq 0x120 data "data"
        packets += icmp.echo.request id 0x400 seq 0x120 data "data"
        packets += icmp.echo.request id 0x400 seq 0x001 data "data"
        packets += icmp.echo.request id 0x200 seq 0x120 data "12345678"
        packets += icmp.echo.reply id 0x200 seq 0x120 data "data"
        packets += icmp.echo.reply id 0x400 seq 0x120 data "data"
        packets += icmp.echo.reply id 0x400 seq 0x001 data "data"
        packets += icmp.echo.reply id 0x200 seq 0x120 data "12345678"

        packets += icmp.unreach.net culprit tcpPkt
        packets += icmp.unreach.host culprit tcpPkt
        packets += icmp.unreach.protocol culprit tcpPkt
        packets += icmp.unreach.port culprit tcpPkt
        packets += icmp.unreach.source_route culprit tcpPkt
        packets += icmp.unreach.filter culprit tcpPkt
        packets += icmp.unreach.frag_needed frag_size 1200 culprit tcpPkt

        packets += icmp.time_exceeded.ttl culprit tcpPkt
        packets += icmp.time_exceeded.reassembly culprit tcpPkt

        verifyEquality(packets, (buf) => new ICMP().deserialize(buf).setPayload(null))
    }

    def testIPv4Subnet() {
        val bytes = Array[Byte](0x0a, 0x0f, 0x0f, 0x21)
        val subnet = new IPv4Subnet(0x0a0f0f21, 24)
        val net = IPv4Addr(0x0a0f0f00)
        val bcast = IPv4Addr(0x0a0f0fff)
        val host = new IPv4Subnet(0x0a0f0f21, 32)

        subnet.getAddress should be === host.getAddress
        subnet.containsAddress(IPv4Addr(0x0a0f0f21)) should be === true
        subnet.containsAddress(IPv4Addr(0x0a0f0d21)) should be === false
        host.getAddress should be === IPv4Addr.fromBytes(bytes)
        subnet.toNetworkAddress should be === net
        subnet.toBroadcastAddress should be === bcast
        subnet.getAddress should be === host.getAddress
        subnet should be === new IPv4Subnet(IPv4Addr.fromBytes(bytes), 24)

        subnet.## should not be equal(host.##)
    }

}
