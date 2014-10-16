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

import java.util.{ArrayList => JArrayList}
import scala.collection.immutable.IndexedSeq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

import org.midonet.odp._
import org.midonet.odp.flows.FlowKeys.{inPort, priority}
import org.midonet.odp.flows._
import org.midonet.odp.ports._
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.MAC
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder.{tcp, _}
import org.midonet.packets.{IPv4, IPv4Addr, TCP}
import org.midonet.util.BatchCollector

trait TapTrafficInjectBase {

    var tapW: TapWrapper = _
    var tapPort: Future[DpPort] = _    // (will set after plugging the tap)

    def initTap(name: String,
                dpF: Future[Datapath],
                con: OvsConnectionOps): Future[DpPort] = {
        // create the tap and plug in into the datapath
        tapW = new TapWrapper(name)
        val ndPort = new NetDevPort(tapW.getName)
        tapPort = dpF flatMap { con createPort(ndPort, _) }
        tapPort
    }

    def tapPortNum = Await.result(tapPort, 2 seconds).getPortNo
}

trait FlowMatchesTcpHeadersTest extends TapTrafficInjectBase {

    def con: OvsConnectionOps

    private[this] val pktsCount = 10
    private[this] val srcIp = "100.0.1.1"
    private[this] val dstIp = "100.0.1.2"
    private[this] val srcMac = "10:00:00:00:00:01"
    private[this] val dstMac = "10:00:00:00:00:02"
    private[this] val srcPort = 10000
    private[this] val dstPort = 10000

    private[this] val handler = new Handler()

    private[this] class Handler extends BatchCollector[Packet] {

        val counter = new java.util.concurrent.atomic.AtomicInteger(0)

        def submit(p: Packet) {
            val ethPkt = p.getEthernet
            if (ethPkt.getEtherType() == IPv4.ETHERTYPE) {
                val ipPkt = ethPkt.getPayload.asInstanceOf[IPv4]
                if (ipPkt.getSourceIPAddress == IPv4Addr(srcIp) &&
                    ipPkt.getDestinationIPAddress == IPv4Addr(dstIp)) {
                    val protocol = ipPkt.getProtocol
                    val payload = ipPkt.getPayload
                    if (protocol == TCP.PROTOCOL_NUMBER) {
                        val tcpPkt = payload.asInstanceOf[TCP]
                        if (tcpPkt.getSourcePort == srcPort &&
                            tcpPkt.getDestinationPort == dstPort) {
                            counter.getAndIncrement()
                        }
                    }
                }
            }
            //println(s"WARNING: unexpected packet received: $p")
        }

        def endBatch() {}

        def stats() = counter.getAndSet(0)
    }

    def tcpPacketWithFlags(flags: JArrayList[TCP.Flag]): EthBuilder = {
        eth addr srcMac -> dstMac                      } << {
        ip4 addr srcIp --> dstIp ttl (-1)              } << {
        tcp ports srcPort.toShort ---> dstPort.toShort flags (TCP.Flag.allOf(flags))
    }

    /**
     * Install a flow for a TCP flag, send some traffic and verify the match
     * has been used for dropping packets.
     */
    def testMatchFlags(dpF: Future[Datapath],
                       sendFlags: JArrayList[TCP.Flag],
                       matchFlags: JArrayList[TCP.Flag]): Future[Boolean] = {

        // create a match for "matchFlag"
        val packet = tcpPacketWithFlags(matchFlags)
        val flowMatch = FlowMatches.fromEthernetPacket(packet)

        // add some extra keys "fromEthernetPacket" does not set and we need
        flowMatch.addKey(priority(0))
        flowMatch.addKey(inPort(tapPortNum))

        def createFlow(f: Flow) = dpF flatMap { con createFlow(f, _) }
        def delFlow(f: Flow) = dpF flatMap { con delFlow(f, _) }

        // create and install the flow for dropping packets with "matchFlags"
        val installedFlow = createFlow(new Flow(flowMatch))
        Await.result(installedFlow, 1 second)

        // generate and send "pktsCount" TCP packets (with some flags)
        // from "srcIp":"srcPort"/"srcMac" to "dstIp":"dstPort"/"dstMac"
        val data: Array[Byte] = tcpPacketWithFlags(sendFlags).serialize()
        (0 until pktsCount) map { _ => tapW.send(data) }

        Thread sleep 250           // give some time to the packets to arrive...
        installedFlow flatMap { delFlow(_) }         // before removing the flow

        val expected = if (sendFlags == matchFlags) {
            0 // all packets should have been dropped
        } else {
            pktsCount // no packets dropped
        }

        Future.successful(handler.stats == expected)
    }

