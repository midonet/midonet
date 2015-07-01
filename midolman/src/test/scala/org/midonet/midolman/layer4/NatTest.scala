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

package org.midonet.midolman.layer4

import java.nio.ByteBuffer
import java.{util => ju}
import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.duration._

import akka.testkit.TestActorRef
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Chain, Router}
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.midolman._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.{Condition, NatTarget, RuleResult}
import org.midonet.midolman.simulation.{Bridge => SimBridge}
import org.midonet.midolman.simulation.{Router => SimRouter}
import org.midonet.midolman.state.FlowState
import org.midonet.midolman.state.NatState
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.{FlowMatches, Packet}
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.state.ShardedFlowStateTable
import org.midonet.util.collection.Reducer

@RunWith(classOf[JUnitRunner])
class NatTest extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var portMap = Map[Int, UUID]()
    val vmNetworkIp = new IPv4Subnet("10.0.0.0", 24)
    val routerIp = new IPv4Subnet("10.0.0.254", 24)
    val routerMac = MAC.fromString("22:aa:aa:ff:ff:ff")

    val vmPortNames = IndexedSeq("port0", "port1", "port2", "port3", "port4")
    var vmPorts: IndexedSeq[BridgePort] = null

    var vmPortNumbers: IndexedSeq[Int] = null
    val vmMacs = IndexedSeq(MAC.fromString("02:aa:bb:cc:dd:d1"),
        MAC.fromString("02:aa:bb:cc:dd:d2"),
        MAC.fromString("02:aa:bb:cc:dd:d3"),
        MAC.fromString("02:aa:bb:cc:dd:d4"),
        MAC.fromString("02:aa:bb:cc:dd:d5"))
    val vmIps = IndexedSeq(IPv4Addr.fromString("10.0.0.1"),
        IPv4Addr.fromString("10.0.0.2"),
        IPv4Addr.fromString("10.0.0.3"),
        IPv4Addr.fromString("10.0.0.4"),
        IPv4Addr.fromString("10.0.0.5"))
    val v6VmIps = IndexedSeq(
        IPv6Addr.fromString("fe80:0:0:0:0:7ed1:c3ff:1"),
        IPv6Addr.fromString("fe80:0:0:0:0:7ed1:c3ff:2"),
        IPv6Addr.fromString("fe80:0:0:0:0:7ed1:c3ff:3"),
        IPv6Addr.fromString("fe80:0:0:0:0:7ed1:c3ff:4"),
        IPv6Addr.fromString("fe80:0:0:0:0:7ed1:c3ff:5"))

    var bridge: UUID = null
    var router: Router = null
    var host: UUID = null

    private val uplinkGatewayAddr = IPv4Addr("180.0.1.1")
    private val uplinkGatewayMac: MAC = "02:0b:09:07:05:03"
    private val uplinkNwAddr = new IPv4Subnet("180.0.1.0", 24)
    private val uplinkPortAddr = IPv4Addr("180.0.1.2")
    private val uplinkPortMac: MAC = "02:0a:08:06:04:02"
    private var uplinkPort: RouterPort = null

    private val dnatAddress = IPv4Addr("180.0.1.100")
    private val snatAddressStart = IPv4Addr("180.0.1.200")
    private val snatAddressEnd = IPv4Addr("180.0.1.205")

    private val natTable = new ShardedFlowStateTable[NatKey, NatBinding](clock).addShard()
    private var mappings = Set[NatKey]()

    private var rtrOutChain: Chain = null
    private var rtrInChain: Chain = null

    private var pktWkfl: TestActorRef[PacketWorkflow] = null
    private val packetOutQueue: ju.Queue[(Packet, ju.List[FlowAction])] =
        new ju.LinkedList[(Packet, ju.List[FlowAction])]

    private class Mapping(val key: NatKey,
        val fwdInPort: UUID, val fwdOutPort: UUID,
        val fwdInPacket: Ethernet, val fwdOutPacket: Ethernet) {

        def flowCount: Int = natTable.getRefCount(key)
        def revInPort: UUID = fwdOutPort
        def revOutPort: UUID = fwdInPort

        def fwdInFromMac: MAC = fwdInPacket.getSourceMACAddress
        def fwdInToMac: MAC = fwdInPacket.getDestinationMACAddress

        def revOutFromMac: MAC = fwdInToMac
        def revOutToMac: MAC = fwdInFromMac

        def fwdOutFromMac: MAC = fwdOutPacket.getSourceMACAddress
        def fwdOutToMac: MAC = fwdOutPacket.getDestinationMACAddress

        def revInFromMac: MAC = fwdOutToMac
        def revInToMac: MAC = fwdOutFromMac

        def fwdInFromIp: IPv4Addr = new IPv4Addr(
                fwdInPacket.getPayload.asInstanceOf[IPv4].getSourceAddress)
        def fwdInToIp: IPv4Addr = new IPv4Addr(
                fwdInPacket.getPayload.asInstanceOf[IPv4].getDestinationAddress)

        def revOutFromIp: IPv4Addr = fwdInToIp
        def revOutToIp: IPv4Addr = fwdInFromIp

        def fwdOutFromIp: IPv4Addr = new IPv4Addr(
                fwdOutPacket.getPayload.asInstanceOf[IPv4].getSourceAddress)
        def fwdOutToIp: IPv4Addr = new IPv4Addr(
                fwdOutPacket.getPayload.asInstanceOf[IPv4].getDestinationAddress)

        def revInFromIp: IPv4Addr = fwdOutToIp
        def revInToIp: IPv4Addr = fwdOutFromIp

        def fwdInFromPort: Short =
            fwdInPacket.getPayload.asInstanceOf[IPv4].
                        getPayload.asInstanceOf[TCP].getSourcePort.toShort
        def fwdInToPort: Short =
            fwdInPacket.getPayload.asInstanceOf[IPv4].
                        getPayload.asInstanceOf[TCP].getDestinationPort.toShort

        def revOutFromPort: Short = fwdInToPort
        def revOutToPort: Short = fwdInFromPort

        def fwdOutFromPort: Short =
            fwdOutPacket.getPayload.asInstanceOf[IPv4].
                         getPayload.asInstanceOf[TCP].getSourcePort.toShort
        def fwdOutToPort: Short =
            fwdOutPacket.getPayload.asInstanceOf[IPv4].
                         getPayload.asInstanceOf[TCP].getDestinationPort.toShort

        def revInFromPort: Short = fwdOutToPort
        def revInToPort: Short = fwdOutFromPort

        def matchForwardOutPacket(eth: Ethernet) {
            val ip = eth.getPayload.asInstanceOf[IPv4]
            val tcp = ip.getPayload.asInstanceOf[TCP]
            eth.getSourceMACAddress should be (fwdOutFromMac)
            eth.getDestinationMACAddress should be (fwdOutToMac)
            IPv4Addr(ip.getSourceAddress) should be (fwdOutFromIp)
            IPv4Addr(ip.getDestinationAddress) should be (fwdOutToIp)
            tcp.getSourcePort should be (fwdOutFromPort)
            tcp.getDestinationPort should be (fwdOutToPort)
        }

        def matchReturnOutPacket(eth: Ethernet) {
            val ip = eth.getPayload.asInstanceOf[IPv4]
            val tcp = ip.getPayload.asInstanceOf[TCP]
            eth.getSourceMACAddress should be (revOutFromMac)
            eth.getDestinationMACAddress should be (revOutToMac)
            IPv4Addr(ip.getSourceAddress) should be (revOutFromIp)
            IPv4Addr(ip.getDestinationAddress) should be (revOutToIp)
            tcp.getSourcePort should be (revOutFromPort)
            tcp.getDestinationPort should be (revOutToPort)
        }
    }

    override def beforeTest(): Unit = {
        var portIdCounter = 1
        host = newHost("myself", hostId)
        host should not be null
        router = newRouter("router")
        router should not be null

        val rtrPort = newRouterPort(router, routerMac,
            routerIp.toUnicastString, routerIp.toNetworkAddress.toString,
            routerIp.getPrefixLen)
        rtrPort should not be null

        newRoute(router, "0.0.0.0", 0,
            routerIp.toNetworkAddress.toString, routerIp.getPrefixLen,
            NextHop.PORT, rtrPort.getId,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        bridge = newBridge("bridge")
        bridge should not be null

        val brPort = newBridgePort(bridge)
        brPort should not be null
        clusterDataClient.portsLink(rtrPort.getId, brPort.getId)

        vmPorts = vmPortNames map { _ => newBridgePort(bridge) }
        vmPorts zip vmPortNames foreach {
            case (port, name) =>
                log.debug("Materializing port {}", name)
                materializePort(port, host, name)
                portMap += portIdCounter -> port.getId
                portIdCounter += 1
        }

        uplinkPort = newRouterPort(
            router, uplinkPortMac, uplinkPortAddr.toString,
            uplinkNwAddr.getAddress.toString, uplinkNwAddr.getPrefixLen)
        uplinkPort should not be null
        materializePort(uplinkPort, host, "uplinkPort")
        portMap += portIdCounter -> uplinkPort.getId
        portIdCounter += 1

        var route = newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0,
            NextHop.PORT, uplinkPort.getId, uplinkGatewayAddr.toString, 1)
        route should not be null

        route = newRoute(router, "0.0.0.0", 0,
            uplinkNwAddr.getAddress.toString, uplinkNwAddr.getPrefixLen,
            NextHop.PORT, uplinkPort.getId, intToIp(Route.NO_GATEWAY), 10)
        route should not be null

        // create NAT rules
        log.info("creating chains")
        rtrOutChain = newOutboundChainOnRouter("rtrOutChain", router)
        rtrInChain = newInboundChainOnRouter("rtrInChain", router)

        // 0.- Reverse NAT rules
        val revDnatRule = newReverseNatRuleOnChain(rtrOutChain, 1,
            new Condition(), RuleResult.Action.CONTINUE, isDnat = true)
        revDnatRule should not be null

        val revSnatRule = newReverseNatRuleOnChain(rtrInChain, 1,
            new Condition(), RuleResult.Action.CONTINUE, isDnat = false)
        revSnatRule should not be null

        // 1.- DNAT -> from !vm-network to dnatAddress --> dnat(vm-network)
        log.info("adding DNAT rule")
        val dnatCond = new Condition()
        dnatCond.nwSrcIp = new IPv4Subnet(
                           IPv4Addr.fromString(vmNetworkIp.toUnicastString),
                           vmNetworkIp.getPrefixLen)
        dnatCond.nwSrcInv = true
        dnatCond.nwDstIp = new IPv4Subnet(dnatAddress, 32)

        /*
         * (Galo): removed this since it prevents matching on ICMP packets for
         * testDnatPing
         * dnatCond.tpDstStart = 80.toInt
         * dnatCond.tpDstEnd = 80.toInt
         */
        val dnatTarget =  new NatTarget(vmIps.head, vmIps.last, 80, 80)
        val dnatRule = newForwardNatRuleOnChain(rtrInChain, 2, dnatCond,
                RuleResult.Action.CONTINUE, Set(dnatTarget), isDnat = true)
        dnatRule should not be null

        // 2.- SNAT -> from vm-network to !vm-network  dstPort=22
        //          --> snat(uplinkPortAddr)
        log.info("adding SNAT rule")
        val snatCond = new Condition()
        snatCond.nwSrcIp = new IPv4Subnet(
                           IPv4Addr.fromString(vmNetworkIp.toUnicastString), 24)
        snatCond.nwDstIp = new IPv4Subnet(
                           IPv4Addr.fromString(vmNetworkIp.toUnicastString), 24)
        snatCond.nwDstInv = true
        // FIXME(guillermo) why does a port range of 0:0 allow 0 to
        // be picked as source port??
        val snatTarget = new NatTarget(snatAddressStart, snatAddressEnd,
                                       10001, 65535)
        val snatRule = newForwardNatRuleOnChain(rtrOutChain, 2, snatCond,
                RuleResult.Action.CONTINUE, Set(snatTarget), isDnat = false)
        snatRule should not be null

        clusterDataClient.routersUpdate(router)

        val simRouter: SimRouter = fetchDevice(router)
        val simBridge: SimBridge = fetchBridge(bridge)

        // feed the router arp cache with the uplink gateway's mac address
        feedArpTable(simRouter, uplinkGatewayAddr.addr, uplinkGatewayMac)
        feedArpTable(simRouter, uplinkPortAddr.addr, uplinkPortMac)
        feedArpTable(simRouter, routerIp.getAddress.addr, routerMac)

        // feed the router's arp cache with each of the vm's addresses
        (vmPortNames, vmMacs, vmIps).zipped foreach {
            (name, mac, ip) => feedArpTable(simRouter, ip.addr, mac)
        }
        (vmMacs, vmPorts).zipped foreach {
            (mac, port) => feedMacTable(simBridge, mac, port.getId)
        }

        fetchBridge(bridge)
        fetchTopology(router, rtrPort, rtrInChain,
                      rtrOutChain, uplinkPort)
        fetchTopology(vmPorts:_*)

        pktWkfl = packetWorkflow(portMap, natTable = natTable)

        mockDpChannel.packetsExecuteSubscribe(
            (packet, actions) => packetOutQueue.add((packet,actions)) )
    }

    scenario("connection tracking") {
        log.info("creating chains")
        val brChain = newInboundChainOnBridge("brChain", bridge)
        brChain should not be null

        val vmPortIds: ju.Set[UUID] = new ju.HashSet[UUID]()
        vmPortIds ++= vmPorts.map { p => p.getId }.toSet

        val returnCond = new Condition()
        returnCond.matchReturnFlow = true
        returnCond.inPortIds = vmPortIds
        returnCond.inPortInv = true
        val returnRule = newLiteralRuleOnChain(
            brChain, 1, returnCond, RuleResult.Action.ACCEPT)
        returnRule should not be null

        val dropIncomingCond = new Condition()
        dropIncomingCond.inPortIds = vmPortIds
        dropIncomingCond.inPortInv = true
        val dropIncomingRule = newLiteralRuleOnChain(
            brChain, 2, dropIncomingCond, RuleResult.Action.DROP)
        dropIncomingRule should not be null

        val forwardCond = new Condition()
        forwardCond.inPortIds = vmPortIds
        forwardCond.matchForwardFlow = true
        val forwardRule = newLiteralRuleOnChain(
            brChain, 3, forwardCond, RuleResult.Action.ACCEPT)
        forwardRule should not be null

        testSnat()
    }

    scenario("destination NAT") {
        log.info("Sending a tcp packet that should be DNAT'ed")

        injectTcp(uplinkPort.getId, uplinkGatewayMac, IPv4Addr("62.72.82.1"),
                  20301, uplinkPortMac, dnatAddress, 80)

        val newMappings = updateAndDiffMappings()
        newMappings.size should be (1)
        natTable.getRefCount(newMappings.head) should be (1)
        packetOutQueue.size should be (1)
        val (packet, actions) = packetOutQueue.remove()
        val outPorts = getOutPorts(actions)
        outPorts.size() should be (1)

        val mapping = new Mapping(newMappings.head,
                                  uplinkPort.getId, portMap(outPorts(0)),
                                  packet.getEthernet,
                                  applyPacketActions(packet.getEthernet,
                                                        actions))

        log.info("Sending a return tcp packet")
        injectTcp(mapping.revInPort,
                  mapping.revInFromMac, mapping revInFromIp,
                  mapping.revInFromPort, mapping.revInToMac,
                  mapping.revInToIp, mapping.revInToPort,
                  ack = true, syn = true)

        updateAndDiffMappings().size should be (0)
        packetOutQueue.size should be (1)
        val (packet2, actions2) = packetOutQueue.remove()
        val outPorts2 = getOutPorts(actions2)
        outPorts2.size should be (1)
        portMap(outPorts2(0)) should be (mapping.fwdInPort)

        mapping.matchReturnOutPacket(
            applyPacketActions(packet2.getEthernet, actions2))
        mapping.flowCount should be (1)

        log.debug("sending a second forward packet, from a different router")
        injectTcp(uplinkPort.getId,
                  MAC.fromString("02:99:99:77:77:77"),
                  IPv4Addr("62.72.82.1"), 20301,
                  uplinkPortMac, dnatAddress, 80, ack = true)
        updateAndDiffMappings().size should be (0)
        packetOutQueue.size should be (1)
        val (packet3, actions3) = packetOutQueue.remove()
        val outPorts3 = getOutPorts(actions3)
        outPorts3.size should be (1)
        portMap(outPorts3(0)) should be (mapping.fwdOutPort)
        mapping.matchForwardOutPacket(
            applyPacketActions(packet3.getEthernet, actions3))
        mapping.flowCount should be (2)

        simBackChannel.tell(FlowTagger.tagForDevice(router.getId))
        pktWkfl.underlyingActor.process()
        mapping.flowCount should be (0)
        clock.time = FlowState.DEFAULT_EXPIRATION.toNanos + 1
        natTable.expireIdleEntries()
        fwdKeys().size should be (0)
    }

    scenario("source nat") {
        testSnat()
    }

    def testSnat() {
        log.info("Sending a tcp packet that should be SNAT'ed")
        injectTcp(vmPorts.head.getId, vmMacs.head, vmIps.head, 30501,
            routerMac, IPv4Addr("62.72.82.1"), 22,
            syn = true)
        val newMappings: Set[NatKey] = updateAndDiffMappings()
        newMappings.size should be (1)
        natTable.getRefCount(newMappings.head) should be (1)
        packetOutQueue.size should be (1)
        val (packet, actions) = packetOutQueue.remove()
        val outPorts = getOutPorts(actions)
        outPorts.size should be (1)
        portMap(outPorts(0)) should be (uplinkPort.getId)

        val mapping = new Mapping(newMappings.head,
            vmPorts.head.getId, uplinkPort.getId,
            packet.getEthernet,
            applyPacketActions(packet.getEthernet, actions))

        log.info("Sending a return tcp packet")
        injectTcp(mapping.revInPort,
            mapping.revInFromMac, mapping.revInFromIp, mapping.revInFromPort,
            mapping.revInToMac, mapping.revInToIp, mapping.revInToPort,
            ack = true, syn = true)
        updateAndDiffMappings().size should be (0)
        packetOutQueue.size should be (1)
        val (packet2, actions2) = packetOutQueue.remove()
        val outPorts2 = getOutPorts(actions2)
        outPorts2.size should be (1)
        portMap(outPorts2(0)) should be (mapping.fwdInPort)
        mapping.matchReturnOutPacket(
            applyPacketActions(packet2.getEthernet, actions2))
        mapping.flowCount should be (1)

        log.debug("sending a second forward packet,"
                      + " from a different network card")
        injectTcp(vmPorts.head.getId, "02:34:34:43:43:20", vmIps.head, 30501,
            routerMac, IPv4Addr("62.72.82.1"), 22,
            ack = true)
        updateAndDiffMappings().size should be (0)
        packetOutQueue.size should be (1)
        val (packet3, actions3) = packetOutQueue.remove()
        val outPorts3 = getOutPorts(actions3)
        outPorts3.size should be (1)
        portMap(outPorts3(0)) should be (mapping.fwdOutPort)
        mapping.matchForwardOutPacket(
            applyPacketActions(packet3.getEthernet, actions3))
        mapping.flowCount should be (2)

        simBackChannel.tell(FlowTagger.tagForDevice(router.getId))
        pktWkfl.underlyingActor.process()
        mapping.flowCount should be (0)
        clock.time = FlowState.DEFAULT_EXPIRATION.toNanos + 1
        natTable.expireIdleEntries()
        fwdKeys().size should be (0)
    }

    // -----------------------------------------------
    // -----------------------------------------------
    //             ICMP over NAT tests
    // -----------------------------------------------
    // -----------------------------------------------

    /**
     * See issue #435
     *
     * Two hosts from a private network send pings that should be SNAT'ed,
     * we check that the router can send each reply back to the correct host.
     */
    scenario("snat ping") {

        val vm1Port = vmPorts.head.getId
        val vm5Port = vmPorts.last.getId
        val upPort = uplinkPort.getId
        val src1Mac = vmMacs.head // VM inside the private network
        val src1Ip = vmIps.last
        val src5Mac = vmMacs.last // VM inside the private network
        val src5Ip = vmIps.last
        val dstMac = routerMac   // A destination beyond the gateway
        val dstIp = IPv4Addr("62.72.82.1")

        // translated source is now random

        log.info("Sending ICMP pings into private network")
        val snatIp16 = pingExpectSnated(vm1Port, src1Mac, src1Ip, dstMac, dstIp,
                                        16, 1, snatAddressStart, snatAddressEnd)
        val snatIp21 = pingExpectSnated(vm5Port, src5Mac, src5Ip, dstMac, dstIp,
                                        21, 1, snatAddressStart, snatAddressEnd)
        // TODO (galo) test that should
        log.info("Sending ICMP pings, expect userspace match")
        val snatIp16_2 = pingExpectSnated(vm1Port, src1Mac, src1Ip, dstMac,
                                          dstIp, 16, 1, snatAddressStart,
                                          snatAddressEnd)
        val snatIp21_2 = pingExpectSnated(vm5Port, src5Mac, src5Ip, dstMac,
                                          dstIp, 21, 1, snatAddressStart,
                                          snatAddressEnd)

        snatIp16 shouldBe snatIp16_2
        snatIp21 shouldBe snatIp21_2

        log.info("Sending ICMP replies")
        // Let's see if replies get into the private network
        pongExpectRevNatd(upPort, uplinkGatewayMac, dstIp, uplinkPortMac,
                          snatIp16, 16, 1, src1Ip)
        pongExpectRevNatd(upPort, uplinkGatewayMac, dstIp, uplinkPortMac,
                          snatIp21, 21, 1, src5Ip)
        pongExpectRevNatd(upPort, uplinkGatewayMac, dstIp, uplinkPortMac,
                          snatIp16, 16, 2, src1Ip)
        pongExpectRevNatd(upPort, uplinkGatewayMac, dstIp, uplinkPortMac,
                          snatIp21, 21, 2, src5Ip)

    }

    /**
     * See issue #435
     *
     * A VM sends a ping request that should be DNAT'ed, and we check that the
     * reply gets back correctly.
     */
    scenario("dnat ping") {
        val srcIp = IPv4Addr("62.72.82.1")
        val mp = pingExpectDnated(uplinkPort.getId, uplinkGatewayMac, srcIp,
                                  uplinkPortMac, dnatAddress, 92, 1)
        pongExpectRevDnated(mp.revInPort, mp.revInFromMac, mp.revInFromIp,
                            mp.revInToMac, mp.revInToIp, srcIp, 92, 1)
    }

    /**
     * See issue #513
     *
     * A VM sends a TCP packet that should be SNAT'ed, an ICMP error is sent
     * back from the destination, we expect it to get to the VM
     */
    scenario("Snat ICMP error after TCP") {
        val dstIp = IPv4Addr("62.72.82.1")
        log.info("Sending a TCP packet that should be SNAT'ed")
        injectTcp(vmPorts.head.getId, vmMacs.head, vmIps.head, 5555,
            routerMac, dstIp, 1111, syn = true)
        verifySnatICMPErrorAfterPacket(userspace = false)
    }

    /**
     * See issue #513
     *
     * A TCP packet is sent to a VM that should be DNAT'ed, the VM replies with
     * an ICMP error is sent, we expect it to get back
     */
    scenario("Dnat ICMP error after TCP") {
        val srcIp = IPv4Addr("62.72.82.1")
        log.info("Sending a TCP packet that should be DNAT'ed")
        injectTcp(uplinkPort.getId, uplinkGatewayMac, srcIp, 888,
            uplinkPortMac, dnatAddress, 333, syn = true)
        verifyDnatICMPErrorAfterPacket(userspace = false)
    }

    /**
     * See issue #513
     *
     * ICMP packet is sent to a VM that should be SNAT'ed, the VM replies with
     * an ICMP error is sent, we expect it to get back
     */
    scenario("Snat ICMP error after ICMP") {
        val dstIp = IPv4Addr("62.72.82.1")
        log.info("Sending a TCP packet that should be SNAT'ed")
        injectIcmpEchoReq(vmPorts.head.getId, vmMacs.head,
                          vmIps.head, routerMac, dstIp, 22, 55)
        verifySnatICMPErrorAfterPacket(userspace = true)
    }

    /**
     * See issue #513
     *
     * ICMP packet is sent to a VM that should be DNAT'ed, the VM replies with
     * an ICMP error is sent, we expect it to get back
     */
    scenario("Dnat ICMP error after ICMP") {
        val srcIp = IPv4Addr("62.72.82.1")
        log.info("Sending a TCP packet that should be DNAT'ed")
        injectIcmpEchoReq(uplinkPort.getId, uplinkGatewayMac, srcIp,
            uplinkPortMac, dnatAddress, 7, 6)
        verifyDnatICMPErrorAfterPacket(userspace = true)
    }

    def fwdKeys(): Set[NatKey] = {
        val reducer =  new Reducer[NatKey, NatBinding, Set[NatKey]] {
            def apply(acc: Set[NatKey], key: NatKey,
                      value: NatBinding): Set[NatKey] =
                key.keyType match {
                    case NatState.FWD_SNAT | NatState.FWD_DNAT |
                            NatState.FWD_STICKY_DNAT =>
                        key.expiresAfter = 0.seconds
                        acc + key
                    case _ =>
                        acc
                }
        }
        natTable.fold(Set.empty[NatKey], reducer).toSet
    }

    def updateAndDiffMappings(): Set[NatKey] = {
        val oldMappings = mappings
        mappings = fwdKeys()
        mappings -- oldMappings
    }

    def inject(inPort: UUID, frame: Ethernet): Unit = {
        val inPortNum = portMap.map(_.swap).toMap.apply(inPort)
        val packets = List(new Packet(frame,
                                      FlowMatches.fromEthernetPacket(frame)
                                          .addKey(FlowKeys.inPort(inPortNum))
                                          .setInputPortNumber(inPortNum)))
        pktWkfl ! PacketWorkflow.HandlePackets(packets.toArray)
    }

    def injectTcp(inPort: UUID, srcMac: MAC, srcIp: IPv4Addr, srcPort: Short,
                  dstMac: MAC, dstIp: IPv4Addr, dstPort: Short,
                  ack: Boolean = false, syn: Boolean = false): Ethernet = {
        val flagList = new ju.ArrayList[TCP.Flag]
        if (ack) { flagList.add(TCP.Flag.Ack) }
        if (syn) { flagList.add(TCP.Flag.Syn) }

        val frame = { eth src srcMac dst dstMac } <<
            { ip4 src srcIp dst dstIp } <<
            { tcp src srcPort dst dstPort flags TCP.Flag.allOf(flagList) } <<
            payload("foobar")
        inject(inPort, frame)
        frame
    }

    def injectIcmpEchoReq(inPort: UUID, srcMac: MAC, srcIp: IPv4Addr,
                          dstMac: MAC, dstIp: IPv4Addr,
                          icmpId: Short, icmpSeq: Short): Ethernet = {
        val frame = { eth src srcMac dst dstMac } <<
            { ip4 src srcIp dst dstIp } <<
            { icmp.echo.request.id(icmpId).seq(icmpSeq) }
        inject(inPort, frame)
        frame
    }

    def injectIcmpEchoReply(inPort: UUID, srcMac: MAC, srcIp: IPv4Addr,
                            dstMac: MAC, dstIp: IPv4Addr,
                            icmpId: Short, icmpSeq: Short): Ethernet = {
        val frame = { eth src srcMac dst dstMac } <<
            { ip4 src srcIp dst dstIp } <<
            { icmp.echo.reply.id(icmpId).seq(icmpSeq) }
        inject(inPort, frame)
        frame
    }

    def injectIcmpUnreachable(inPort: UUID, srcMac: MAC, dstMac: MAC,
                              origEth: Ethernet): Ethernet = {
        val origIp = origEth.getPayload.asInstanceOf[IPv4]
        val frame = { eth src srcMac dst dstMac } <<
            { ip4.src(origIp.getDestinationIPAddress)
                .dst(origIp.getSourceIPAddress) } <<
            { icmp.unreach culprit origIp host}
        inject(inPort, frame)
        frame
    }

    def getOutPorts(actions: ju.List[FlowAction]): ju.List[Int] = {
        val ports = new ju.ArrayList[Int]()
        actions.asScala.foreach {
            case o: FlowActionOutput =>
                ports.add(o.getPortNumber())
            case _ =>
        }
        ports
    }

    private def pingExpectDnated(inPort: UUID,
                                 srcMac: MAC, srcIp: IPv4Addr,
                                 dstMac: MAC, dstIp: IPv4Addr,
                                 icmpId: Short, icmpSeq: Short): Mapping = {
        log.info("Sending a PING that should be DNAT'ed")
        val icmpReq = injectIcmpEchoReq(inPort,
                                        srcMac, srcIp,
                                        dstMac, dstIp, icmpId, icmpSeq)
            .getPayload.asInstanceOf[IPv4]
            .getPayload.asInstanceOf[ICMP]

        packetOutQueue.size should be (1)
        val (packet, actions) = packetOutQueue.remove()
        val outPorts = getOutPorts(actions)
        outPorts.size should be (1)

        val newMappings = updateAndDiffMappings()
        newMappings.size should be (1)
        // 0 because it's a userspace flow
        natTable.getRefCount(newMappings.head) should be (0)

        val eth = applyPacketActions(packet.getEthernet, actions)
        val mapping = new Mapping(newMappings.head,
            inPort, portMap(outPorts(0)),
            packet.getEthernet,
            eth)

        val ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPak.getSourceAddress should be (srcIp.addr)
        ipPak.getDestinationAddress should be (mapping.fwdOutToIp.addr)

        val icmpPak = ipPak.getPayload.asInstanceOf[ICMP]
        icmpPak should not be null
        icmpPak.getType should be (ICMP.TYPE_ECHO_REQUEST)
        icmpPak.getIdentifier should be (icmpReq.getIdentifier)
        icmpPak.getQuench should be (icmpReq.getQuench)

        mapping
    }

    private def pongExpectRevDnated(inPort: UUID,
                                    srcMac: MAC, srcIp: IPv4Addr,
                                    dstMac: MAC, dstIp: IPv4Addr,
                                    origSrcIp: IPv4Addr, icmpId: Short,
                                    icmpSeq: Short): Unit = {

        log.info("Send ICMP reply into the router, should be revSnat'ed")
        val icmpReply = injectIcmpEchoReply(inPort, srcMac, srcIp,
                                            dstMac, dstIp, icmpId, icmpSeq)
            .getPayload.asInstanceOf[IPv4]
            .getPayload.asInstanceOf[ICMP]

        // Expect the packet delivered back to the source, MAC should be learnt
        // already so expect straight on the bridge's port
        packetOutQueue.size should be (1)
        val (packet, actions) = packetOutQueue.remove()
        val outPorts = getOutPorts(actions)
        outPorts.size should be (1)

        portMap(outPorts(0)) should be (uplinkPort.getId)

        val eth = applyPacketActions(packet.getEthernet, actions)
        val ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPak.getSourceAddress should be (dnatAddress.addr)
        ipPak.getDestinationAddress should be (origSrcIp.addr)
        val icmpPak = ipPak.getPayload.asInstanceOf[ICMP]
        icmpPak should not be null
        icmpPak.getType should be (ICMP.TYPE_ECHO_REPLY)
        icmpPak.getIdentifier should be (icmpReply.getIdentifier)
    }

    /**
     * Assuming that expectIcmpData was sent, this method will expect an
     * ICMP UNREACHABLE HOST back on the given port, containing in its data
     * the expectIcmpData
     */
    private def expectICMPError(port: UUID, expectIcmpData: IPv4): Unit = {
        // we should have a packet coming out of the expected port
        packetOutQueue.size should be (1)
        val (packet, actions) = packetOutQueue.remove()
        val outPorts = getOutPorts(actions)
        outPorts.size should be (1)
        portMap(outPorts(0)) should be (port)

        // the ip packet should be addressed as expected
        val ethApplied = applyPacketActions(packet.getEthernet, actions)
        val ipv4 = ethApplied.getPayload.asInstanceOf[IPv4]

        log.debug("Received reply {}", packet)
        ipv4 should not be null
        ipv4.getSourceAddress shouldBe expectIcmpData.getDestinationAddress
        ipv4.getDestinationAddress shouldBe expectIcmpData.getSourceAddress
        // The ICMP contents should be as expected
        val icmp = ipv4.getPayload.asInstanceOf[ICMP]
        icmp should not be null
        icmp.getType should be (ICMP.TYPE_UNREACH)
        icmp.getCode should be (ICMP.UNREACH_CODE.UNREACH_HOST.toByte)

        // Now we need to look inside the ICMP data that not only should
        // bring the data contained in the original packet that this ICMP is
        // replying to, but also should do NAT on the L3 and L4 data contained
        // in it
        val icmpData = ByteBuffer.wrap(icmp.getData)
        val actualIcmpData = new IPv4()
        actualIcmpData.deserializeHeader(icmpData)
        actualIcmpData.getProtocol should be (expectIcmpData.getProtocol)
        actualIcmpData.getSourceAddress should be (
            expectIcmpData.getSourceAddress)
        actualIcmpData.getDestinationAddress should be (
            expectIcmpData.getDestinationAddress)
        actualIcmpData.getTtl should be (expectIcmpData.getTtl - 1)

        // The parts of the TCP payload inside the ICMP error
        expectIcmpData.getProtocol match {
            case ICMP.PROTOCOL_NUMBER =>
                val icmpPayload = expectIcmpData.getPayload
                val expect = ju.Arrays.copyOfRange(
                    icmpPayload.serialize(), 0, 8)
                val actual = ju.Arrays.copyOfRange(
                    icmpPayload.serialize(), 0, 8)
                expect should be (actual)
            case TCP.PROTOCOL_NUMBER =>
                val tcpPayload = expectIcmpData.getPayload.asInstanceOf[TCP]
                tcpPayload.getSourcePort should be (icmpData.getShort)
                tcpPayload.getDestinationPort should be (icmpData.getShort)
                tcpPayload.getSeqNo should be (icmpData.getShort)
                tcpPayload.getAckNo should be (icmpData.getShort)
        }
    }

    /**
     * Pings from src (assumed a VM in the private network) to a dst (assumed
     * a host beyond the gateway). Expects that the router outputs the packet
     * on the gateway link, with the srcIp translated
     */
    private def pingExpectSnated(port: UUID, srcMac: MAC, srcIp: IPv4Addr,
                                 dstMac: MAC, dstIp: IPv4Addr,
                                 icmpId: Short, icmpSeq: Short,
                                 transSrcIpStart: IPv4Addr,
                                 transSrcIpEnd: IPv4Addr): IPv4Addr = {

        log.info("Sending a PING that should be NAT'ed")
        val icmpReq = injectIcmpEchoReq(port,
                        srcMac, srcIp,
                        dstMac, dstIp,
                        icmpId, icmpSeq)
            .getPayload.asInstanceOf[IPv4]
            .getPayload.asInstanceOf[ICMP]

        packetOutQueue.size should be (1)
        val (packet, actions) = packetOutQueue.remove()
        val outPorts = getOutPorts(actions)
        outPorts.size should be (1)

        portMap(outPorts(0)) should be (uplinkPort.getId)

        val eth = applyPacketActions(packet.getEthernet, actions)
        var ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPak.getDestinationAddress shouldBe dstIp.addr

        val snatedIp = IPv4Addr.fromInt(ipPak.getSourceAddress)
        transSrcIpStart.range(transSrcIpEnd).find {
            case ip: IPv4Addr if ip equals snatedIp => true
            case _ => false
        } shouldBe Some(snatedIp)

        val icmpPak = ipPak.getPayload.asInstanceOf[ICMP]
        icmpPak should not be null
        icmpPak.getType shouldBe ICMP.TYPE_ECHO_REQUEST
        icmpPak.getIdentifier should be (icmpReq.getIdentifier)
        icmpPak.getQuench should be (icmpReq.getQuench)

        snatedIp
    }

    /**
     * Puts an ICMP reply from a host beyond the gateway on the router's uplink
     * port with a destination on the router's uplink IP. Expects that the
     * origSrcIp (a VM) receives the packet on its port, with the dstIp
     * translated to origSrcIp
     */
    private def pongExpectRevNatd(port: UUID, srcMac: MAC, srcIp: IPv4Addr,
                                  dstMac: MAC, dstIp: IPv4Addr,
                                  icmpId: Short, icmpSeq: Short,
                                  origSrcIp: IPv4Addr): Unit = {
        log.info("Send ICMP reply into the router, should be revSnat'ed")
        val icmpReply = injectIcmpEchoReply(port, srcMac, srcIp,
                                            dstMac, dstIp, icmpId, icmpSeq)
            .getPayload.asInstanceOf[IPv4]
            .getPayload.asInstanceOf[ICMP]

        // Expect the packet delivered back to the source, MAC should be learnt
        // already so expect straight on the bridge's port
        packetOutQueue.size should be (1)
        val (packet, actions) = packetOutQueue.remove()
        val outPorts = getOutPorts(actions)
        outPorts.size should be (1)

        // localPortNumberToName(outPorts.head) should be (Some())

        // TODO (galo) check mappings?
        val eth = applyPacketActions(packet.getEthernet, actions)
        val ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPak.getSourceAddress should be (srcIp.addr)
        ipPak.getDestinationAddress should be (origSrcIp.addr)
        val icmpPak = ipPak.getPayload.asInstanceOf[ICMP]
        icmpPak should not be null
        icmpPak.getType shouldBe ICMP.TYPE_ECHO_REPLY
        icmpPak.getIdentifier shouldBe icmpReply.getIdentifier
    }

    /**
     * You send a packet from a VM to a remote addr. beyond the router,
     * this method will kindly check that it arrives, reply with an ICMP
     * UNREACHABLE, and verify that the reverse SNAT is done properly
     */
    private def verifySnatICMPErrorAfterPacket(userspace: Boolean) {
        packetOutQueue.size should be (1)
        val (packet, actions) = packetOutQueue.remove()
        val outPorts = getOutPorts(actions)
        outPorts.size should be (1)

        val newMappings = updateAndDiffMappings()
        newMappings.size should be (1)
        natTable.getRefCount(newMappings.head) should be (
            if (userspace) 0 else 1)
        portMap(outPorts(0)) should be (uplinkPort.getId)

        val eth = packet.getEthernet
        val ethApplied = applyPacketActions(eth, actions)
        val mp = new Mapping(newMappings.head,
            vmPorts.head.getId, uplinkPort.getId, eth,
            ethApplied)

        log.info("Reply with an ICMP error that should be rev SNAT'ed")
        injectIcmpUnreachable(uplinkPort.getId, mp.revInFromMac,
                              mp.revInToMac, ethApplied)

        // We should see the ICMP reply back at the sender VM
        // Note that the second parameter is the packet sent from the origin
        // who is unaware of the NAT, eth has no actions applied so we can
        // use this one
        expectICMPError(vmPorts.head.getId, eth.getPayload.asInstanceOf[IPv4])
    }

    /**
     * You send a packet from a remote addr. beyond the gateway to the DNAT
     * address, this method will kindly check that it arrives to the VM,
     * reply with an ICMP UNREACHABLE, and verify that the reverse DNAT is done
     * properly
     */
    private def verifyDnatICMPErrorAfterPacket(userspace: Boolean): Unit = {
        packetOutQueue.size should be (1)
        val (packet, actions) = packetOutQueue.remove()
        val outPorts = getOutPorts(actions)
        outPorts.size should be (1)

        val newMappings = updateAndDiffMappings()
        newMappings.size should be (1)
        natTable.getRefCount(newMappings.head) should be (
            if (userspace) 0 else 1)

        val eth = packet.getEthernet
        val ethApplied = applyPacketActions(eth, actions)
        val mapping = new Mapping(newMappings.head,
            uplinkPort.getId, portMap(outPorts(0)),
            eth, ethApplied)

        log.info("TCP reached destination, {}", packet)

        // 10.0.0.X:80 -> 62.72.82.1:888
        log.info("Reply with an ICMP error that should be rev DNAT'ed")
        injectIcmpUnreachable(mapping.revInPort, mapping.revInFromMac,
            mapping.revInToMac, ethApplied)

        // We should see the ICMP reply back going out on the original source
        // Note that the second parameter is the packet sent from the origin
        // who is unaware of the NAT, eth has no actions applied so we can
        // use this one
        expectICMPError(uplinkPort.getId, eth.getPayload.asInstanceOf[IPv4])
    }
}
