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

package org.midonet.midolman.datapath

import java.util.UUID

import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.{Packet, FlowMatch}
import org.midonet.packets.util.PacketBuilder
import org.midonet.packets._
import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.junit.JUnitRunner
import org.midonet.packets.util.PacketBuilder._
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class MSSClampTest extends FlatSpec with Matchers {
    val log = Logger(LoggerFactory.getLogger(this.getClass))

    val srcMac1 = MAC.fromString("01:02:03:04:05:06")
    val srcMac2 = MAC.fromString("07:08:09:0A:0B:0C")
    val dstMac1 = MAC.fromString("10:20:30:40:50:60")
    val dstMac2 = MAC.fromString("70:80:90:A0:B0:C0")

    val srcIp1 = IPv4Addr.fromString("10.0.0.1")
    val srcIp2 = IPv4Addr.fromString("10.0.0.2")
    val dstIp1 = IPv4Addr.fromString("20.0.0.1")
    val dstIp2 = IPv4Addr.fromString("20.0.0.2")

    val srcPort1: Short = 10001
    val srcPort2: Short = 10002
    val dstPort1: Short = 20001
    val dstPort2: Short = 20002

    val synFlags = TCP.Flag.allOf(List(TCP.Flag.Syn))
    val synAckFlags = TCP.Flag.allOf(List(TCP.Flag.Syn, TCP.Flag.Ack))
    val ackFlags = TCP.Flag.allOf(List(TCP.Flag.Ack))

    "clampMss" should "do nothing for regular TCP packets" in {
        val pkt = { eth src srcMac1 dst dstMac1 } <<
                  { ip4 src srcIp1 dst dstIp1 } <<
                  { tcp src srcPort1 dst dstPort1 flags synFlags mss 1460 }
        val ctx = makeCtx(pkt)
        log.debug("*****" + ctx.packet.getEthernet)
        PacketExecutor.clampMss(ctx, log)
        checkMss(ctx.packet.getEthernet.getPayload.getPayload, 1460)
    }

    it should "Reduce MSS for encapsulated packet" in {
        val pkt = { eth src srcMac2 dst dstMac2 } <<
                  { ip4 src srcIp2 dst dstIp2 } <<
                  { udp src srcPort2 dst dstPort2 } <<
                  { vxlan vni 5 } <<
                  { eth src srcMac1 dst dstMac1 } <<
                  { ip4 src srcIp1 dst dstIp2 } <<
                  { tcp src srcPort1 dst dstPort1 flags synFlags mss 1460 }
        val ctx = makeCtx(pkt)
        log.debug("*****" + ctx.packet.getEthernet)
        PacketExecutor.clampMss(ctx, log)
        val vxlanPkt = ctx.packet.getEthernet.getPayload.getPayload.getPayload
        checkMss(vxlanPkt.getPayload.getPayload.getPayload, 1410)
    }

    private var cookie = 1
    private def makeCtx(bldr: PacketBuilder[Ethernet],
                        setInputPort: Boolean = true): PacketContext = {
        val pkt = new Packet(bldr.packet, new FlowMatch())
        val ctx = new PacketContext(cookie, pkt, pkt.getMatch)
        cookie += 1
        if (setInputPort)
            ctx.inputPort = UUID.randomUUID()
        ctx
    }

    private def checkMss(pkt: IPacket, mss: Short): Unit = {
        pkt shouldBe a[TCP]
        pkt.asInstanceOf[TCP].getOptions should contain inOrder(
            2, 4, (mss >> 8).toByte, mss.toByte)
    }
}
