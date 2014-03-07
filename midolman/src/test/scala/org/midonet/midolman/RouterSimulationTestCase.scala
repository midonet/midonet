/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.UUID
import scala.annotation.tailrec
import scala.collection.mutable
import scala.compat.Platform
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.util.Timeout
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.RouterPort
import org.midonet.cluster.data.{Ports, Router => ClusterRouter}
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.DeduplicationActor.DiscardPacket
import org.midonet.midolman.FlowController._
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.guice.actors.OutgoingMessage
import org.midonet.midolman.layer3.Route.{NextHop, NO_GATEWAY}
import org.midonet.midolman.rules.{RuleResult, NatTarget, Condition}
import org.midonet.midolman.simulation.{ArpTableImpl, RouteBalancer, PacketContext}
import org.midonet.midolman.state.ArpCacheEntry
import org.midonet.midolman.state.ReplicatedMap.Watcher
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest
import org.midonet.midolman.util.RouterHelper
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.odp.flows.FlowActionSetKey
import org.midonet.odp.flows.FlowKeyEthernet
import org.midonet.odp.flows.FlowKeyIPv4
import org.midonet.packets._
import org.midonet.sdn.flows.WildcardMatch

@Category(Array(classOf[SimulationTests]))
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

    private var clusterRouter: ClusterRouter = null
    private val uplinkGatewayAddr = "180.0.1.1"
    private val uplinkNwAddr = "180.0.1.0"
    private val uplinkNwLen = 24
    private val uplinkPortAddr = "180.0.1.2"
    private val uplinkMacAddr = MAC.fromString("02:0a:08:06:04:02")
    private var uplinkPort: RouterPort = null
    private var upLinkRoute: UUID = null
    private var host: Host = null

    private val portConfigs = mutable.Map[Int, RouterPort]()
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

        host = newHost("myself", hostId())
        host should not be null
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        // Create one port that works as an uplink for the router.
        uplinkPort = newRouterPort(clusterRouter, uplinkMacAddr, uplinkPortAddr,
                                   uplinkNwAddr, uplinkNwLen)
        uplinkPort should not be null
        uplinkPort =
            materializePort(uplinkPort, host,"uplinkPort")
                .asInstanceOf[RouterPort]
        var portEvent = requestOfType[LocalPortActive](portsProbe)
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
                val segmentAddr = new IPv4Addr(nwAddr + j*4)
                val portAddr = new IPv4Addr(nwAddr + j*4 + 1)

                var port = newRouterPort(clusterRouter, macAddr,
                    portAddr.toString, segmentAddr.toString, 30)
                port should not be null

                port = materializePort(port, host,
                  portName).asInstanceOf[RouterPort]
                portEvent = requestOfType[LocalPortActive](portsProbe)
                portEvent.active should be(true)
                portEvent.portID should be(port.getId)

                // store port info for later use
                portConfigs.put(portNum, port)
                portNumToId.put(portNum, port.getId)
                portNumToMac.put(portNum, macAddr)
                portNumToName.put(portNum, portName)
                portNumToSegmentAddr.put(portNum, segmentAddr.addr)

                // Default route to port based on destination only.  Weight 2.
                newRoute(clusterRouter, "10.0.0.0", 16, segmentAddr.toString, 30,
                    NextHop.PORT, port.getId, new IPv4Addr(NO_GATEWAY).toString,
                    2)
                // Anything from 10.0.0.0/16 is allowed through.  Weight 1.
                newRoute(clusterRouter, "10.0.0.0", 16, segmentAddr.toString, 30,
                    NextHop.PORT, port.getId, new IPv4Addr(NO_GATEWAY).toString,
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

    private def makeAddressInSegment(portNum: Int) : IPv4Addr =
        new IPv4Addr(portNumToSegmentAddr(portNum) + 2)

    private def myAddressOnPort(portNum: Int): IPv4Addr =
        new IPv4Addr(portNumToSegmentAddr(portNum) + 1)

    implicit private def dummyPacketContext =
        new PacketContext(None, null, Platform.currentTime+5000,
                          null, null, null, false, None, new WildcardMatch())(actors())

    def testBalancesRoutes() {
        val routeDst = "21.31.41.51"
        val gateways = List("180.0.1.40", "180.0.1.41", "180.0.1.42")
        gateways foreach { gw =>
            newRoute(clusterRouter, "0.0.0.0", 0, routeDst, 32,
                     NextHop.PORT, uplinkPort.getId, gw, 1)
        }
        val (router, port) = fetchRouterAndPort("uplinkPort", uplinkPort.getId)
        val rb = new RouteBalancer(router.rTable)

        val wmatch = new WildcardMatch().
            setNetworkSource(IPv4Addr.fromString(uplinkPortAddr)).
            setNetworkDestination(IPv4Addr.fromString(routeDst))

        @tailrec
        def matchAllResults(resultPool: List[String]) {
            val rt = rb.lookup(wmatch)
            rt should not be null
            val gw = rt.getNextHopGateway
            gw should not be null
            resultPool should contain (gw)
            if (resultPool.length > 1)
                matchAllResults(resultPool filterNot { _ == gw })
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
        flow.actions.size should equal(0)
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
            makeAddressInSegment(onPort), IPv4Addr.fromString("45.44.33.22"),
            10, 11, "My UDP packet".getBytes)
        eth.getPayload.asInstanceOf[IPv4].setTtl(ttl)

        feedArpCache("uplinkPort",
            IPv4Addr.fromString(uplinkGatewayAddr).addr,
            gwMac,
            IPv4Addr.fromString(uplinkPortAddr).addr,
            uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        drainProbes()

        triggerPacketIn(portNumToName(onPort), eth)
        expectPacketOnPort(portNumToId(onPort))
        val flow = expectFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        flow.actions.size should equal(3)
        val ethKey =
            expectFlowActionSetKey[FlowKeyEthernet](flow.actions(0))
        ethKey.getDst should be (gwMac.getAddress)
        ethKey.getSrc should be (uplinkMacAddr.getAddress)
        val ipKey = expectFlowActionSetKey[FlowKeyIPv4](flow.actions(1))
        ipKey.getTtl should be (ttl-1)
        flow.actions(2).getClass should equal(classOf[FlowActionOutput])
    }

    def testBlackholeRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 11.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
        val toPort = 21
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = IPv4Addr.fromString("11.0.0.31")
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
        flow.actions.size should equal(0)
        requestOfType[DiscardPacket](discardPacketProbe)
    }

    def testRejectRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 12.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
        val toPort = 21
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = IPv4Addr.fromString("12.0.0.31")
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
        flow.actions.size should equal(0)

        expectEmitIcmp(uplinkMacAddr, IPv4Addr.fromString(uplinkPortAddr),
                        fromMac, fromIp, ICMP.TYPE_UNREACH,
                        ICMP.UNREACH_CODE.UNREACH_FILTER_PROHIB.toByte)
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

        feedArpCache(portNumToName(outPort), toIp.addr, outToMac,
                     myAddressOnPort(outPort).addr, outFromMac)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        drainProbes()
        triggerPacketIn(portNumToName(inPort), eth)
        expectPacketOnPort(portNumToId(inPort))

        val flow = expectFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        flow.actions.size should be (3)
        val ethKey =
            expectFlowActionSetKey[FlowKeyEthernet](flow.actions(0))
        ethKey.getDst should be (outToMac.getAddress)
        ethKey.getSrc should be (outFromMac.getAddress)
        flow.actions(2).getClass should equal(classOf[FlowActionOutput])
    }

    def testNoRoute() {
        clusterDataClient().routesDelete(upLinkRoute)

        val onPort = 23
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val eth = Packets.udp(
            fromMac, portNumToMac(onPort),
            makeAddressInSegment(onPort), IPv4Addr.fromString("45.44.33.22"),
            10, 11, "My UDP packet".getBytes)
        triggerPacketIn(portNumToName(onPort), eth)
        expectPacketOnPort(portNumToId(onPort))

        val flow = expectFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        flow.actions.size should equal(0)
        expectEmitIcmp(portNumToMac(onPort), myAddressOnPort(onPort),
            fromMac, makeAddressInSegment(onPort), ICMP.TYPE_UNREACH,
            ICMP.UNREACH_CODE.UNREACH_NET.toByte)
    }

    def testArpRequestFulfilledLocally() {
        val (router, port) = fetchRouterAndPort("uplinkPort", uplinkPort.getId)
        val mac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        val expiry = Platform.currentTime + 1000
        val arpPromise = router.arpTable.get(
            IPv4Addr.fromString(uplinkGatewayAddr), port, expiry)
        fishForRequestOfType[EmitGeneratedPacket](dedupProbe())

        feedArpCache("uplinkPort",
            IPv4Addr.stringToInt(uplinkGatewayAddr), mac,
            IPv4Addr.fromString(uplinkPortAddr).addr,
            uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        drainProbes()
        val arpResult = Await.result(arpPromise, Timeout(3 seconds).duration)
        arpResult should be (mac)
    }

    def testArpRequestFulfilledRemotely() {
        val (router, port) = fetchRouterAndPort("uplinkPort", uplinkPort.getId)

        val ip = IPv4Addr.fromString(uplinkGatewayAddr)
        val mac = MAC.fromString("fe:fe:fe:da:da:da")

        val arpTable = router.arpTable.asInstanceOf[ArpTableImpl]
        val arpCache = arpTable.arpCache.asInstanceOf[Watcher[IPv4Addr,
                                                              ArpCacheEntry]]
        val macFuture = router.arpTable.get(ip, port,
            Platform.currentTime + 30*1000)
        fishForRequestOfType[EmitGeneratedPacket](dedupProbe())

        val now = Platform.currentTime
        arpCache.processChange(ip, null,
            new ArpCacheEntry(mac, now + 60*1000, now + 30*1000, 0))
        val arpResult = Await.result(macFuture, Timeout(3 seconds).duration)
        arpResult should be (mac)
    }

    def testArpRequestGeneration() {
        val (router, port) = fetchRouterAndPort("uplinkPort", uplinkPort.getId)
        val expiry = Platform.currentTime + 1000
        val fromIp = IPv4Addr.fromString(uplinkPortAddr)
        val toIp = IPv4Addr.fromString(uplinkGatewayAddr)

        val arpPromise = router.arpTable.get(toIp, port, expiry)
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
        val (router, port) = fetchRouterAndPort("uplinkPort", uplinkPort.getId)
        val hisIp = IPv4Addr.fromString(uplinkGatewayAddr)
        val myIp = IPv4Addr.fromString(uplinkPortAddr)
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
            setSenderProtocolAddress(IPv4Addr.intToBytes(hisIp.toInt)).
            setTargetProtocolAddress(IPv4Addr.intToBytes(myIp.toInt)).
            setTargetHardwareAddress(if (isUnicast) myMac else bcastMac))
        triggerPacketIn("uplinkPort", eth)
        requestOfType[PacketIn](packetInProbe)
        val msg = fishForRequestOfType[EmitGeneratedPacket](dedupProbe())
        msg.egressPort should be (uplinkPort.getId)
        msg.eth.getEtherType should be (ARP.ETHERTYPE)
        msg.eth.getSourceMACAddress should be (myMac)
        msg.eth.getDestinationMACAddress should be (hisMac)
        msg.eth.getPayload.getClass should be (classOf[ARP])
        val arp = msg.eth.getPayload.asInstanceOf[ARP]
        arp.getOpCode should be (ARP.OP_REPLY)
        IPv4Addr.fromBytes(arp.getSenderProtocolAddress) should be (myIp)
        arp.getSenderHardwareAddress should be (myMac)
        IPv4Addr.fromBytes(arp.getTargetProtocolAddress) should be (hisIp)
        arp.getTargetHardwareAddress should be (hisMac)

        val expiry = Platform.currentTime + 1000
        val arpPromise = router.arpTable.get(hisIp, port, expiry)
        val t = Timeout(1 second)
        val arpResult = Await.result(arpPromise, t.duration)
        arpResult should be (hisMac)
    }

    def testIcmpEchoNearPort() {
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = "50.25.50.25"

        feedArpCache("uplinkPort",
            IPv4Addr.fromString(uplinkGatewayAddr).addr,
            fromMac,
            IPv4Addr.fromString(uplinkPortAddr).addr,
            uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
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
        requestOfType[DiscardPacket](discardPacketProbe)
        expectEmitIcmp(uplinkMacAddr, IPv4Addr.fromString(uplinkPortAddr),
            fromMac, IPv4Addr.fromString(fromIp), ICMP.TYPE_ECHO_REPLY,
            ICMP.CODE_NONE)
    }

    def testIcmpEchoFarPort() {
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = "10.0.50.25"
        val farPort = 21

        feedArpCache("uplinkPort",
            IPv4Addr.fromString(uplinkGatewayAddr).addr,
            fromMac,
            IPv4Addr.fromString(uplinkPortAddr).addr,
            uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        drainProbes()

        val echo = new ICMP()
        echo.setEchoRequest(16, 32, "My ICMP".getBytes)
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(fromMac).
            setDestinationMACAddress(uplinkMacAddr).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(fromIp).
            setDestinationAddress(myAddressOnPort(farPort).addr).
            setProtocol(ICMP.PROTOCOL_NUMBER).
            setPayload(echo))
        triggerPacketIn("uplinkPort", eth)
        expectPacketOnPort(uplinkPort.getId)
        requestOfType[DiscardPacket](discardPacketProbe)
        expectEmitIcmp(uplinkMacAddr, myAddressOnPort(farPort),
            fromMac, IPv4Addr.fromString(fromIp), ICMP.TYPE_ECHO_REPLY,
            ICMP.CODE_NONE)
    }

    /**
     * See #547:
     *
     * Router port fails to reply to ping if the port address is mapped to a
     * floating IP.
     */
    def testIcmpEchoFarPortWithFloatingIP() {
        // We need a bit of extra setup here, let's add a new internal port
        // in the router, and assign a FloatingIP
        val mac = MAC.fromString("cc:dd:ee:44:22:33")
        val floatingIp = IPv4Addr.fromString("176.28.127.1")
        val privIp = IPv4Addr.fromString("176.28.0.1")
        val nwAddr = "176.28.0.0"
        val nwLen =16
        val port = newRouterPort(clusterRouter, mac,
                                         privIp.toString, nwAddr, nwLen)
        port should not be null

        // The interior port routes do not take effect until it is linked.
        // So link it to a dummy router
        val peerRouter = newRouter("peer_router")
        peerRouter should not be null
        val peerMac = MAC.fromString("cc:dd:ee:44:22:34")
        val peerIp = IPv4Addr.fromString("176.28.0.2")
        val peerPort = newRouterPort(peerRouter, peerMac, peerIp.toString,
                                     nwAddr, nwLen)
        peerPort should not be null
        linkPorts(port, peerPort)

        log.info("Feeding ARP cache for the uplink")
        feedArpCache("uplinkPort",
            IPv4Addr.fromString(uplinkGatewayAddr).addr,
            MAC.fromString("44:44:44:44:44:22"),
            IPv4Addr.fromString(uplinkPortAddr).addr,
            uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        drainProbes()

        log.info("Setting Floating IP rules")
        // Set the FloatingIp rule
        val dnatCond = new Condition()
        dnatCond.nwDstIp = floatingIp.subnet()
        val dnatTarget = new NatTarget(privIp.toInt, privIp.toInt, 0, 0)
        val preChain = newInboundChainOnRouter("pre_routing", clusterRouter)
        val postChain = newOutboundChainOnRouter("post_routing", clusterRouter)
        val dnatRule = newForwardNatRuleOnChain(preChain, 1, dnatCond,
            RuleResult.Action.ACCEPT, Set(dnatTarget), isDnat = true)
        dnatRule should not be null
        val snatCond = new Condition()
        snatCond.nwSrcIp = privIp.subnet()
        val snatTarget = new NatTarget(floatingIp.toInt, floatingIp.toInt, 0, 0)
        val snatRule = newForwardNatRuleOnChain(postChain, 1, snatCond,
            RuleResult.Action.ACCEPT, Set(snatTarget), isDnat = false)
        snatRule should not be null

        log.info("Send a PING from the uplink")
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = "180.0.1.23"
        val echo = new ICMP()
        echo.setEchoRequest(16, 32, "My ICMP".getBytes)
        val eth: Ethernet = new Ethernet()
            .setSourceMACAddress(fromMac)
            .setDestinationMACAddress(uplinkMacAddr)
            .setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(fromIp)
            .setDestinationAddress(floatingIp.toInt)
            .setProtocol(ICMP.PROTOCOL_NUMBER)
            .setPayload(echo))

        triggerPacketIn("uplinkPort", eth)
        expectPacketOnPort(uplinkPort.getId)
        requestOfType[DiscardPacket](discardPacketProbe)

        val pktReply = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktReply should not be null
        val ethReply = applyOutPacketActions(pktReply)
        val ipReply = ethReply.getPayload.asInstanceOf[IPv4]
        ipReply.getSourceAddress should be (floatingIp.toInt)
        ipReply.getDestinationAddress should be (IPv4Addr.fromString(fromIp).addr)
        val icmpReply = ipReply.getPayload.asInstanceOf[ICMP]
        icmpReply.getType should be (ICMP.TYPE_ECHO_REPLY)
        icmpReply.getCode should be (ICMP.CODE_NONE)
        icmpReply.getIdentifier should be (echo.getIdentifier)
        icmpReply.getSequenceNum should be (echo.getSequenceNum)
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
            makeAddressInSegment(onPort), IPv4Addr.fromString("45.44.33.22"),
            10, 11, "My UDP packet".getBytes)
        triggerPacketIn(portNumToName(onPort), eth)

        requestOfType[PacketIn](packetInProbe)
        expectEmitIcmp(portNumToMac(onPort), myAddressOnPort(onPort),
            fromMac, makeAddressInSegment(onPort),
            ICMP.TYPE_UNREACH, ICMP.UNREACH_CODE.UNREACH_NET.toByte)
        val flow = expectFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        // A flow with no actions drops matching packets
        flow.actions.size should equal(0)
    }

    def testUnlinkedLogicalPort() {
        log.debug("creating logical port on router")
        val portAddr = "13.13.13.1"
        val nwAddr = "13.0.0.0"
        val nwLen = 8
        val hwAddr = MAC.fromString("34:12:34:12:34:12")
        var logicalPort = Ports.routerPort(clusterRouter).
            setPortAddr(portAddr).
            setNwAddr(nwAddr).
            setNwLength(nwLen).
            setNwAddr(nwAddr).
            setNwLength(nwLen)
        logicalPort = clusterDataClient().portsGet(clusterDataClient().
            portsCreate(logicalPort)).asInstanceOf[RouterPort]
        logicalPort should not be null
        newRoute(clusterRouter, "0.0.0.0", 0, "16.0.0.0", 8,
            NextHop.PORT, logicalPort.getId, "13.13.13.2", 1)

        // Remove the uplink route so that the traffic won't get forwarded
        // there.
        deleteRoute(upLinkRoute)

        val onPort = 23
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val eth = Packets.udp(
            fromMac, portNumToMac(onPort),
            makeAddressInSegment(onPort), IPv4Addr.fromString("16.0.0.1"),
            10, 11, "My UDP packet".getBytes)
        triggerPacketIn(portNumToName(onPort), eth)

        expectPacketOnPort(portNumToId(onPort))
        val flow = fishForFlowAddedMessage()
        expectMatchForIPv4Packet(eth, flow.getMatch)
        flow.actions.size should equal(0)
        expectEmitIcmp(portNumToMac(onPort), myAddressOnPort(onPort),
            fromMac, makeAddressInSegment(onPort), ICMP.TYPE_UNREACH,
            ICMP.UNREACH_CODE.UNREACH_NET.toByte)
    }

    def testArpRequestTimeout() {
        val (router, port) = fetchRouterAndPort("uplinkPort", uplinkPort.getId)
        val myIp = IPv4Addr.fromString(uplinkPortAddr)
        val hisIp = IPv4Addr.fromString(uplinkGatewayAddr)
        val expiry = Platform.currentTime + ARP_TIMEOUT_SECS * 1000 + 1000
        val arpPromise = router.arpTable.get(hisIp, port, expiry)

        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, myIp, hisIp)
        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, myIp, hisIp)
        packetInProbe.expectNoMsg(Timeout((ARP_TIMEOUT_SECS*2) seconds).duration)
        try {
            Await.result(arpPromise, Timeout(100 milliseconds).duration)
        } catch {
            case e: java.util.concurrent.TimeoutException =>
        }
    }

    def testArpRequestRetry() {
        val (router, port) = fetchRouterAndPort("uplinkPort", uplinkPort.getId)
        val myMac = uplinkMacAddr
        val myIp = IPv4Addr.fromString(uplinkPortAddr)
        val hisMac = MAC.fromString("77:aa:66:bb:55:cc")
        val hisIp = IPv4Addr.fromString(uplinkGatewayAddr)
        val expiry = Platform.currentTime + ARP_TIMEOUT_SECS * 1000 + 1000
        val arpPromise = router.arpTable.get(hisIp, port, expiry)

        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, myIp, hisIp)
        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, myIp, hisIp)
        feedArpCache("uplinkPort", hisIp.addr, hisMac, myIp.addr, myMac)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        drainProbes()
        val mac: MAC = Await.result(arpPromise, Timeout(1 seconds).duration)
        mac should be (hisMac)
        dedupProbe().expectNoMsg(Timeout((ARP_TIMEOUT_SECS*2) seconds).duration)
    }

    def testArpEntryExpiration() {
        val (router, port) = fetchRouterAndPort("uplinkPort", uplinkPort.getId)
        val mac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        val myIp = IPv4Addr.fromString(uplinkPortAddr)
        val hisIp = IPv4Addr.fromString(uplinkGatewayAddr)

        var expiry = Platform.currentTime + 1000
        var arpPromise = router.arpTable.get(hisIp, port, expiry)

        feedArpCache("uplinkPort", hisIp.addr, mac, myIp.addr, uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        drainProbes()
        var arpResult = Await.result(arpPromise, Timeout(1 second).duration)
        arpResult should be (mac)

        dilatedSleep((ARP_STALE_SECS/2) * 1000)

        feedArpCache("uplinkPort", hisIp.addr, mac, myIp.addr, uplinkMacAddr)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        drainProbes()
        expiry = Platform.currentTime + 1000
        arpPromise = router.arpTable.get(hisIp, port, expiry)
        arpResult = Await.result(arpPromise, Timeout(1 second).duration)
        arpResult should be (mac)

        dilatedSleep((ARP_EXPIRATION_SECS - ARP_STALE_SECS/2 + 1) * 1000)

        drainProbes()
        expiry = Platform.currentTime + 1000
        arpPromise = router.arpTable.get(hisIp, port, expiry)
        expectEmitArpRequest(uplinkPort.getId, uplinkMacAddr, myIp, hisIp)
        try {
            // No one replies to the ARP request, so the get should return
            // null. We have to wait long enough to give it a chance to time
            // out and complete the promise with null.
            Await.result(arpPromise, Timeout(2000 milliseconds).duration
                ) should be (null)
        } catch {
            case _: Throwable => // We don't expect to get any exceptions
                false should be (true)
        }
    }

    def testDropsVlanTraffic() {
        val onPort = 12
        val eth = new Ethernet()
                      .setEtherType(Ethernet.VLAN_TAGGED_FRAME)
                      .setDestinationMACAddress(portNumToMac(onPort))
                      .setVlanID(10)
                      .setSourceMACAddress(MAC.fromString("01:02:03:04:05:06"))
                      .setPad(true)
        triggerPacketIn(portNumToName(onPort), eth)
        expectPacketOnPort(portNumToId(onPort))
        val flow = expectFlowAddedMessage()
        flow.getMatch.getEthernetDestination should equal(portNumToMac(onPort))
        flow.getMatch.getEthernetSource should equal(MAC.fromString("01:02:03:04:05:06"))
        flow.getMatch.getVlanIds.size() should equal(1)
        flow.getMatch.getVlanIds.get(0) should equal(10)
        // A flow with no actions drops matching packets
        flow.actions.size should equal(0)

    }
}