    def tcpFlagsMatchesTests(dpF: Future[Datapath]) = {
        // create the tap and plug in into the datapath
        initTap("tap1", dpF, con)

        // install a common handler
        dpF flatMap { con.setHandler(_, handler) }

        val pshFlags = new JArrayList[TCP.Flag]()
        val ackFlags = new JArrayList[TCP.Flag]()
        val rstFlags = new JArrayList[TCP.Flag]()
        pshFlags.add(TCP.Flag.Psh)
        ackFlags.add(TCP.Flag.Ack)
        rstFlags.add(TCP.Flag.Rst)

        Seq[(String, Future[Any])](
            ("can match PSH TCP flag", testMatchFlags(dpF, pshFlags, pshFlags)),
            ("can match ACK TCP flag", testMatchFlags(dpF, ackFlags, ackFlags)),
            ("receives unmatched TCP flags", testMatchFlags(dpF, rstFlags, pshFlags))
        )
    }
}

trait WildcardFlowTest extends TapTrafficInjectBase {

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
    private[this] val srcPort = 5000.toShort
    private[this] val dstPort = 5000.toShort

    private[this] val handler = new Handler()

    private[this] class Handler extends BatchCollector[Packet] {

        val counter = new java.util.concurrent.atomic.AtomicInteger(0)
        def submit(p: Packet) {
            counter.getAndIncrement()
        }

        def endBatch() {}
        def stats() = counter.getAndSet(0)
    }

