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

package org.midonet.odp.flows

import org.midonet.odp.OpenVSwitch.FlowKey.Attr
import org.midonet.packets._

object FlowKeyApplier {
    private val applyKey = Array.fill[(FlowKey, Ethernet) => Unit](Attr.MAX)(
        (fk, eth) => { })

    {
        applyKey(Attr.Ethernet) = applyEthernet
        applyKey(Attr.ARP) = applyArp
        applyKey(Attr.IPv4) = applyIpv4
        applyKey(Attr.IPv6) = applyIpv6
        applyKey(Attr.TCP) = applyTcp
        applyKey(Attr.UDP) = applyUdp
    }

    def apply(fk: FlowKey, eth: Ethernet): Unit =
        applyKey(fk.attrId() & Attr.MASK)(fk, eth)

    private def applyEthernet(fk: FlowKey, eth: Ethernet): Unit = {
        val fkEth = fk.asInstanceOf[FlowKeyEthernet]
        eth.setSourceMACAddress(MAC.fromAddress(fkEth.eth_src))
        eth.setDestinationMACAddress(MAC.fromAddress(fkEth.eth_dst))
    }

    private def applyArp(fk: FlowKey, eth: Ethernet): Unit = {
        val fkArp = fk.asInstanceOf[FlowKeyARP]
        val arp = eth.getPayload.asInstanceOf[ARP]
        arp.setOpCode(fkArp.arp_op)
        arp.setSenderHardwareAddress(MAC.fromAddress(fkArp.arp_sha))
        arp.setSenderProtocolAddress(IPv4Addr.fromInt(fkArp.arp_sip).toBytes)
        arp.setTargetHardwareAddress(MAC.fromAddress(fkArp.arp_tha))
        arp.setTargetProtocolAddress(IPv4Addr.fromInt(fkArp.arp_tip).toBytes)
    }

    private def applyIpv4(fk: FlowKey, eth: Ethernet): Unit = {
        val fkIp = fk.asInstanceOf[FlowKeyIPv4]
        val ip = eth.getPayload.asInstanceOf[IPv4]
        ip.setSourceAddress(fkIp.ipv4_src)
        ip.setDestinationAddress(fkIp.ipv4_dst)
        ip.setDiffServ(fkIp.ipv4_tos)
        ip.setTtl(fkIp.ipv4_ttl)
        ip.clearChecksum()
    }

    private def applyIpv6(fk: FlowKey, eth: Ethernet): Unit = {
        val fkIp = fk.asInstanceOf[FlowKeyIPv6]
        val ip = eth.getPayload.asInstanceOf[IPv6]
        ip.setSourceAddress(IPv6Addr.fromInts(fkIp.ipv6_src))
        ip.setDestinationAddress(IPv6Addr.fromInts(fkIp.ipv6_dst))
        ip.setHopLimit(fkIp.ipv6_hlimit)
        ip.setFlowLabel(fkIp.ipv6_label)
        ip.setTrafficClass(fkIp.ipv6_tclass)
    }

    private def applyTcp(fk: FlowKey, eth: Ethernet): Unit = {
        val fkTcp = fk.asInstanceOf[FlowKeyTCP]
        val tcp = eth.getPayload.getPayload.asInstanceOf[TCP]
        tcp.setSourcePort(fkTcp.tcp_src)
        tcp.setDestinationPort(fkTcp.tcp_dst)
    }

    private def applyUdp(fk: FlowKey, eth: Ethernet): Unit = {
        val fkUdp = fk.asInstanceOf[FlowKeyUDP]
        val udp = eth.getPayload.getPayload.asInstanceOf[UDP]
        udp.setSourcePort(fkUdp.udp_src)
        udp.setDestinationPort(fkUdp.udp_dst)
    }
}
