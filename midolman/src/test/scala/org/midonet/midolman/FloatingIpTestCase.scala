/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman

import java.util.{HashSet => JHashSet, UUID}

import akka.testkit.TestProbe
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.ports.{RouterPort, BridgePort}
import org.midonet.midolman.DeduplicationActor.DiscardPacket
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.{RuleResult, NatTarget, Condition}
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest
import org.midonet.midolman.util.RouterHelper
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.guice.OutgoingMessage
import org.midonet.packets._

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class FloatingIpTestCase extends MidolmanTestCase
         with RouterHelper {
    private final val log = LoggerFactory.getLogger(classOf[FloatingIpTestCase])

    // Router port one connecting to host VM1
    val routerIp1 = IPv4Addr("192.168.111.1")
    val routerRange1 = new IPv4Subnet("192.168.111.0", 24)
    val routerMac1 = MAC.fromString("22:aa:aa:ff:ff:ff")
    // Interior router port connecting to bridge
    val routerIp2 = IPv4Addr("192.168.222.1")
    val routerRange2 = new IPv4Subnet("192.168.222.0", 24)
    val routerMac2 = MAC.fromString("22:ab:cd:ff:ff:ff")
    // VM1: remote host to ping
    val vm1Ip = IPv4Addr("192.168.111.2")
    val vm1Mac = MAC.fromString("02:23:24:25:26:27")
    // VM2
    val vm2Ip = IPv4Addr("192.168.222.2")
    val vm2Mac = MAC.fromString("02:DD:AA:DD:AA:03")

    val subnet1 = new IPv4Subnet("192.168.111.0", 24)
    val subnet2 = new IPv4Subnet("192.168.222.0", 24)

    // Other stuff
    var brPort2 : BridgePort = null
    val vm2PortName = "VM2"
    var vm2PortNumber = 0
    var rtrPort1 : RouterPort = null
    val rtrPort1Name = "RouterPort1"
    var rtrPort1Num = 0
    var rtrPort2 : RouterPort = null

    val floatingIP = IPv4Addr.fromString("10.0.173.5")

    override def beforeTest() {

        val host = newHost("myself", hostId())
        host should not be null
        val router = newRouter("router")
        router should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        // set up materialized port on router
        rtrPort1 = newRouterPort(router, routerMac1,
            routerIp1.toString,
            routerRange1.getAddress.toString,
            routerRange1.getPrefixLen)
        rtrPort1 should not be null
        materializePort(rtrPort1, host, rtrPort1Name)
        val portEvent = requestOfType[LocalPortActive](portsProbe)
        portEvent.active should be(true)
        portEvent.portID should be(rtrPort1.getId)
        vifToLocalPortNumber(rtrPort1.getId)
        match {
            case Some(portNo : Short) => rtrPort1Num = portNo
            case None => fail("Unable to find data port no. for Router port 1")
        }

        newRoute(router, "0.0.0.0", 0,
            routerRange1.getAddress.toString, routerRange1.getPrefixLen,
            NextHop.PORT, rtrPort1.getId,
            IPv4Addr(Route.NO_GATEWAY).toString, 10)

        // set up logical port on router
        rtrPort2 = newRouterPort(router, routerMac2,
            routerIp2.toString(),
            routerRange2.getAddress.toString,
            routerRange2.getPrefixLen)
        rtrPort2 should not be null

        newRoute(router, "0.0.0.0", 0,
            routerRange2.getAddress.toString, routerRange2.getPrefixLen,
            NextHop.PORT, rtrPort2.getId,
            IPv4Addr(Route.NO_GATEWAY).toString, 10)

        // create bridge link to router's logical port
        val bridge = newBridge("bridge")
        bridge should not be null

        val brPort1 = newBridgePort(bridge)
        brPort1 should not be null
        clusterDataClient().portsLink(rtrPort2.getId, brPort1.getId)

        // add a materialized port on bridge, logically connect to VM2
        brPort2 = newBridgePort(bridge)
        brPort2 should not be null

        materializePort(brPort2, host, vm2PortName)
        requestOfType[LocalPortActive](portsProbe)
        vifToLocalPortNumber(brPort2.getId)
        match {
            case Some(portNo : Short) => vm2PortNumber = portNo
            case None => fail("Unable to find data port no. for bridge port 2")
        }

        log.info("Setting up rule chain")

        val preChain = newInboundChainOnRouter("pre_routing", router)
        val postChain = newOutboundChainOnRouter("post_routing", router)

        // DNAT rule
        // assign floatingIP to VM1's private addr
        val dnatCond = new Condition()
        dnatCond.nwDstIp = new IPv4Subnet(floatingIP, 32)
        val dnatTarget = new NatTarget(vm1Ip.toInt,
                                       vm1Ip.toInt, 0, 0)
        val dnatRule = newForwardNatRuleOnChain(preChain, 1, dnatCond,
            RuleResult.Action.ACCEPT, Set(dnatTarget), isDnat = true)
        dnatRule should not be null

        // SNAT rules
        // assign floatingIP to VM1's private addr
        val snatCond = new Condition()
        snatCond.nwSrcIp = subnet1
        snatCond.outPortIds = new JHashSet[UUID]()
        snatCond.outPortIds.add(rtrPort2.getId)
        val snatTarget = new NatTarget(floatingIP.toInt,
                                       floatingIP.toInt, 0, 0)
        val snatRule = newForwardNatRuleOnChain(postChain, 1, snatCond,
            RuleResult.Action.ACCEPT, Set(snatTarget), isDnat = false)
        snatRule should not be null

        // TODO needed?
        clusterDataClient().routersUpdate(router)

        flowProbe().expectMsgType[DatapathController.DatapathReady]
                   .datapath should not be (null)
        drainProbes()
    }

    def test() {
        log.info("Feeding ARP cache on VM2")
        feedArpCache(vm2PortName, vm2Ip.toInt, vm2Mac,
                     routerIp2.toInt, routerMac2)
        requestOfType[PacketIn](packetInProbe)
        requestOfType[DiscardPacket](discardPacketProbe)
        drainProbes()

        log.info("Feeding ARP cache on VM1")
        feedArpCache(rtrPort1Name, vm1Ip.toInt, vm1Mac,
                     routerIp1.toInt, routerMac1)
        requestOfType[PacketIn](packetInProbe)
        requestOfType[DiscardPacket](discardPacketProbe)
        drainProbes()

        log.info("Sending a tcp packet VM2 -> floating IP, should be DNAT'ed")
        injectTcp(vm2PortName, vm2Mac, vm2Ip, 20301, routerMac2,
                  floatingIP, 80, syn = true)
        var pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getPacket should not be null
        var eth = applyOutPacketActions(pktOut)
        log.debug("Packet out: {}", pktOut)
        var ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceIPAddress should be (vm2Ip)
        ipPak.getDestinationIPAddress should be (vm1Ip)

        log.info(
            "Replying with tcp packet floatingIP -> VM2, should be SNAT'ed")
        injectTcp(rtrPort1Name, vm1Mac, vm1Ip, 20301, routerMac1,
                  vm2Ip, 80, syn = true)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getPacket should not be null
        eth = applyOutPacketActions(pktOut)
        log.debug("packet out: {}", pktOut)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (floatingIP.toInt)
        ipPak.getDestinationAddress should be (vm2Ip.toInt)

        log.info("Sending tcp packet from VM2 -> VM1, private ips, no NAT")
        injectTcp(vm2PortName, vm2Mac, vm2Ip, 20301, routerMac2,
                  vm1Ip, 80, syn = true)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getPacket should not be null
        eth = applyOutPacketActions(pktOut)
        log.debug("packet out: {}", pktOut)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (vm2Ip.toInt)
        ipPak.getDestinationAddress should be (vm1Ip.toInt)

        log.info("ICMP echo, VM1 -> VM2, should be SNAT'ed")
        injectIcmpEchoReq(rtrPort1Name, vm1Mac, vm1Ip, routerMac1, vm2Ip)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getPacket should not be null
        eth = applyOutPacketActions(pktOut)
        log.debug("packet out: {}", pktOut)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (floatingIP.toInt)
        ipPak.getDestinationAddress should be (vm2Ip.toInt)

        log.info("ICMP echo, VM2 -> floatingIp, should be DNAT'ed")
        injectIcmpEchoReq(rtrPort1Name, vm2Mac, vm2Ip, routerMac1, floatingIP)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getPacket should not be null
        eth = applyOutPacketActions(pktOut)
        log.debug("packet out: {}", pktOut)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (vm2Ip.toInt)
        ipPak.getDestinationAddress should be (vm1Ip.toInt)

        log.info(
            "ICMP echo, VM1 -> floatingIp, should be DNAT'ed, but not SNAT'ed")
        injectIcmpEchoReq(rtrPort1Name, vm1Mac, vm1Ip, routerMac1, floatingIP)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getPacket should not be null
        eth = applyOutPacketActions(pktOut)
        log.debug("packet out: {}", pktOut)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (vm1Ip.toInt)
        ipPak.getDestinationAddress should be (vm1Ip.toInt)

    }

}
