/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import flows.WildcardMatch
import scala.collection.mutable
import scala.compat.Platform
import annotation.tailrec
import java.util.UUID

import akka.dispatch.Await
import akka.testkit.TestProbe
import akka.util.duration._
import akka.util.Timeout

import guice.actors.OutgoingMessage
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.FlowController._
import com.midokura.midolman.SimulationController.EmitGeneratedPacket
import com.midokura.midolman.layer3.Route.{NextHop, NO_GATEWAY}
import simulation.{PacketContext, LoadBalancer, ArpTableImpl}
import com.midokura.midolman.state.ArpCacheEntry
import com.midokura.midolman.state.ReplicatedMap.Watcher
import com.midokura.midolman.topology.VirtualToPhysicalMapper.HostRequest
import com.midokura.midonet.cluster.data.{Router => ClusterRouter, Ports}
import com.midokura.midonet.cluster.data.ports.{LogicalRouterPort, MaterializedRouterPort}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.packets._
import com.midokura.sdn.dp.flows.{FlowActionSetKey, FlowActionOutput,
                                  FlowKeyEthernet, FlowKeyIPv4}
import topology.LocalPortActive
import util.RouterHelper


@RunWith(classOf[JUnitRunner])
class RouterSimulationTestCase extends MidolmanTestCase with
        VirtualConfigurationBuilders with RouterHelper {
    private final val log =
         LoggerFactory.getLogger(classOf[RouterSimulationTestCase])
    // these should reliably give us two retries, no more, no less.
    private final val ARP_RETRY_SECS = 2
    private final val ARP_TIMEOUT_SECS = 3
    private final val ARP_STALE_SECS = 5
    private final val ARP_EXPIRATION_SECS = 10

    private var flowEventsProbe: TestProbe = null
    private var clusterRouter: ClusterRouter = null
    private val uplinkGatewayAddr = "180.0.1.1"
    private val uplinkNwAddr = "180.0.1.0"
    private val uplinkNwLen = 24
    private val uplinkPortAddr = "180.0.1.2"
    private val uplinkMacAddr = MAC.fromString("02:0a:08:06:04:02")
    private var uplinkPort: MaterializedRouterPort = null
    private var upLinkRoute: UUID = null
    private var host: Host = null

    private val portConfigs = mutable.Map[Int, MaterializedRouterPort]()
    private val portNumToId = mutable.Map[Int, UUID]()
    private val portNumToMac = mutable.Map[Int, MAC]()
    private val portNumToName = mutable.Map[Int, String]()
    private val portNumToSegmentAddr = mutable.Map[Int, Int]()

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        config.setProperty("arptable.arp_retry_interval_seconds", ARP_RETRY_SECS)
        config.setProperty("arptable.arp_timeout_seconds", ARP_TIMEOUT_SECS)
        config.setProperty("arptable.arp_stale_seconds", ARP_STALE_SECS)
        config.setProperty("arptable.arp_expiration_seconds", ARP_EXPIRATION_SECS)
        super.fillConfig(config)
    }

    override def beforeTest() {
        flowEventsProbe = newProbe()
        val portEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(portEventsProbe.ref, classOf[LocalPortActive])

        host = newHost("myself", hostId())
        host should not be null
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        // Create one port that works as an uplink for the router.
        uplinkPort = newExteriorRouterPort(clusterRouter, uplinkMacAddr,
            uplinkPortAddr, uplinkNwAddr, uplinkNwLen)
        uplinkPort should not be null
        materializePort(uplinkPort, host, "uplinkPort")
        var portEvent = requestOfType[LocalPortActive](portEventsProbe)
        portEvent.active should be(true)
        portEvent.portID should be(uplinkPort.getId)

        upLinkRoute = newRoute(clusterRouter, "0.0.0.0", 0, "0.0.0.0", 0,
            NextHop.PORT, uplinkPort.getId, uplinkGatewayAddr, 1)
        upLinkRoute should not be null

        for (i <- 0 to 2) {
            // Nw address is 10.0.<i>.0/24
            val nwAddr = 0x0a000000 + (i << 8)
            for (j <- 1 to 3) {
                val macAddr = MAC.fromString("0a:0b:0c:0d:0" + i + ":0" + j)
                val portNum = i * 10 + j
                val portName = "port" + portNum
                // The port will route to 10.0.<i>.<j*4>/30
                val segmentAddr = new IntIPv4(nwAddr + j*4)
                val portAddr = new IntIPv4(nwAddr + j*4 + 1)

                val port = newExteriorRouterPort(clusterRouter, macAddr,
                    portAddr.toString, segmentAddr.toString, 30)
                port should not be null

                materializePort(port, host, portName)
                portEvent = requestOfType[LocalPortActive](portEventsProbe)
                portEvent.active should be(true)
                portEvent.portID should be(port.getId)

                // store port info for later use
                portConfigs.put(portNum, port)
                portNumToId.put(portNum, port.getId)
                portNumToMac.put(portNum, macAddr)
                portNumToName.put(portNum, portName)
                portNumToSegmentAddr.put(portNum, segmentAddr.addressAsInt)

                // Default route to port based on destination only.  Weight 2.
                newRoute(clusterRouter, "10.0.0.0", 16, segmentAddr.toString, 30,
                    NextHop.PORT, port.getId, new IntIPv4(NO_GATEWAY).toString,
                    2)
                // Anything from 10.0.0.0/16 is allowed through.  Weight 1.
                newRoute(clusterRouter, "10.0.0.0", 16, segmentAddr.toString, 30,
                    NextHop.PORT, port.getId, new IntIPv4(NO_GATEWAY).toString,
                    1)
                // Anything from 11.0.0.0/24 is silently dropped.  Weight 1.
                newRoute(clusterRouter, "11.0.0.0", 24, segmentAddr.toString, 30,
                    NextHop.BLACKHOLE, null, null, 1)
                // Anything from 12.0.0.0/24 is rejected (ICMP filter
                // prohibited).
                newRoute(clusterRouter, "12.0.0.0", 24, segmentAddr.toString, 30,
                    NextHop.REJECT, null, null, 1)
            }
        }

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        drainProbes()
    }

    private def expectFlowActionSetKey[T](action: AnyRef)(implicit m: Manifest[T]) : T = {
        as[T](as[FlowActionSetKey](action).getFlowKey)
    }

    private def makeAddressInSegment(portNum: Int) : IntIPv4 =
        new IntIPv4(portNumToSegmentAddr(portNum) + 2)

    private def myAddressOnPort(portNum: Int): IntIPv4 =
        new IntIPv4(portNumToSegmentAddr(portNum) + 1)

    def testBalancesRoutes() {
        val routeDst = "21.31.41.51"
        val gateways = List("180.0.1.40", "180.0.1.41", "180.0.1.42")
        gateways foreach { gw =>
            newRoute(clusterRouter, "0.0.0.0", 0, routeDst, 32,
                     NextHop.PORT, uplinkPort.getId, gw, 1)
        }
        val (router, port) = fetchRouterAndPort("uplinkPort")
        val lb = new LoadBalancer(router.rTable)

        val wmatch = new WildcardMatch().
            setNetworkSource(IntIPv4.fromString(uplinkPortAddr)).
            setNetworkDestination(IntIPv4.fromString(routeDst))

        @tailrec
        def matchAllResults(resultPool: List[String]) {
            val rt = lb.lookup(wmatch)
            rt should not be null
            val gw = rt.getNextHopGateway
            gw should not be null
            resultPool should contain (gw)
            if (resultPool.size > 1)
                matchAllResults(resultPool - gw)
        }

        matchAllResults(gateways)
        matchAllResults(gateways)
    }

    def testDropsIPv6() {
        val onPort = 12
        val eth = (new Ethernet()).setEtherType(IPv6_ETHERTYPE).
            setDestinationMACAddress(portNumToMac(onPort)).
            setSourceMACAddress(MAC.fromString("01:02:03:04:05:06")).
            setPad(true)
        triggerPacketIn(portNumToName(onPort), eth)
        expectPacketOnPort(portNumToId(onPort))
        val flow = expectFlowAddedMessage()
        flow.getMatch.getEthernetDestination should equal(portNumToMac(onPort))
        flow.getMatch.getEthernetSource should equal(MAC.fromString("01:02:03:04:05:06"))
        flow.getMatch.getEtherType should equal(IPv6_ETHERTYPE)
        // A flow with no actions drops matching packets
        flow.getActions.size() should equal(0)
    }

    def testForwardToUplink() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches the uplink
        // port (e.g. anything outside 10.  0.0.0/16).
        val gwMac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        val onPort = 23
        // use a large TTL to ensure that we don't get unsigned/signed issues
        val ttl: Byte = (158).toByte
        val eth = Packets.udp(
            MAC.fromString("01:02:03:04:05:06"), portNumToMac(onPort),
            makeAddressInSegment(onPort), IntIPv4.fromString("45.44.33.22"),
            10, 11, "My UDP packet".getBytes)
        eth.getPayload.asInstanceOf[IPv4].setTtl(ttl)

        feedArpCache("uplinkPort",
            IntIPv4.fromString(uplinkGatewayAddr).addressAsInt,
            gwMac,
            IntIPv4.fromString(uplinkPortAddr).addressAsInt,
            uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()

        triggerPacketIn(portNumToName(onPort), eth)
        expectPacketOnPort(portNumToId(onPort))
        val flow = expectFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        flow.getActions.size() should equal(3)
        val ethKey =
            expectFlowActionSetKey[FlowKeyEthernet](flow.getActions.get(0))
        ethKey.getDst should be === gwMac.getAddress
        ethKey.getSrc should be === uplinkMacAddr.getAddress
        val ipKey = expectFlowActionSetKey[FlowKeyIPv4](flow.getActions.get(1))
        ipKey.getTtl should be === (ttl-1)
        flow.getActions.get(2).getClass should equal(classOf[FlowActionOutput])
    }

    def testBlackholeRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 11.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
        val toPort = 21
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = IntIPv4.fromString("11.0.0.31")
        val toIp = makeAddressInSegment(toPort)
        val fromUdp: Short = 10
        val toUdp: Short = 11
        val eth = Packets.udp(fromMac, uplinkMacAddr, fromIp, toIp,
                              fromUdp, toUdp, "My UDP packet".getBytes)
        triggerPacketIn("uplinkPort", eth)
        expectPacketOnPort(uplinkPort.getId)

        val flow = expectFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        // A flow with no actions drops matching packets
        flow.getActions.size() should equal(0)
        simProbe().expectNoMsg(Timeout(2 seconds).duration)
    }

    def testRejectRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 12.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
        val toPort = 21
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = IntIPv4.fromString("12.0.0.31")
        val toIp = makeAddressInSegment(toPort)
        val fromUdp: Short = 10
        val toUdp: Short = 11
        val eth = Packets.udp(fromMac, uplinkMacAddr, fromIp, toIp,
            fromUdp, toUdp, "My UDP packet".getBytes)
        triggerPacketIn("uplinkPort", eth)
        expectPacketOnPort(uplinkPort.getId)

        val flow = expectFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        // A flow with no actions drops matching packets
        flow.getActions.size() should equal(0)

        expectEmitIcmp(uplinkMacAddr, IntIPv4.fromString(uplinkPortAddr),
                        fromMac, fromIp, ICMP.TYPE_UNREACH,
                        ICMP.UNREACH_CODE.UNREACH_FILTER_PROHIB.toChar)
    }

    def testForwardBetweenDownlinks() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches port 12
        // (i.e. inside 10.0.1.8/30).
        val inPort = 23
        val outPort = 12
        val inFromMac = MAC.fromString("23:23:23:ff:ff:ff")
        val outToMac = MAC.fromString("12:12:12:aa:aa:aa")
        val inToMac = portNumToMac(23)
        val outFromMac = portNumToMac(12)
        val fromIp = makeAddressInSegment(inPort)
        val toIp = makeAddressInSegment(outPort)
        val fromUdp: Short = 10
        val toUdp: Short = 11
        val eth = Packets.udp(inFromMac, inToMac, fromIp, toIp,
            fromUdp, toUdp, "My UDP packet".getBytes)

        feedArpCache(portNumToName(outPort), toIp.addressAsInt, outToMac,
                     myAddressOnPort(outPort).addressAsInt, outFromMac)
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()
        triggerPacketIn(portNumToName(inPort), eth)
        expectPacketOnPort(portNumToId(inPort))

        val flow = expectFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        flow.getActions.size() should be === 3
        val ethKey =
            expectFlowActionSetKey[FlowKeyEthernet](flow.getActions.get(0))
        ethKey.getDst should be === outToMac.getAddress
        ethKey.getSrc should be === outFromMac.getAddress
        flow.getActions.get(2).getClass should equal(classOf[FlowActionOutput])
    }

    def testNoRoute() {
        clusterDataClient().routesDelete(upLinkRoute)

        val onPort = 23
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val eth = Packets.udp(
            fromMac, portNumToMac(onPort),
            makeAddressInSegment(onPort), IntIPv4.fromString("45.44.33.22"),
            10, 11, "My UDP packet".getBytes)
        triggerPacketIn(portNumToName(onPort), eth)
        expectPacketOnPort(portNumToId(onPort))

        val flow = expectFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        flow.getActions.size() should equal(0)
        expectEmitIcmp(portNumToMac(onPort), myAddressOnPort(onPort),
            fromMac, makeAddressInSegment(onPort), ICMP.TYPE_UNREACH,
            ICMP.UNREACH_CODE.UNREACH_NET.toChar)
    }

    def testArpRequestFulfilledLocally() {
        val (router, port) = fetchRouterAndPort("uplinkPort")
        val mac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        val expiry = Platform.currentTime + 1000
        val arpPromise = router.arpTable.get(
            IntIPv4.fromString(uplinkGatewayAddr), port, expiry)(
            actors().dispatcher, actors(), null)
        requestOfType[EmitGeneratedPacket](simProbe())

        feedArpCache("uplinkPort",
            IPv4.toIPv4Address(uplinkGatewayAddr), mac,
            IntIPv4.fromString(uplinkPortAddr).addressAsInt,
            uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()
        val arpResult = Await.result(arpPromise, Timeout(3 seconds).duration)
        arpResult should be === mac
    }

    def testArpRequestFulfilledRemotely() {
        val (router, port) = fetchRouterAndPort("uplinkPort")

        val ip = IntIPv4.fromString(uplinkGatewayAddr)
        val mac = MAC.fromString("fe:fe:fe:da:da:da")

        val arpTable = router.arpTable.asInstanceOf[ArpTableImpl]
        val arpCache = arpTable.arpCache.asInstanceOf[Watcher[IntIPv4,
                                                              ArpCacheEntry]]
        val macFuture = router.arpTable.get(ip, port,
            Platform.currentTime + 30*1000)(actors().dispatcher, actors(), null)
        requestOfType[EmitGeneratedPacket](simProbe())

        val now = Platform.currentTime
        arpCache.processChange(ip, null,
            new ArpCacheEntry(mac, now + 60*1000, now + 30*1000, 0))
        val arpResult = Await.result(macFuture, Timeout(3 seconds).duration)
        arpResult should be === mac
    }

    def testArpRequestGeneration() {
        val (router, port) = fetchRouterAndPort("uplinkPort")
        val expiry = Platform.currentTime + 1000
        val fromIp = IntIPv4.fromString(uplinkPortAddr)
        val toIp = IntIPv4.fromString(uplinkGatewayAddr)

        val arpPromise = router.arpTable.get(toIp, port, expiry)(
            actors().dispatcher, actors(), null)
        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, fromIp, toIp)
        try {
            Await.result(arpPromise, Timeout(100 milliseconds).duration)
        } catch {
            case e: java.util.concurrent.TimeoutException =>
        }
    }

    def testUnicastArpReceivedRequestProcessing() {
        arpReceivedRequestProcessing(isUnicast = true)
    }

    def testArpReceivedRequestProcessing() {
        arpReceivedRequestProcessing(isUnicast = false)
    }

    private def arpReceivedRequestProcessing(isUnicast: Boolean) {
        val (router, port) = fetchRouterAndPort("uplinkPort")
        val hisIp = IntIPv4.fromString(uplinkGatewayAddr)
        val myIp = IntIPv4.fromString(uplinkPortAddr)
        val hisMac = MAC.fromString("ab:cd:ef:ab:cd:ef")
        val myMac = uplinkMacAddr
        val bcastMac = MAC.fromString("ff:ff:ff:ff:ff:ff")
        val eth = new Ethernet().setEtherType(ARP.ETHERTYPE).
            setSourceMACAddress(hisMac).
            setDestinationMACAddress(if (isUnicast) myMac else bcastMac)
        eth.setPayload(new ARP().
            setHardwareType(ARP.HW_TYPE_ETHERNET).
            setProtocolType(ARP.PROTO_TYPE_IP).
            setHardwareAddressLength(6:Byte).
            setProtocolAddressLength(4:Byte).
            setOpCode(ARP.OP_REQUEST).
            setSenderHardwareAddress(hisMac).
            setSenderProtocolAddress(IPv4.toIPv4AddressBytes(hisIp.addressAsInt())).
            setTargetProtocolAddress(IPv4.toIPv4AddressBytes(myIp.addressAsInt())).
            setTargetHardwareAddress(if (isUnicast) myMac else bcastMac))
        triggerPacketIn("uplinkPort", eth)
        requestOfType[PacketIn](simProbe())
        val msg = requestOfType[EmitGeneratedPacket](simProbe())
        msg.egressPort should be === uplinkPort.getId
        msg.ethPkt.getEtherType should be === ARP.ETHERTYPE
        msg.ethPkt.getSourceMACAddress should be === myMac
        msg.ethPkt.getDestinationMACAddress should be === hisMac
        msg.ethPkt.getPayload.getClass should be === classOf[ARP]
        val arp = msg.ethPkt.getPayload.asInstanceOf[ARP]
        arp.getOpCode should be === ARP.OP_REPLY
        new IntIPv4(arp.getSenderProtocolAddress) should be === myIp
        arp.getSenderHardwareAddress should be === myMac
        new IntIPv4(arp.getTargetProtocolAddress) should be === hisIp
        arp.getTargetHardwareAddress should be === hisMac

        // the arp cache should be updated without generating a request
        val expiry = Platform.currentTime + 1000
        val arpPromise = router.arpTable.get(hisIp, port, expiry)(
            actors().dispatcher, actors(), null)
        val t = Timeout(1 second)
        val arpResult = Await.result(arpPromise, t.duration)
        arpResult should be === hisMac
        simProbe().expectNoMsg(Timeout(2 seconds).duration)
    }

    def testIcmpEchoNearPort() {
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = "50.25.50.25"

        feedArpCache("uplinkPort",
            IntIPv4.fromString(uplinkGatewayAddr).addressAsInt,
            fromMac,
            IntIPv4.fromString(uplinkPortAddr).addressAsInt,
            uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()

        val echo = new ICMP()
        echo.setEchoRequest(16, 32, "My ICMP".getBytes)
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(fromMac).
            setDestinationMACAddress(uplinkMacAddr).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(fromIp).
                    setDestinationAddress(uplinkPortAddr).
                    setProtocol(ICMP.PROTOCOL_NUMBER).
                    setPayload(echo))
        triggerPacketIn("uplinkPort", eth)
        expectPacketOnPort(uplinkPort.getId)
        requestOfType[DiscardPacket](flowProbe())
        expectEmitIcmp(uplinkMacAddr, IntIPv4.fromString(uplinkPortAddr),
            fromMac, IntIPv4.fromString(fromIp), ICMP.TYPE_ECHO_REPLY,
            ICMP.CODE_NONE)
    }

    def testIcmpEchoFarPort() {
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = "10.0.50.25"
        val farPort = 21

        feedArpCache("uplinkPort",
            IntIPv4.fromString(uplinkGatewayAddr).addressAsInt,
            fromMac,
            IntIPv4.fromString(uplinkPortAddr).addressAsInt,
            uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()

        val echo = new ICMP()
        echo.setEchoRequest(16, 32, "My ICMP".getBytes)
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(fromMac).
            setDestinationMACAddress(uplinkMacAddr).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(fromIp).
            setDestinationAddress(myAddressOnPort(farPort).addressAsInt()).
            setProtocol(ICMP.PROTOCOL_NUMBER).
            setPayload(echo))
        triggerPacketIn("uplinkPort", eth)
        expectPacketOnPort(uplinkPort.getId)
        requestOfType[DiscardPacket](flowProbe())
        expectEmitIcmp(uplinkMacAddr, myAddressOnPort(farPort),
            fromMac, IntIPv4.fromString(fromIp), ICMP.TYPE_ECHO_REPLY,
            ICMP.CODE_NONE)
    }

    def testNextHopNonLocalAddress() {
        val badGwAddr = "179.0.0.1"
        clusterDataClient().routesDelete(upLinkRoute)
        newRoute(clusterRouter, "0.0.0.0", 0, "0.0.0.0", 0, NextHop.PORT,
            uplinkPort.getId, badGwAddr, 1)
        val fromMac = MAC.fromString("01:02:03:03:02:01")
        val onPort = 23
        val eth = Packets.udp(
            fromMac, portNumToMac(onPort),
            makeAddressInSegment(onPort), IntIPv4.fromString("45.44.33.22"),
            10, 11, "My UDP packet".getBytes)
        triggerPacketIn(portNumToName(onPort), eth)

        requestOfType[PacketIn](simProbe())
        expectEmitIcmp(portNumToMac(onPort), myAddressOnPort(onPort),
            fromMac, makeAddressInSegment(onPort),
            ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toChar)
        val flow = expectFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        // A flow with no actions drops matching packets
        flow.getActions.size() should equal(0)
    }

    def testUnlinkedLogicalPort() {
        log.debug("creating logical port on router")
        val portAddr = "13.13.13.1"
        val nwAddr = "13.0.0.0"
        val nwLen = 8
        val hwAddr = MAC.fromString("34:12:34:12:34:12")
        var logicalPort = Ports.logicalRouterPort(clusterRouter).
            setPortAddr(portAddr).
            setNwAddr(nwAddr).
            setNwLength(nwLen).
            setNwAddr(nwAddr).
            setNwLength(nwLen)
        logicalPort = clusterDataClient().portsGet(clusterDataClient().
            portsCreate(logicalPort)).asInstanceOf[LogicalRouterPort]
        logicalPort should not be null
        newRoute(clusterRouter, "0.0.0.0", 0, "16.0.0.0", 8,
            NextHop.PORT, logicalPort.getId, "13.13.13.2", 1)

        val onPort = 23
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val eth = Packets.udp(
            fromMac, portNumToMac(onPort),
            makeAddressInSegment(onPort), IntIPv4.fromString("16.0.0.1"),
            10, 11, "My UDP packet".getBytes)
        triggerPacketIn(portNumToName(onPort), eth)

        expectPacketOnPort(portNumToId(onPort))
        val flow = fishForFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        flow.getActions.size() should equal(0)
        expectEmitIcmp(portNumToMac(onPort), myAddressOnPort(onPort),
            fromMac, makeAddressInSegment(onPort), ICMP.TYPE_UNREACH,
            ICMP.UNREACH_CODE.UNREACH_NET.toChar)
    }

    def testArpRequestTimeout() {
        val (router, port) = fetchRouterAndPort("uplinkPort")
        val myIp = IntIPv4.fromString(uplinkPortAddr)
        val hisIp = IntIPv4.fromString(uplinkGatewayAddr)
        val expiry = Platform.currentTime + ARP_TIMEOUT_SECS * 1000 + 1000
        val arpPromise = router.arpTable.get(hisIp, port, expiry)(
            actors().dispatcher, actors(), null)

        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, myIp, hisIp)
        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, myIp, hisIp)
        simProbe().expectNoMsg(Timeout((ARP_TIMEOUT_SECS*2) seconds).duration)
        try {
            Await.result(arpPromise, Timeout(100 milliseconds).duration)
        } catch {
            case e: java.util.concurrent.TimeoutException =>
        }
    }

    def testArpRequestRetry() {
        val (router, port) = fetchRouterAndPort("uplinkPort")
        val myMac = uplinkMacAddr
        val myIp = IntIPv4.fromString(uplinkPortAddr)
        val hisMac = MAC.fromString("77:aa:66:bb:55:cc")
        val hisIp = IntIPv4.fromString(uplinkGatewayAddr)
        val expiry = Platform.currentTime + ARP_TIMEOUT_SECS * 1000 + 1000
        val arpPromise = router.arpTable.get(hisIp, port, expiry)(
            actors().dispatcher, actors(), null)

        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, myIp, hisIp)
        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, myIp, hisIp)
        feedArpCache("uplinkPort", hisIp.addressAsInt, hisMac,
                                   myIp.addressAsInt, myMac)
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()
        val mac: MAC = Await.result(arpPromise, Timeout(1 seconds).duration)
        mac should be === hisMac
        simProbe().expectNoMsg(Timeout((ARP_TIMEOUT_SECS*2) seconds).duration)
    }

    def testArpEntryExpiration() {
        val (router, port) = fetchRouterAndPort("uplinkPort")
        val mac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        val myIp = IntIPv4.fromString(uplinkPortAddr)
        val hisIp = IntIPv4.fromString(uplinkGatewayAddr)

        var expiry = Platform.currentTime + 1000
        var arpPromise = router.arpTable.get(hisIp, port, expiry)(
            actors().dispatcher, actors(), null)

        feedArpCache("uplinkPort",
            hisIp.addressAsInt, mac,
            myIp.addressAsInt, uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()
        var arpResult = Await.result(arpPromise, Timeout(1 second).duration)
        arpResult should be === mac

        dilatedSleep((ARP_STALE_SECS/2) * 1000)

        feedArpCache("uplinkPort",
            hisIp.addressAsInt, mac,
            myIp.addressAsInt, uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()
        expiry = Platform.currentTime + 1000
        arpPromise = router.arpTable.get(hisIp, port, expiry)(
            actors().dispatcher, actors(), null)
        arpResult = Await.result(arpPromise, Timeout(1 second).duration)
        arpResult should be === mac

        dilatedSleep((ARP_EXPIRATION_SECS - ARP_STALE_SECS/2 + 1) * 1000)

        drainProbes()
        expiry = Platform.currentTime + 1000
        arpPromise = router.arpTable.get(hisIp, port, expiry)(
            actors().dispatcher, actors(), null)
        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, myIp, hisIp)
        try {
            Await.result(arpPromise, Timeout(100 milliseconds).duration)
            false should not be true
        } catch {
            case e: java.util.concurrent.TimeoutException =>
        }
    }
/*
    @Ignore def testFilterBadSrcForPort() {
    }

    @Ignore def testFilterBadDestination() {
    }

    @Ignore def testDnat() {
    }

    */
}