    def wflowTests(dpF: Future[Datapath]) = {
        initTap("tap2", dpF, con)

        // install a common handler
        dpF flatMap { con.setHandler(_, handler) }

        def createWFlow(f: Flow): Future[Flow] = dpF flatMap { con createFlow(f, _) }
        def delWFlow(f: Flow): Future[Flow] = dpF flatMap { con delFlow(f, _) }
        def checkMegaflows: Future[Boolean] = dpF flatMap { con supportsWildcards(_) }

        val supportsMegaflows = Await.result(checkMegaflows, 1 second)

        // a general flow with mask, just for testing we can insert/remove it...
        val flowMatch = new FlowMatch().
            addKey(FlowKeys.priority(0)).
            addKey(FlowKeys.inPort(0)).
            addKey(FlowKeys.ethernet(srcMacBytes, dstMacBytes)).
            addKey(FlowKeys.etherType(FlowKeyEtherType.Type.ETH_P_IP)).
            addKey(FlowKeys.ipv4(srcIpA, dstIpA, IpProtocol.TCP)).
            addKey(FlowKeys.tcp(srcPort, dstPort)).
            addKey(FlowKeys.tcpFlags(0.toShort))

        val flowMask = new FlowMask().
            addKey(FlowKeys.priority(FlowMask.PRIO_EXACT)).
            addKey(FlowKeys.inPort(FlowMask.INPORT_EXACT)).
            addKey(FlowKeys.ethernet(FlowMask.ETHER_EXACT, FlowMask.ETHER_EXACT)).
            addKey(FlowKeys.etherType(FlowMask.ETHERTYPE_EXACT)).
            addKey(FlowKeys.ipv4(FlowMask.IP_EXACT, FlowMask.IP_EXACT,
                                 FlowMask.BYTE_EXACT, FlowMask.BYTE_EXACT,
                                 FlowMask.BYTE_EXACT, FlowMask.BYTE_ANY)).
            addKey(FlowKeys.tcp(FlowMask.TCP_EXACT, FlowMask.TCP_EXACT)).
            addKey(FlowKeys.tcpFlags(FlowMask.TCPFLAGS_EXACT))

        val testFlow = new Flow(flowMatch, flowMask)

        val makeWF = createWFlow(testFlow)

        val enumWF1 = for {
            dp <- dpF
            fs <- con enumFlows dp
        } yield {
            fs
        }

        val delWF = makeWF flatMap delWFlow
        val flushWF = delWF flatMap { case _ => dpF flatMap { con flushFlows _ } }

        val enumWF2 = for {
            dp <- dpF
            f <- flushWF
            fs <- con enumFlows dp
        } yield {
            if (fs.nonEmpty) {
                throw new Exception("flows remaining")
            }
            true
        }

        // first packets variation: increasing ethernet destinations
        val increasingEthPackets =
            (0 until 10) map { numEth =>
                {
                    eth addr srcMac -> s"10:00:00:00:00:0$numEth" } << {
                    ip4 addr srcIp --> dstIp ttl (-1)             } << {
                    tcp ports srcPort.toShort ---> dstPort.toShort
                }
            }

        // second packets variation: increasing IP destinations
        val increasingIpsPackets =
            (0 until 10) map { numIp =>
                {
                    eth addr srcMac -> dstMac                     } << {
                    ip4 addr srcIp --> s"100.0.1.$numIp" ttl (-1) } << {
                    tcp ports srcPort.toShort ---> dstPort.toShort
                }
            }

        // third packets variation: increasing ports destinations
        val increasingPortsPackets =
            (dstPort.toInt until dstPort.toInt + 10) map { numPort =>
                {
                    eth addr srcMac -> dstMac                     } << {
                    ip4 addr srcIp --> dstIp ttl (-1)             } << {
                    tcp ports srcPort.toShort ---> numPort.toShort
                }
            }

        val allPackets: IndexedSeq[EthBuilder] = increasingEthPackets ++
            increasingIpsPackets ++ increasingPortsPackets

        def commonMatch: FlowMatch = new FlowMatch().
            addKey(FlowKeys.priority(0)).
            addKey(FlowKeys.inPort(tapPortNum)).
            addKey(FlowKeys.ethernet(srcMacBytes, dstMacBytes)).
            addKey(FlowKeys.etherType(FlowKeyEtherType.Type.ETH_P_IP)).
            addKey(FlowKeys.ipv4(srcIpA.toInt, dstIpA.toInt,
            IpProtocol.TCP.value, 0.toByte, (-1).toByte, 0.toByte)).
            addKey(FlowKeys.tcp(srcPort, dstPort)).
            addKey(FlowKeys.tcpFlags(0.toShort))

        // match for ethernet adresses, wildcarding IP addresses and TCP ports
        // we have two options: require exact matches for IP and TCP protocols,
        // or we can skip the masks at those levels (otherwise the flow will be
        // invalid)
        val exactEtherWildcard = new FlowMask().
            addKey(FlowKeys.priority(FlowMask.PRIO_EXACT)).
            addKey(FlowKeys.inPort(FlowMask.INPORT_EXACT)).
            addKey(FlowKeys.ethernet(FlowMask.ETHER_EXACT, FlowMask.ETHER_EXACT)).
            addKey(FlowKeys.etherType(FlowMask.ETHERTYPE_EXACT)).
            addKey(FlowKeys.ipv4(FlowMask.IP_ANY, FlowMask.IP_ANY,
                                 FlowMask.BYTE_EXACT, FlowMask.BYTE_ANY,
                                 FlowMask.BYTE_ANY, FlowMask.BYTE_ANY)).
            addKey(FlowKeys.tcp(FlowMask.TCP_ANY, FlowMask.TCP_ANY)).
            addKey(FlowKeys.tcpFlags(FlowMask.TCPFLAGS_ANY))

        // exact match for IP adresses, wildcarding ethernet addresses and TCP ports
        // must require at least exact match for ethernet protocol equal to IP
        val exactIpWildcard = new FlowMask().
            addKey(FlowKeys.priority(FlowMask.PRIO_ANY)).
            addKey(FlowKeys.inPort(FlowMask.INPORT_ANY)).
            addKey(FlowKeys.ethernet(FlowMask.ETHER_ANY, FlowMask.ETHER_ANY)).
            addKey(FlowKeys.etherType(FlowMask.ETHERTYPE_EXACT)).
            addKey(FlowKeys.ipv4(FlowMask.IP_EXACT, FlowMask.IP_EXACT,
                                 FlowMask.BYTE_EXACT, FlowMask.BYTE_EXACT,
                                 FlowMask.BYTE_EXACT, FlowMask.BYTE_EXACT)).
            addKey(FlowKeys.tcp(FlowMask.TCP_ANY, FlowMask.TCP_ANY)).
            addKey(FlowKeys.tcpFlags(FlowMask.TCPFLAGS_ANY))

        // exact match for TCP ports, wildcarding IP addresses and ethernet addresses
        // must require exact match for ethernet protocol equal to IP,
        // exact match for IP protocol equal to TCP and, if we require exact
        // match for TCP ports, we can require exact match for TCP flags
        val exactTcpPortsWildcard = new FlowMask().
            addKey(FlowKeys.priority(FlowMask.PRIO_EXACT)).
            addKey(FlowKeys.inPort(FlowMask.INPORT_EXACT)).
            addKey(FlowKeys.ethernet(FlowMask.ETHER_ANY, FlowMask.ETHER_ANY)).
            addKey(FlowKeys.etherType(FlowMask.ETHERTYPE_EXACT)).
            addKey(FlowKeys.ipv4(FlowMask.IP_ANY, FlowMask.IP_ANY,
                                 FlowMask.BYTE_EXACT, FlowMask.BYTE_ANY,
                                 FlowMask.BYTE_ANY, FlowMask.BYTE_ANY)).
            addKey(FlowKeys.tcp(FlowMask.TCP_ANY, FlowMask.TCP_EXACT)).
            addKey(FlowKeys.tcpFlags(FlowMask.TCPFLAGS_EXACT))

        def checkFlowMask(msk: FlowMask, expected: Int) = {
            val r = createWFlow(new Flow(commonMatch, msk))
            Await.result(r, 1 second)
            allPackets map { packet => tapW.send(packet.serialize) }
            Thread sleep 250         // give some time to the packets to arrive
            r flatMap { delWFlow(_) }           // before removing the flow
            val passed = handler.stats == expected || !supportsMegaflows
            if (passed) {
                Future.successful(true)
            } else {
                Future.failed(new AssertionError(s"ERROR: expected $expected"))
            }
        }

        val exactEthWildRest = checkFlowMask(exactEtherWildcard,
            increasingEthPackets.length - 1)
        val exactIpWildRest = checkFlowMask(exactIpWildcard,
            increasingIpsPackets.length - 1)
        val exactTcpWildRest = checkFlowMask(exactTcpPortsWildcard,
            increasingPortsPackets.length - 1)

        Seq[(String, Future[Any])](
            ("can create a wildcard flow", makeWF),
            ("can list wildcard flows", enumWF1),
            ("can delete a wildcard flow", delWF),
            ("can flush wildcard flows", enumWF2),
            ("can match wildcarded Ethernet addresses", exactEthWildRest),
            ("can match wildcarded IP addresses", exactIpWildRest),
            ("can match wildcarded TCP ports", exactTcpWildRest)
        )
    }
}
