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

package org.midonet.odp

import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.concurrent.ThreadLocalRandom

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.odp.flows._
import org.midonet.packets.{IPv4, IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class FlowMaskTest extends FlatSpec with Matchers {
    type Expected = (Array[FlowKey], FlowMatch) => Unit

    val rand = ThreadLocalRandom.current()

    implicit def toByteArray(b: Byte): Array[Byte] =
        ByteBuffer.allocate(1).put(b).array()

    implicit def toByteArray(s: Short): Array[Byte] =
        ByteBuffer.allocate(2).putShort(s).array()

    implicit def toByteArray(i: Int): Array[Byte] =
        ByteBuffer.allocate(4).putInt(i).array()

    implicit def toByteArray(l: Long): Array[Byte] =
        ByteBuffer.allocate(8).putLong(l).array()

    private def expect[K <: FlowKey](f: K => Array[Byte])
                                    (implicit m: Manifest[K]): Expected =
        (maskKeys, fmatch) => {
            val ctor = m.runtimeClass.getDeclaredConstructor()
            ctor.setAccessible(true)
            val id = ctor.newInstance().asInstanceOf[K].attrId() & 0xffff
            if (fmatch.getKeys.exists(_.attrId() == id)) {
                allOnes(f(maskKeys(id).asInstanceOf[K]))
            }
        }

    private def seeSomeFields(fmatch: FlowMatch) = {
        var expected = Set[Expected]()

        fmatch.getInputPortNumber
        expected += expect[FlowKeyInPort](_.portNo)

        if (rand.nextBoolean()) {
            fmatch.getTunnelKey
            expected += expect[FlowKeyTunnel](_.tun_id)
        }
        if (rand.nextBoolean()) {
            fmatch.getTunnelSrc
            expected += expect[FlowKeyTunnel](_.ipv4_src)
        }
        if (rand.nextBoolean()) {
            fmatch.getTunnelDst
            expected += expect[FlowKeyTunnel](_.ipv4_dst)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getEthSrc
            expected += expect[FlowKeyEthernet](_.eth_src)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getEthDst
            expected += expect[FlowKeyEthernet](_.eth_dst)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getVlanIds
            expected += expect[FlowKeyVLAN](_.vlan)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getEtherType
            expected += expect[FlowKeyEtherType](_.etherType)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getNetworkSrcIP
            expected += expect[FlowKeyIPv4](_.ipv4_src)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getNetworkDstIP
            expected += expect[FlowKeyIPv4](_.ipv4_dst)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getIpFragmentType
            expected += expect[FlowKeyIPv4](_.ipv4_frag)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getNetworkTOS
            expected += expect[FlowKeyIPv4](_.ipv4_tos)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getNetworkTTL
            expected += expect[FlowKeyIPv4](_.ipv4_ttl)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getNetworkProto
            expected += expect[FlowKeyIPv4](_.ipv4_proto)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getSrcPort
            expected += expect[FlowKeyUDP](_.udp_src.toShort)
            expected += expect[FlowKeyTCP](_.tcp_src.toShort)
            expected += expect[FlowKeyICMP](_.icmp_type)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getDstPort
            expected += expect[FlowKeyUDP](_.udp_dst.toShort)
            expected += expect[FlowKeyTCP](_.tcp_dst.toShort)
            expected += expect[FlowKeyICMP](_.icmp_code)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getNetworkSrcIP
            expected += expect[FlowKeyARP](_.arp_sip)
        }
        if (rand.nextInt(2) == 0) {
            fmatch.getNetworkDstIP
            expected += expect[FlowKeyARP](_.arp_tip)
        }
        if (fmatch.highestLayerSeen() >= 3) {
            expected += expect[FlowKeyEtherType](_.etherType)
            expected += expect[FlowKeyIPv4](_.ipv4_frag)
        }
        if (fmatch.highestLayerSeen() >= 4) {
            expected += expect[FlowKeyIPv4](_.ipv4_proto)
        }
        (fmatch, expected)
    }

    private def maskedFlowKeys(fmatch: FlowMatch) = {
        val mask = new FlowMask
        mask.calculateFor(fmatch, new ArrayList[FlowAction])
        val bb = ByteBuffer.allocate(1024*8)
        mask.serializeInto(bb)
        bb.flip()
        FlowMask.reader.deserializeFrom(bb)
    }

    private def allOnes(arr: Array[Byte]): Unit =
        for (b <- arr)
            b should be ((~0).toByte)

    private def verify(flowMatch: FlowMatch, flowMask: FlowMask,
                       expected: Set[Expected]) = {
        // Seen fields are a subset of used fields in the mask because some
        // fields are used although only one in the aggregating flow key was seen.
        val maskMatch = new FlowMatch()
        val flowKeys = flowMask.getKeys
        var i = 0
        while (i < flowKeys.length) {
            if (flowKeys(i) ne null)
                maskMatch.addKey(flowKeys(i))
            i += 1
        }
        (flowMatch.getSeenFields & maskMatch.getUsedFields) should be (flowMatch.getSeenFields)
        expected foreach (_(flowKeys, flowMatch))
    }

    "Only flow keys with exact matches" should "be serialized" in {
        (0 to 10000) map { _ =>
            FlowMatches.generateFlowMatch(ThreadLocalRandom.current())
        } map seeSomeFields foreach { case(fmatch, expected) =>
            verify(fmatch, maskedFlowKeys(fmatch), expected)
        }
    }

    "No ethertype key" should "cause a specific mask" in {
        val fmatch = new FlowMatch()
        fmatch.addKey(FlowKeys.inPort(9))
        val normalKey = FlowKeys.ethernet(MAC.random().getAddress, MAC.random().getAddress)
        fmatch.addKey(FlowKeys.ethernet(MAC.random().getAddress, MAC.random().getAddress))
        val mask = new FlowMask()
        mask.calculateFor(fmatch, new ArrayList[FlowAction]())
        val maskedKey = mask.getMaskFor(normalKey.attrId()).asInstanceOf[FlowKeyEthernet]
        allOnes(maskedKey.eth_src)
        allOnes(maskedKey.eth_dst)
    }

    "Set IP key flow actions" should "influence mask result" in {
        val fmatch = new FlowMatch()
        fmatch.addKey(FlowKeys.inPort(9))
        fmatch.addKey(FlowKeys.etherType(IPv4.ETHERTYPE))
        fmatch.addKey(FlowKeys.ethernet(MAC.random().getAddress, MAC.random().getAddress))
        fmatch.addKey(FlowKeys.ipv4(IPv4Addr.random, IPv4Addr.random, IpProtocol.TCP))
        val actions = new ArrayList[FlowAction]
        actions.add(FlowActions.setKey(
            FlowKeys.ipv4(IPv4Addr.random, IPv4Addr.random, IpProtocol.TCP)))
        val mask = new FlowMask()
        mask.calculateFor(fmatch, actions)
        val ethertypeMaskedKey = mask.getMaskFor(
            OpenVSwitch.FlowKey.Attr.Ethertype).asInstanceOf[FlowKeyEtherType]
        allOnes(ethertypeMaskedKey.etherType)
        val ipv4MaskedKey = mask.getMaskFor(
            OpenVSwitch.FlowKey.Attr.IPv4).asInstanceOf[FlowKeyIPv4]
        allOnes(ipv4MaskedKey.ipv4_proto)
    }

    "Set protocol key flow actions" should "influence mask result" in {
        val fmatch = new FlowMatch()
        fmatch.addKey(FlowKeys.inPort(9))
        fmatch.addKey(FlowKeys.etherType(IPv4.ETHERTYPE))
        fmatch.addKey(FlowKeys.ethernet(MAC.random().getAddress, MAC.random().getAddress))
        fmatch.addKey(FlowKeys.ipv4(IPv4Addr.random, IPv4Addr.random, IpProtocol.TCP))
        fmatch.addKey(FlowKeys.tcp(80, 80))
        val actions = new ArrayList[FlowAction]
        actions.add(FlowActions.setKey(FlowKeys.tcp(8080, 8080)))
        val mask = new FlowMask()
        mask.calculateFor(fmatch, actions)
        val ethertypeMaskedKey = mask.getMaskFor(
            OpenVSwitch.FlowKey.Attr.Ethertype).asInstanceOf[FlowKeyEtherType]
        allOnes(ethertypeMaskedKey.etherType)
        val ipv4MaskedKey = mask.getMaskFor(
            OpenVSwitch.FlowKey.Attr.IPv4).asInstanceOf[FlowKeyIPv4]
        allOnes(ipv4MaskedKey.ipv4_proto)
    }
}
