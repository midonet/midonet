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

package org.midonet.midolman.util

import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.Await

import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.simulation.{Router => SimRouter}
import org.midonet.midolman.topology.VirtualTopologyActor.tryAsk
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.packets._
import org.midonet.midolman.NotYetException

trait RouterHelper extends SimulationHelper { this: MidolmanTestCase =>

    def expectEmitIcmp(fromMac: MAC, fromIp: IPv4Addr,
                               toMac: MAC, toIp: IPv4Addr,
                               icmpType: Byte, icmpCode: Byte) {
        val pkt = fishForRequestOfType[EmitGeneratedPacket](dedupProbe()).eth
        assertExpectedIcmpPacket(fromMac, fromIp, toMac, toIp, icmpType,
            icmpCode, pkt)
    }

    def assertExpectedIcmpPacket(fromMac: MAC, fromIp: IPv4Addr,
                                 toMac: MAC, toIp: IPv4Addr,
                                 icmpType: Byte, icmpCode: Byte,
                                 pkt: Ethernet){
        pkt.getEtherType should be (IPv4.ETHERTYPE)
        pkt.getSourceMACAddress should be (fromMac)
        pkt.getDestinationMACAddress should be (toMac)
        val ipPkt = pkt.getPayload.asInstanceOf[IPv4]
        ipPkt.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPkt.getDestinationAddress should be (toIp.addr)
        ipPkt.getSourceAddress should be (fromIp.addr)
        val icmpPkt = ipPkt.getPayload.asInstanceOf[ICMP]
        icmpPkt.getType should be (icmpType)
        icmpPkt.getCode should be (icmpCode)
    }

    def expectEmitArpRequest(port: UUID, fromMac: MAC, fromIp: IPv4Addr,
                                     toIp: IPv4Addr) {
        val toMac = MAC.fromString("ff:ff:ff:ff:ff:ff")
        val msg = fishForRequestOfType[EmitGeneratedPacket](dedupProbe())
        msg.egressPort should be (port)
        val eth = msg.eth
        eth.getSourceMACAddress should be (fromMac)
        eth.getDestinationMACAddress should be (toMac)
        eth.getEtherType should be (ARP.ETHERTYPE)
        eth.getPayload.getClass should be (classOf[ARP])
        val arp = eth.getPayload.asInstanceOf[ARP]
        arp.getHardwareAddressLength should be (6)
        arp.getProtocolAddressLength should be (4)
        arp.getSenderHardwareAddress should be (fromMac)
        arp.getTargetHardwareAddress should be (MAC.fromString("00:00:00:00:00:00"))
        IPv4Addr.fromBytes(arp.getSenderProtocolAddress) should be (fromIp)
        IPv4Addr.fromBytes(arp.getTargetProtocolAddress) should be (toIp)
        arp.getOpCode should be (ARP.OP_REQUEST)
    }

    def fetchRouterAndPort(portName: String,
                           portId: UUID) : (SimRouter, RouterPort) = {
        val port = try {
            tryAsk[RouterPort](portId)
        } catch { case NotYetException(f, _) =>
            Await.result(f.mapTo[RouterPort], 1 second)
        }
        val router = try {
            tryAsk[SimRouter](port.deviceId)
        } catch { case NotYetException(f, _) =>
            Await.result(f.mapTo[SimRouter], 1 second)
        }
        drainProbes()
        (router, port)
    }
}
