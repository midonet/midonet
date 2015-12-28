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
package org.midonet.odp.test

import org.midonet.odp.FlowMatch.Field

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try

import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.odp.ports._
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.util.{IPv6Builder, EthBuilder}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{Ethernet, IPv4Addr, MAC}
import org.midonet.util.BatchCollector

trait TapTrafficInjectBase {

    var tapWrapper: TapWrapper = _
    var tapPort: DpPort = _
    def con: OvsConnectionOps

    def initTap(dpF: Future[Datapath]): Unit = {
        Try(new TapWrapper("odp-test-tap", false).remove())
        tapWrapper = new TapWrapper("odp-test-tap")
        val tapDpPort = new NetDevPort(tapWrapper.getName)
        tapPort = Await.result(dpF flatMap { con createPort(tapDpPort, _) }, 1 second)
    }
    
    def closeTap(dpF: Future[Datapath]): Unit = {
        Await.result(dpF flatMap { con delPort(tapPort, _) }, 1 second)
        tapWrapper.remove()
    }

    def tapPortNum = tapPort.getPortNo
}

trait MegaFlowTest extends TapTrafficInjectBase {

    def con: OvsConnectionOps

    final val NULL_IP = IPv4Addr.fromString("0.0.0.0")

    private[this] val srcIp = "100.0.1.1"
    private[this] val srcIpA = IPv4Addr.fromString(srcIp)
    private[this] val dstIp = "100.0.1.2"
    private[this] val dstIpA = IPv4Addr.fromString(dstIp)
    private[this] val srcMac = "10:00:00:00:00:01"
    private[this] val dstMac = "10:00:00:00:00:02"
    private[this] val srcMacBytes = MAC.fromString(srcMac).getAddress
    private[this] val dstMacBytes = MAC.fromString(dstMac).getAddress
    private[this] val srcPort = 5000
    private[this] val dstPort = 5000

    private[this] val handler = new Handler()

    private[this] class Handler extends BatchCollector[Packet] {

        val counter = new java.util.concurrent.atomic.AtomicInteger(0)
        def submit(p: Packet) {
            counter.getAndIncrement()
        }

        def endBatch() {}
        def stats() = counter.getAndSet(0)
    }

    def createWFlow(dpF: Future[Datapath], f: Flow): Flow =
        Await.result(dpF flatMap { con createFlow(f, _) }, 1 second)

    def delWFlow(dpF: Future[Datapath], f: Flow): Unit =
        Await.result(dpF flatMap { con delFlow(f, _) }, 1 second)

    def checkIpv6Masked(dpF: Future[Datapath]): Future[Any] = {
        val packet =
            { eth src "fa:16:3e:18:24:4d" dst "33:33:00:00:00:fb" } <<
            { ip6 src "fe80:0:0:0:f816:3eff:fe18:244d" dst "ff02:0:0:0:0:0:0:fb" } <<
            { tcp src 5353 dst 5353 }
        val fmatch = FlowMatches.fromEthernetPacket(packet)
        fmatch.fieldSeen(Field.EthSrc)
        fmatch.fieldSeen(Field.EthDst)
        fmatch.fieldSeen(Field.EtherType)
        fmatch.fieldSeen(Field.NetworkSrc)
        fmatch.fieldSeen(Field.NetworkDst)
        fmatch.fieldSeen(Field.SrcPort)
        fmatch.fieldSeen(Field.DstPort)
        dpF flatMap { con createFlow(new Flow(fmatch), _) }
    }

    def wflowTests(dpF: Future[Datapath]): Seq[(String, Future[Any])] = {
        initTap(dpF)

        con.ovsCon.datapathsSetNotificationHandler(handler)

        if (!Await.result(dpF, 1 second).supportsMegaflow()) {
            return Seq(
                ("Installed OVS kernel module doesn't support megaflow",
                 Future.successful(true)))
        }

        val ethDstVariations = (0 until 10) map { numEth =>
            { eth addr srcMac -> s"10:00:00:00:00:0$numEth" } <<
            { ip4 addr srcIp --> dstIp ttl (-1) } <<
            { tcp ports srcPort ---> dstPort.toShort }
        }

        val ipDstVariations = (0 until 10) map { numIp =>
            { eth addr srcMac -> dstMac } <<
            { ip4 addr srcIp --> s"100.0.1.$numIp" ttl (-1) } <<
            { tcp ports srcPort ---> dstPort.toShort }
        }

        val tcpDstVariations = (0 until 10) map { numPort =>
            { eth addr srcMac -> dstMac } <<
            { ip4 addr srcIp --> dstIp ttl (-1) } <<
            { tcp ports srcPort ---> (dstPort + numPort).toShort }
        }

        def commonMatch: FlowMatch = new FlowMatch().
            addKey(FlowKeys.priority(0)).
            addKey(FlowKeys.inPort(tapPortNum)).
            addKey(FlowKeys.ethernet(srcMacBytes, dstMacBytes)).
            addKey(FlowKeys.etherType(FlowKeyEtherType.Type.ETH_P_IP)).
            addKey(FlowKeys.ipv4(srcIpA.toInt, dstIpA.toInt,
            IpProtocol.TCP.value, 0.toByte, 0.toByte, 0.toByte)).
            addKey(FlowKeys.tcp(srcPort, dstPort)).
            addKey(FlowKeys.tcpFlags(0.toShort))

        var fmatch = commonMatch
        fmatch.getEthSrc
        fmatch.getEthDst
        val exactEther = new Flow(fmatch)

        fmatch = commonMatch
        fmatch.getEthSrc
        val wildcardedEther = new Flow(fmatch)

        fmatch = commonMatch
        fmatch.getNetworkSrcIP
        fmatch.getNetworkDstIP
        val exactIp = new Flow(fmatch)

        fmatch = commonMatch
        fmatch.getNetworkSrcIP
        val wildcardedIp = new Flow(fmatch)

        fmatch = commonMatch
        fmatch.getSrcPort
        fmatch.getDstPort
        val exactPort = new Flow(fmatch)

        fmatch = commonMatch
        fmatch.getSrcPort
        val wildcardedPort = new Flow(fmatch)

        def checkFlow(flow: Flow, packets: Seq[EthBuilder], expected: Int) = {
            val dpFlow = createWFlow(dpF, flow)
            packets foreach { packet => tapWrapper.send(packet.serialize) }
            Thread sleep 500
            delWFlow(dpF, dpFlow)
            val stats = handler.stats()
            if (stats == expected) {
                Future.successful(true)
            } else {
                Future.failed(new Exception(s"Failed: expected $expected packets but " +
                                            s"received $stats. Is megaflow supported?"))
            }
        }

        val fs = Seq[(String, Future[Any])](
            ("can match specific Ethernet addresses",
                checkFlow(exactEther, ethDstVariations, ethDstVariations.length - 1)),
            ("can match a wildcarded Ethernet address",
                checkFlow(wildcardedEther, ethDstVariations, 0)),
            ("can match specific IP addresses",
                checkFlow(exactIp, ipDstVariations, ipDstVariations.length - 1)),
            ("can match a wildcarded IP address",
                checkFlow(wildcardedIp, ipDstVariations, 0)),
            ("can match specific TCP ports",
                checkFlow(exactPort, tcpDstVariations, tcpDstVariations.length - 1)),
            ("can match a wildcarded TCP port",
                checkFlow(wildcardedPort, tcpDstVariations, 0)),
            ("can match IPv6 flow",
                checkIpv6Masked(dpF))
        )
        Future.sequence(fs map (_._2)).onComplete(_ =>  closeTap(dpF))
        fs
    }
}
