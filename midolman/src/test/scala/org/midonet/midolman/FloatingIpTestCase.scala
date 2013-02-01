/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman

import rules.{RuleResult, NatTarget, Condition}
import scala.Some
import java.util.{HashSet => JHashSet, UUID}

import akka.testkit.TestProbe
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory
import guice.actors.OutgoingMessage

import org.midonet.midolman.FlowController.{WildcardFlowRemoved,
    WildcardFlowAdded, DiscardPacket}
import layer3.Route
import layer3.Route.NextHop
import topology.LocalPortActive
import org.midonet.packets._
import topology.VirtualToPhysicalMapper.HostRequest
import util.SimulationHelper
import org.midonet.cluster.data.ports.{LogicalRouterPort, MaterializedBridgePort, MaterializedRouterPort}
import util.RouterHelper
import org.midonet.midolman.DatapathController.PacketIn

@RunWith(classOf[JUnitRunner])
class FloatingIpTestCase extends VirtualConfigurationBuilders with RouterHelper {
    private final val log = LoggerFactory.getLogger(classOf[FloatingIpTestCase])

    // Router port one connecting to host VM1
    val routerIp1 = IntIPv4.fromString("192.168.111.1", 24)
    val routerMac1 = MAC.fromString("22:aa:aa:ff:ff:ff")
    // Interior router port connecting to bridge
    val routerIp2 = IntIPv4.fromString("192.168.222.1", 24)
    val routerMac2 = MAC.fromString("22:ab:cd:ff:ff:ff")
    // VM1: remote host to ping
    val vm1Ip = IntIPv4.fromString("192.168.111.2", 24)
    val vm1Mac = MAC.fromString("02:23:24:25:26:27")
    // VM2
    val vm2Ip = IntIPv4.fromString("192.168.222.2", 24)
    val vm2Mac = MAC.fromString("02:DD:AA:DD:AA:03")

    // Other stuff
    var brPort2 : MaterializedBridgePort = null
    val vm2PortName = "VM2"
    var vm2PortNumber = 0
    var rtrPort1 : MaterializedRouterPort = null
    val rtrPort1Name = "RouterPort1"
    var rtrPort1Num = 0
    var rtrPort2 : LogicalRouterPort = null

    val floatingIP = IntIPv4.fromString("10.0.173.5")

    private var flowEventsProbe: TestProbe = null
    private var portEventsProbe: TestProbe = null
    private var packetsEventsProbe: TestProbe = null

