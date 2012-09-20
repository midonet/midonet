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

import com.midokura.midolman.FlowController._
import com.midokura.midolman.layer3.Route.{NextHop, NO_GATEWAY}
import com.midokura.midolman.simulation.{Router => SimRouter, ArpTableImpl}
import com.midokura.midolman.topology.VirtualToPhysicalMapper.{LocalPortActive,
                                                               HostRequest}
import com.midokura.midolman.topology.VirtualTopologyActor.{PortRequest,
                                                            RouterRequest}
import com.midokura.midonet.cluster.client.{RouterPort, ExteriorRouterPort}
import com.midokura.midonet.cluster.data.{Router => ClusterRouter}
import com.midokura.midonet.cluster.data.ports.MaterializedRouterPort
import com.midokura.packets._
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.FlowController.WildcardFlowAdded
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.sdn.dp.flows.{FlowKeyIPv4, FlowKeyEthernet,
                                  FlowActionSetKey, FlowActionOutput}
import com.midokura.midolman.state.ArpCacheEntry
import com.midokura.midolman.state.ReplicatedMap.Watcher
import com.midokura.midolman.SimulationController.EmitGeneratedPacket

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
        val host = newHost("myself", hostId())
        router = newRouter("router")
        // Create one port that works as an uplink for the router.
        uplinkPort = newPortOnRouter(router, uplinkMacAddr, uplinkPortAddr,
                                     uplinkNwAddr, uplinkNwLen,
                                     uplinkNwAddr, uplinkNwLen)

        initializeDatapath() should not be (null)

        materializePort(uplinkPort, host, "uplinkPort")
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())
        requestOfType[LocalPortActive](vtpProbe())

        upLinkRoute = newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0, NextHop.PORT,
            uplinkPort.getId, uplinkGatewayAddr, 1)

        router should not be null
        uplinkPort should not be null
        upLinkRoute should not be null

        for (i <- 0 to 2) {
            // Nw address is 10.0.<i>.0/24
            val nwAddr = 0x0a000000 + (i << 8)
            // All ports in this subnet share the same ip address: 10.0.<i>.1
            val portAddr = nwAddr + 1
            for (j <- 1 to 3) {
                val macAddr = MAC.fromString("0a:0b:0c:0d:0" + i + ":0" + j)
                val portNum = i * 10 + j
                val portName = "port" + portNum
                // The port will route to 10.0.<i>.<j*4>/30
                val segmentAddr = new IntIPv4(nwAddr + (j * 4))

                val port = newPortOnRouter(router, macAddr,
                    new IntIPv4(portAddr).toString,
                    segmentAddr.toString, 30,
                    new IntIPv4(nwAddr).toString, 24)

                port should not be null

                materializePort(port, host, portName)
                requestOfType[OutgoingMessage](vtpProbe())
                requestOfType[LocalPortActive](vtpProbe())


                log.debug("Created router port {}, {}", portName, macAddr)

                // store port for later use
                portConfigs.put(portNum, port)
                portNumToId.put(portNum, port.getId)
                portNumToMac.put(portNum, macAddr)
                portNumToName.put(portNum, portName)
                portNumToSegmentAddr.put(portNum, segmentAddr.addressAsInt)

                // Default route to port based on destination only.  Weight 2.
                var rt = newRoute(router, "0.0.0.0", 0, segmentAddr.toString, 30,
                    NextHop.PORT, port.getId, new IntIPv4(NO_GATEWAY).toString,
                    2)
                /* XXX - discuss.
                if (1 == j) {
                    // The first port's routes are added manually because the
                    // first port will be treated as remote.
                    rTable.addRoute(rt)
                }
                */

                // Anything from 10.0.0.0/16 is allowed through.  Weight 1.
                rt = newRoute(router, "10.0.0.0", 16, segmentAddr.toString, 30,
                    NextHop.PORT, port.getId, new IntIPv4(NO_GATEWAY).toString,
                    1)
                // XXX see above.

                // Anything from 11.0.0.0/24 is silently dropped.  Weight 1.
                rt = newRoute(router, "11.0.0.0", 24, segmentAddr.toString, 30,
                    NextHop.BLACKHOLE, null, null, 1)

                // Anything from 12.0.0.0/24 is rejected (ICMP filter
                // prohibited).
                rt = newRoute(router, "12.0.0.0", 24, segmentAddr.toString, 30,
                    NextHop.REJECT, null, null, 1)
                /* XXX - discuss
                if (1 != j) {
                    // Except for the first port, add them locally.
                    rtr.addPort(portId)
                }*/
            } // end for-loop on j
        } // end for-loop on i

        flowEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])
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

    private def expectPacketOnPort(portNum: Int): PacketIn = {
        dpProbe().expectMsgClass(classOf[PacketIn])

        val pktInMsg = simProbe().expectMsgClass(classOf[PacketIn])
        pktInMsg should not be null
        pktInMsg.pktBytes should not be null
        pktInMsg.wMatch should not be null
        pktInMsg.wMatch.getInputPortUUID should be(portNumToId(portNum))
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

    def testDropsIPv6() {
        val onPort = 12
        val eth = (new Ethernet()).setEtherType(IPv6_ETHERTYPE).
            setDestinationMACAddress(portNumToMac(onPort)).
            setSourceMACAddress(MAC.fromString("01:02:03:04:05:06")).
            setPad(true)
        triggerPacketIn(portNumToName(onPort), eth)
        expectPacketOnPort(onPort)
        flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())
        addFlowMsg should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow.getMatch.getEthernetDestination should equal(portNumToMac(onPort))
        addFlowMsg.flow.getMatch.getEthernetSource should equal(MAC.fromString("01:02:03:04:05:06"))
        addFlowMsg.flow.getMatch.getEtherType should equal(IPv6_ETHERTYPE)
        // A flow with no actions drops matching packets
        addFlowMsg.flow.getActions.size() should equal(0)
    }

    def testForwardToUplink() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches the uplink
        // port (e.g. anything outside 10.  0.0.0/16).
        val gwMac = MAC.fromString("aa:bb:aa:cc:dd:cc")
        val onPort = 23
        val ttl: Byte = 17
        val eth = Packets.udp(
            MAC.fromString("01:02:03:04:05:06"),
            portNumToMac(onPort),
            new IntIPv4((portNumToSegmentAddr(onPort) + 2)),
            IntIPv4.fromString("45.44.33.22"),
            10, 11, "My UDP packet".getBytes)
        eth.getPayload.asInstanceOf[IPv4].setTtl(ttl)
        triggerPacketIn(portNumToName(onPort), eth)
        expectPacketOnPort(onPort)
        feedArpCache("uplinkPort",
            IntIPv4.fromString(uplinkGatewayAddr).addressAsInt,
            gwMac,
            IntIPv4.fromString(uplinkPortAddr).addressAsInt,
            uplinkMacAddr)

        requestOfType[DiscardPacket](flowProbe())
        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())
        addFlowMsg should not be null
        addFlowMsg.flow should not be null
        val flow = addFlowMsg.flow
        flow.getMatch.getEthernetSource should equal(MAC.fromString("01:02:03:04:05:06"))
        flow.getMatch.getEthernetDestination should equal(portNumToMac(onPort))
        flow.getMatch.getEtherType should equal(IPv4.ETHERTYPE)
        flow.getMatch.getNetworkProtocol should equal(UDP.PROTOCOL_NUMBER)
        flow.getMatch.getTransportSource should equal(10)
        flow.getMatch.getTransportDestination should equal(11)
        // XXX - there should be a FlowKeyIPv4 action to set the ttl here
        flow.getActions.size() should equal(3)
        val ethKey =
            expectFlowActionSetKey[FlowKeyEthernet](flow.getActions.get(0))
        ethKey.getDst should be === gwMac.getAddress
        ethKey.getSrc should be === uplinkMacAddr.getAddress
        val ipKey = expectFlowActionSetKey[FlowKeyIPv4](flow.getActions.get(1))
        ipKey.getTtl should be === (ttl-1)
        flow.getActions.get(2).getClass should equal(classOf[FlowActionOutput])
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
        val macFuture = router.arpTable.get(
            ip, port,
            Platform.currentTime + 30*1000)(actors().dispatcher, actors())

        val now = Platform.currentTime
        arpCache.processChange(ip, null,
            new ArpCacheEntry(mac, now + 60*1000, now + 30*1000, 0))
        val t = Timeout(3 seconds)
        val arpResult = Await.result(macFuture, t.duration)
        arpResult should be === mac
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

    @Ignore def testBlackholeRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 11.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
    }

    @Ignore def testRejectRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 12.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
    }

    @Ignore def testNoRoute() {
    }

    @Ignore def testICMPEcho() {
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
