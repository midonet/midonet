/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.test

import java.util.{ArrayList => JArrayList}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import org.midonet.odp._
import org.midonet.odp.flows.FlowKeys.{inPort, priority}
import org.midonet.odp.flows._
import org.midonet.odp.ports._
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder.{tcp, _}
import org.midonet.packets.{IPv4, IPv4Addr, TCP}
import org.midonet.util.BatchCollector

trait FlowMatchesTcpHeadersTest {

    def con: OvsConnectionOps

    val pktsCount = 10
    val srcIp = "100.0.1.1"
    val dstIp = "100.0.1.2"
    val srcMac = "10:00:00:00:00:01"
    val dstMac = "10:00:00:00:00:02"
    val srcPort = 10000.toShort
    val dstPort = 10000.toShort

    var tap1: TapWrapper = _
    var tapPort: Future[DpPort] = _    // (will set after plugging the tap)

    class Handler extends BatchCollector[Packet] {

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

    val handler = new Handler()

    def tcpPacketWithFlags(flags: JArrayList[TCP.Flag]): EthBuilder = {
        eth addr srcMac -> dstMac
    } << {
        ip4 addr srcIp --> dstIp ttl (-1)
    } << {
        tcp ports srcPort ---> dstPort flags (TCP.Flag.allOf(flags))
    }

    /**
     * Generate and send "pktsCount" TCP packets (with some flags)
     * from "srcIp":"srcPort"/"srcMac") to "dstIp":"dstPort"/"dstMac")
     * @param flags the TCP flags (see http://www.perihel.at/sec/mz/mzguide.html#tcp)
     */
    def sendTcpPackets(flags: JArrayList[TCP.Flag]) = {
        val eth: EthBuilder = tcpPacketWithFlags(flags)
        val data: Array[Byte] = eth.serialize()
        for (a <- 0 until pktsCount) {
            tap1.send(data)
            Thread sleep 50
        }
    }

    def matchForTCPFlags(flags: JArrayList[TCP.Flag]): FlowMatch = {
        val packet = tcpPacketWithFlags(flags)
        val flowMatch = FlowMatches.fromEthernetPacket(packet)

        // add some extra keys we need
        flowMatch.addKey(priority(0))
        flowMatch.addKey(inPort(Await.result(tapPort, 2 seconds).getPortNo))
    }

    /**
     * Install a flow for a TCP flag, send some traffic and verify the match
     * has been used for dropping packets.
     */
    def testMatchFlags(dpF: Future[Datapath],
                       sendFlags: JArrayList[TCP.Flag],
                       matchFlags: JArrayList[TCP.Flag]): Future[Boolean] = {

        // create a match for "matchFlag"
        val flowMatch = matchForTCPFlags(matchFlags)

        // create and install the flow for dropping packets with "matchFlags"
        val flow = new Flow(flowMatch, new JArrayList[FlowAction]())
        dpF flatMap { con createFlow(flow, _) }

        // generate the TCP traffic with the flag
        sendTcpPackets(sendFlags)

        val expected = if (sendFlags == matchFlags) {
            0 // all packets should have been dropped
        } else {
            pktsCount // no packets dropped
        }

        // remove the flow
        dpF flatMap { con delFlow(flow, _) }

        val received = handler.stats
        Future.successful(received == expected)
    }

    def tcpFlagsMatchesTests(dpF: Future[Datapath]) = {
        // create the tap and plug in into the datapath
        tap1 = new TapWrapper("tap1")
        val ndPort = new NetDevPort(tap1.getName)
        tapPort = dpF flatMap { con createPort(ndPort, _) }

        // install a common handler
        dpF flatMap {
            con.setHandler(_, handler)
        }

        val synFlags = new JArrayList[TCP.Flag]()
        val ackFlags = new JArrayList[TCP.Flag]()
        synFlags.add(TCP.Flag.Syn)
        ackFlags.add(TCP.Flag.Ack)

        Seq[(String, Future[Any])](
            ("can match SYN TCP flag", testMatchFlags(dpF, synFlags, synFlags)),
            ("can match ACK TCP flag", testMatchFlags(dpF, ackFlags, ackFlags)),
            ("receives unmatched TCP flags", testMatchFlags(dpF, ackFlags, synFlags))
        )
    }
}