    override def beforeTest() {
        flowEventsProbe = newProbe()
        portEventsProbe = newProbe()
        packetsEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowRemoved])
        actors().eventStream.subscribe(portEventsProbe.ref, classOf[LocalPortActive])
        actors().eventStream.subscribe(packetsEventsProbe.ref, classOf[PacketsExecute])

        val host = newHost("myself", hostId())
        host should not be null
        val router = newRouter("router")
        router should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        // set up materialized port on router
        rtrPort1 = newExteriorRouterPort(router, routerMac1,
            routerIp1.toUnicastString,
            routerIp1.toNetworkAddress.toUnicastString,
            routerIp1.getMaskLength)
        rtrPort1 should not be null
        materializePort(rtrPort1, host, rtrPort1Name)
        val portEvent = requestOfType[LocalPortActive](portEventsProbe)
        portEvent.active should be(true)
        portEvent.portID should be(rtrPort1.getId)
        dpController().underlyingActor.vifToLocalPortNumber(rtrPort1.getId) match {
            case Some(portNo : Short) => rtrPort1Num = portNo
            case None => fail("Not able to find data port number for Router port 1")
        }

        newRoute(router, "0.0.0.0", 0,
            routerIp1.toNetworkAddress.toUnicastString, routerIp1.getMaskLength,
            NextHop.PORT, rtrPort1.getId,
            new IntIPv4(Route.NO_GATEWAY).toUnicastString, 10)

        // set up logical port on router
        rtrPort2 = newInteriorRouterPort(router, routerMac2,
            routerIp2.toUnicastString,
            routerIp2.toNetworkAddress.toUnicastString,
            routerIp2.getMaskLength)
        rtrPort2 should not be null

        newRoute(router, "0.0.0.0", 0,
            routerIp2.toNetworkAddress.toUnicastString, routerIp2.getMaskLength,
            NextHop.PORT, rtrPort2.getId,
            new IntIPv4(Route.NO_GATEWAY).toUnicastString, 10)

        // create bridge link to router's logical port
        val bridge = newBridge("bridge")
        bridge should not be null

        val brPort1 = newInteriorBridgePort(bridge)
        brPort1 should not be null
        clusterDataClient().portsLink(rtrPort2.getId, brPort1.getId)

        // add a materialized port on bridge, logically connect to VM2
        brPort2 = newExteriorBridgePort(bridge)
        brPort2 should not be null

        materializePort(brPort2, host, vm2PortName)
        requestOfType[LocalPortActive](portEventsProbe)
        dpController().underlyingActor.vifToLocalPortNumber(brPort2.getId) match {
            case Some(portNo : Short) => vm2PortNumber = portNo
            case None => fail("Not able to find data port number for bridge port 2")
        }

        log.info("Setting up rule chain")

        val preChain = newInboundChainOnRouter("pre_routing", router)
        val postChain = newOutboundChainOnRouter("post_routing", router)

        // DNAT rule
        // assign floatingIP to VM1's private addr
        val dnatCond = new Condition()
        dnatCond.nwDstIp = floatingIP
        val dnatTarget = new NatTarget(vm1Ip.addressAsInt(), vm1Ip.addressAsInt(), 0, 0)
        val dnatRule = newForwardNatRuleOnChain(preChain, 1, dnatCond,
            RuleResult.Action.ACCEPT, Set(dnatTarget), isDnat = true)
        dnatRule should not be null

        // SNAT rules
        // assign floatingIP to VM1's private addr
        val snatCond = new Condition()
        snatCond.nwSrcIp = vm1Ip
        snatCond.outPortIds = new JHashSet[UUID]()
        snatCond.outPortIds.add(rtrPort2.getId)
        val snatTarget = new NatTarget(floatingIP.addressAsInt(), floatingIP.addressAsInt(), 0, 0)
        val snatRule = newForwardNatRuleOnChain(postChain, 1, snatCond,
            RuleResult.Action.ACCEPT, Set(snatTarget), isDnat = false)
        snatRule should not be null

        // TODO needed?
        clusterDataClient().routersUpdate(router)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        drainProbes()

    }

    def test() {

        log.info("Feeding ARP cache on VM2")
        feedArpCache(vm2PortName, vm2Ip.addressAsInt, vm2Mac,
            routerIp2.addressAsInt, routerMac2)
        requestOfType[PacketIn](simProbe())
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()

        log.info("Feeding ARP cache on VM1")
        feedArpCache(rtrPort1Name, vm1Ip.addressAsInt, vm1Mac,
            routerIp1.addressAsInt, routerMac1)
        requestOfType[PacketIn](simProbe())
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()

        log.info("Sending a tcp packet VM2 -> floating IP, should be DNAT'ed")
        injectTcp(vm2PortName, vm2Mac, vm2Ip, 20301, routerMac2, floatingIP, 80,
            syn = true)
        var pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        var eth = applyOutPacketActions(pktOut)
        log.debug("Packet out: {}", pktOut)
        var ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (vm2Ip.addressAsInt)
        ipPak.getDestinationAddress should be (vm1Ip.addressAsInt)

        log.info("Replying with tcp packet floatingIP -> VM2, should be SNAT'ed")
        injectTcp(rtrPort1Name, vm1Mac, vm1Ip, 20301, routerMac1, vm2Ip, 80,
            syn = true)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        eth = applyOutPacketActions(pktOut)
        log.debug("packet out: {}", pktOut)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (floatingIP.addressAsInt)
        ipPak.getDestinationAddress should be (vm2Ip.addressAsInt)

        log.info("Sending tcp packet from VM2 -> VM1, private ips, no NAT")
        injectTcp(vm2PortName, vm2Mac, vm2Ip, 20301, routerMac2, vm1Ip, 80,
            syn = true)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        eth = applyOutPacketActions(pktOut)
        log.debug("packet out: {}", pktOut)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (vm2Ip.addressAsInt)
        ipPak.getDestinationAddress should be (vm1Ip.addressAsInt)

        log.info("ICMP echo, VM1 -> VM2, should be SNAT'ed")
        injectIcmpEcho(rtrPort1Name, vm1Mac, vm1Ip, routerMac1, vm2Ip)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        eth = applyOutPacketActions(pktOut)
        log.debug("packet out: {}", pktOut)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (floatingIP.addressAsInt)
        ipPak.getDestinationAddress should be (vm2Ip.addressAsInt)

        log.info("ICMP echo, VM2 -> floatingIp, should be DNAT'ed")
        injectIcmpEcho(rtrPort1Name, vm2Mac, vm2Ip, routerMac1, floatingIP)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        eth = applyOutPacketActions(pktOut)
        log.debug("packet out: {}", pktOut)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (vm2Ip.addressAsInt)
        ipPak.getDestinationAddress should be (vm1Ip.addressAsInt)

        log.info("ICMP echo, VM1 -> floatingIp, should be DNAT'ed, but not SNAT'ed")
        injectIcmpEcho(rtrPort1Name, vm1Mac, vm1Ip, routerMac1, floatingIP)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        eth = applyOutPacketActions(pktOut)
        log.debug("packet out: {}", pktOut)
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getSourceAddress should be (vm1Ip.addressAsInt)
        ipPak.getDestinationAddress should be (vm1Ip.addressAsInt)

    }

}
