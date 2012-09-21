/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import scala.collection.mutable
import scala.compat.Platform
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
import com.midokura.midolman.simulation.{ArpTableImpl, Router => SimRouter}
import com.midokura.midolman.state.ArpCacheEntry
import com.midokura.midolman.state.ReplicatedMap.Watcher
import com.midokura.midolman.topology.VirtualToPhysicalMapper.{HostRequest,
                                                               LocalPortActive}
import com.midokura.midolman.topology.VirtualTopologyActor.{PortRequest,
                                                            RouterRequest}
import com.midokura.midonet.cluster.client.{ExteriorRouterPort, RouterPort}
import com.midokura.midonet.cluster.data.{Router => ClusterRouter}
import com.midokura.midonet.cluster.data.ports.MaterializedRouterPort
import com.midokura.packets._
import com.midokura.sdn.dp.flows.{FlowActionSetKey, FlowActionOutput,
                                  FlowKeyEthernet, FlowKeyIPv4}
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}


@RunWith(classOf[JUnitRunner])
class RouterSimulationTestCase extends MidolmanTestCase with
        VirtualConfigurationBuilders {
    private final val log =
         LoggerFactory.getLogger(classOf[RouterSimulationTestCase])

    val IPv6_ETHERTYPE: Short = 0x86dd.toShort
    private var flowEventsProbe: TestProbe = null
    private var router: ClusterRouter = null
    private val uplinkGatewayAddr = "180.0.1.1"
    private val uplinkNwAddr = "180.0.1.0"
    private val uplinkNwLen = 30
    private val uplinkPortAddr = "180.0.1.2"
    private val uplinkMacAddr = MAC.fromString("02:0a:08:06:04:02")
    private var uplinkPort: MaterializedRouterPort = null
    private var upLinkRoute: UUID = null

    private val portConfigs = mutable.Map[Int, MaterializedRouterPort]()
    private val portNumToId = mutable.Map[Int, UUID]()
    private val portNumToMac = mutable.Map[Int, MAC]()
    private val portNumToName = mutable.Map[Int, String]()
    private val portNumToSegmentAddr = mutable.Map[Int, Int]()

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    override def before() {
        flowEventsProbe = newProbe()
        val portEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(portEventsProbe.ref, classOf[LocalPortActive])

        val host = newHost("myself", hostId())
        host should not be null
        router = newRouter("router")
        router should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        // Create one port that works as an uplink for the router.
        uplinkPort = newPortOnRouter(router, uplinkMacAddr, uplinkPortAddr,
            uplinkNwAddr, uplinkNwLen)
        uplinkPort should not be null
        materializePort(uplinkPort, host, "uplinkPort")
        requestOfType[LocalPortActive](portEventsProbe)

        upLinkRoute = newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0, NextHop.PORT,
            uplinkPort.getId, uplinkGatewayAddr, 1)
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

                val port = newPortOnRouter(router, macAddr, portAddr.toString,
                                           segmentAddr.toString, 30)
                port should not be null

                materializePort(port, host, portName)
                requestOfType[LocalPortActive](portEventsProbe)

                // store port info for later use
                portConfigs.put(portNum, port)
                portNumToId.put(portNum, port.getId)
                portNumToMac.put(portNum, macAddr)
                portNumToName.put(portNum, portName)
                portNumToSegmentAddr.put(portNum, segmentAddr.addressAsInt)

                // Default route to port based on destination only.  Weight 2.
                newRoute(router, "10.0.0.0", 16, segmentAddr.toString, 30,
                    NextHop.PORT, port.getId, new IntIPv4(NO_GATEWAY).toString,
                    2)
                // Anything from 10.0.0.0/16 is allowed through.  Weight 1.
                newRoute(router, "10.0.0.0", 16, segmentAddr.toString, 30,
                    NextHop.PORT, port.getId, new IntIPv4(NO_GATEWAY).toString,
                    1)
                // Anything from 11.0.0.0/24 is silently dropped.  Weight 1.
                newRoute(router, "11.0.0.0", 24, segmentAddr.toString, 30,
                    NextHop.BLACKHOLE, null, null, 1)
                // Anything from 12.0.0.0/24 is rejected (ICMP filter
                // prohibited).
                newRoute(router, "12.0.0.0", 24, segmentAddr.toString, 30,
                    NextHop.REJECT, null, null, 1)
            }
        }

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        drainProbes()
    }

    private def feedArpCache(portName: String, srcIp: Int, srcMac: MAC,
                                               dstIp: Int, dstMac: MAC) {
        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6)
        arp.setProtocolAddressLength(4)
        arp.setOpCode(ARP.OP_REPLY)
        arp.setSenderHardwareAddress(srcMac)
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(srcIp))
        arp.setTargetHardwareAddress(dstMac)
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(dstIp))

        val eth = new Ethernet()
        eth.setPayload(arp)
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(dstMac)
        eth.setEtherType(ARP.ETHERTYPE)
        triggerPacketIn(portName, eth)
    }

    private def expectPacketOnPort(port: UUID): PacketIn = {
        dpProbe().expectMsgClass(classOf[PacketIn])

        val pktInMsg = simProbe().expectMsgClass(classOf[PacketIn])
        pktInMsg should not be null
        pktInMsg.pktBytes should not be null
        pktInMsg.wMatch should not be null
        pktInMsg.wMatch.getInputPortUUID should be === port
        pktInMsg
    }

    private def expectFlowActionSetKey[T](action: AnyRef)(implicit m: Manifest[T]) : T = {
        as[T](as[FlowActionSetKey](action).getFlowKey)
    }

    private def fetchRouterAndPort(portName: String) : (SimRouter, RouterPort[_]) = {
        // Simulate a dummy packet so the system creates the Router RCU object
        val eth = (new Ethernet()).setEtherType(IPv6_ETHERTYPE).
            setDestinationMACAddress(MAC.fromString("de:de:de:de:de:de")).
            setSourceMACAddress(MAC.fromString("01:02:03:04:05:06")).
            setPad(true)
        triggerPacketIn(portName, eth)

        requestOfType[PortRequest](vtaProbe())
        val port = requestOfType[OutgoingMessage](vtaProbe()).m.asInstanceOf[RouterPort[_]]
        requestOfType[RouterRequest](vtaProbe())
        val router = replyOfType[SimRouter](vtaProbe())
        drainProbes()
        (router, port)
    }

    private def makeAddressInSegment(portNum: Int) : IntIPv4 =
        new IntIPv4(portNumToSegmentAddr(portNum) + 2)

    private def myAddressOnPort(portNum: Int): IntIPv4 =
        new IntIPv4(portNumToSegmentAddr(portNum) + 1)

    private def expectFlowAddedMessage(): WildcardFlow = {
        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())
        addFlowMsg should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow
    }

    private def expectMatchForIPv4Packet(pkt: Ethernet, wmatch: WildcardMatch) {
        wmatch.getEthernetDestination should be === pkt.getDestinationMACAddress
        wmatch.getEthernetSource should be === pkt.getSourceMACAddress
        wmatch.getEtherType should be === pkt.getEtherType
        val ipPkt = pkt.getPayload.asInstanceOf[IPv4]
        wmatch.getNetworkDestination should be === ipPkt.getDestinationAddress
        wmatch.getNetworkSource should be === ipPkt.getSourceAddress
        wmatch.getNetworkProtocol should be === ipPkt.getProtocol

        ipPkt.getProtocol match {
            case UDP.PROTOCOL_NUMBER =>
                val udpPkt = ipPkt.getPayload.asInstanceOf[UDP]
                wmatch.getTransportDestination should be === udpPkt.getDestinationPort
                wmatch.getTransportSource should be === udpPkt.getSourcePort
            case TCP.PROTOCOL_NUMBER =>
                val tcpPkt = ipPkt.getPayload.asInstanceOf[TCP]
                wmatch.getTransportDestination should be === tcpPkt.getDestinationPort
                wmatch.getTransportSource should be === tcpPkt.getSourcePort
            case _ =>
        }
    }

    private def expectEmitIcmp(fromMac: MAC, fromIp: IntIPv4,
                               toMac: MAC, toIp: IntIPv4,
                               icmpType: Char, icmpCode: Char) {
        val errorPkt = requestOfType[EmitGeneratedPacket](simProbe()).ethPkt
        errorPkt.getEtherType should be === IPv4.ETHERTYPE
        val ipErrorPkt = errorPkt.getPayload.asInstanceOf[IPv4]
        ipErrorPkt.getProtocol should be === ICMP.PROTOCOL_NUMBER
        ipErrorPkt.getDestinationAddress should be === toIp.addressAsInt
        ipErrorPkt.getSourceAddress should be === fromIp.addressAsInt
        val icmpPkt = ipErrorPkt.getPayload.asInstanceOf[ICMP]
        icmpPkt.getType should be === icmpType
        icmpPkt.getCode should be === icmpCode
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
        val ttl: Byte = 17
        val eth = Packets.udp(
            MAC.fromString("01:02:03:04:05:06"), portNumToMac(onPort),
            makeAddressInSegment(onPort), IntIPv4.fromString("45.44.33.22"),
            10, 11, "My UDP packet".getBytes)
        eth.getPayload.asInstanceOf[IPv4].setTtl(ttl)
        triggerPacketIn(portNumToName(onPort), eth)
        expectPacketOnPort(portNumToId(onPort))
        feedArpCache("uplinkPort",
            IntIPv4.fromString(uplinkGatewayAddr).addressAsInt,
            gwMac,
            IntIPv4.fromString(uplinkPortAddr).addressAsInt,
            uplinkMacAddr)

        requestOfType[DiscardPacket](flowProbe())

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
        requestOfType[DiscardPacket](flowProbe())
        expectPacketOnPort(portNumToId(outPort))
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
        val tuple = fetchRouterAndPort("uplinkPort")
        val router: SimRouter = tuple._1
        val port: RouterPort[_] = tuple._2
        val mac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        val expiry = Platform.currentTime + 1000

        val arpPromise = router.arpTable.get(
            IntIPv4.fromString(uplinkGatewayAddr), port, expiry)(
            actors().dispatcher, actors())
        requestOfType[EmitGeneratedPacket](simProbe())

        feedArpCache("uplinkPort",
            IPv4.toIPv4Address(uplinkGatewayAddr), mac,
            IntIPv4.fromString(uplinkPortAddr).addressAsInt,
            uplinkMacAddr)
        val t = Timeout(3 seconds)
        val arpResult = Await.result(arpPromise, t.duration)
        arpResult should be === mac
    }

    def testArpRequestFulfilledRemotely() {
        val tuple = fetchRouterAndPort("uplinkPort")
        val router: SimRouter = tuple._1
        val port: RouterPort[_] = tuple._2

        val ip = IntIPv4.fromString(uplinkGatewayAddr)
        val mac = MAC.fromString("fe:fe:fe:da:da:da")

        val arpTable = router.arpTable.asInstanceOf[ArpTableImpl]
        val arpCache = arpTable.arpCache.asInstanceOf[Watcher[IntIPv4,
                                                              ArpCacheEntry]]
        val macFuture = router.arpTable.get(ip, port,
            Platform.currentTime + 30*1000)(actors().dispatcher, actors())
        requestOfType[EmitGeneratedPacket](simProbe())

        val now = Platform.currentTime
        arpCache.processChange(ip, null,
            new ArpCacheEntry(mac, now + 60*1000, now + 30*1000, 0))
        val t = Timeout(3 seconds)
        val arpResult = Await.result(macFuture, t.duration)
        arpResult should be === mac
    }

    def testIcmpEchoNearPort() {
        val fromMac = MAC.fromString("01:02:03:04:05:06")
        val fromIp = "50.25.50.25"

        feedArpCache("uplinkPort",
            IntIPv4.fromString(uplinkGatewayAddr).addressAsInt,
            fromMac,
            IntIPv4.fromString(uplinkPortAddr).addressAsInt,
            uplinkMacAddr)
        expectPacketOnPort(uplinkPort.getId)

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
        expectPacketOnPort(uplinkPort.getId)

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

    /*
    @Ignore def testArpRequestNonLocalAddress() {
    }

    @Ignore def testArpRequestGeneration() {
    }

    @Ignore def testArpRequestRetry() {
    }

    @Ignore def testArpRequestTimeout() {
    }

    @Ignore def testArpReceivedRequestProcessing() {
    }

    @Ignore def testForwardBetweenDownlinks() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches port 12
        // (i.e. inside 10.0.1.8/30).
    }


    @Ignore def testUnlinkedLogicalPort() {
    }

    @Ignore def testFilterBadSrcForPort() {
    }

    @Ignore def testFilterBadDestination() {
    }

    @Ignore def testDnat() {
    }

    */
}
