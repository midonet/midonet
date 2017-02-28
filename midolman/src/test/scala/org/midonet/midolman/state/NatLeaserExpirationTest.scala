/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.midolman.state

import java.{util => ju}
import java.util.UUID
import java.util.concurrent.TimeUnit.{NANOSECONDS => NANOS, MILLISECONDS => MILLIS}

import scala.collection.mutable
import scala.concurrent.Future

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.{Condition, NatTarget, RuleResult}
import org.midonet.midolman.simulation.{Bridge => SimBridge}
import org.midonet.midolman.simulation.{Router => SimRouter}
import org.midonet.midolman.state.NatBlockAllocator.NoFreeNatBlocksException
import org.midonet.midolman.state.NatLeaser.blockOf
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.MockPacketWorkflow
import org.midonet.odp.{FlowMatches, Packet}
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.packets.{FlowStateStore, NatState => BaseNatState}
import org.midonet.packets.NatState.NatBinding
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.util.collection.Reducer
import org.midonet.util.logging.Logger
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.state.ShardedFlowStateTable


@RunWith(classOf[JUnitRunner])
class NatLeaserExpirationTest extends MidolmanSpec {

    var portMap = Map[Int, UUID]()
    val vmNetworkIp = new IPv4Subnet("10.0.0.0", 24)
    val routerIp = new IPv4Subnet("10.0.0.254", 24)
    val routerMac = MAC.fromString("22:aa:aa:ff:ff:ff")

    val vmPortName = "port0"
    var vmPort: UUID = null

    var vmPortNumber: Int = -1
    val vmMac = MAC.fromString("02:aa:bb:cc:dd:d1")
    val vmIp = IPv4Addr.fromString("10.0.0.1")

    var router: UUID = null
    private val uplinkGatewayAddr = IPv4Addr("180.0.1.1")
    private val uplinkGatewayMac: MAC = "02:0b:09:07:05:03"
    private val uplinkNwAddr = new IPv4Subnet("180.0.1.0", 24)
    private val uplinkPortAddr = IPv4Addr("180.0.1.2")
    private val uplinkPortMac: MAC = "02:0a:08:06:04:02"
    private var uplinkPort: UUID = null

    private val loopSubnet = new IPv4Subnet("2.2.2.0", 24)

    private val failUplinkGatewayAddr = new IPv4Subnet("180.100.0.1", 24)
    private val failUplinkGatewayMac: MAC = "03:0b:09:02:32:12"
    private val failUplinkPortAddr = new IPv4Subnet("180.100.0.2", 24)
    private val failUplinkPortMac: MAC = "43:53:16:b3:a3:22"
    private val failUplinkTargetSubnet = new IPv4Subnet("1.1.1.0", 24)
    private val failSubnetPortAddr = new IPv4Subnet("180.200.0.2", 24)
    private val failSubnetPortMac: MAC = "03:32:61:43:ba:dd"
    private val failSubnetPortGatewayAddr = new IPv4Subnet("180.200.0.1", 24)

    private val snatAddressStart = IPv4Addr("180.0.1.200")
    private val snatAddressEnd = IPv4Addr("180.0.1.205")

    private val natTable = new ShardedFlowStateTable[NatKey, NatBinding](clock).addShard()
    private var mappings = Set[NatKey]()

    private var pktWkfl: MockPacketWorkflow = null
    private val packetOutQueue: ju.Queue[(Packet, ju.List[FlowAction])] =
        new ju.LinkedList[(Packet, ju.List[FlowAction])]

    val allocatedBlocks = mutable.Set[NatBlock]()
    val natLeaser = new NatLeaser {
        override val log = Logger(NatLeaserExpirationTest.this.log)
        override val allocator: NatBlockAllocator = new NatBlockAllocator {
            override def allocateBlockInRange(natRange: NatRange) =
                (natRange.tpPortStart to natRange.tpPortEnd) map { port =>
                    new NatBlock(natRange.deviceId, natRange.ip, blockOf(port))
                } find { block =>
                    if (allocatedBlocks contains block) {
                        false
                    } else {
                        allocatedBlocks += block
                        true
                    }
                } map Future.successful getOrElse Future.failed(NoFreeNatBlocksException)

            override def freeBlock(natBlock: NatBlock): Unit = {
                allocatedBlocks -= natBlock
            }
        }

        override val clock = NatLeaserExpirationTest.this.clock
    }

    private class Mapping(val key: NatKey,
        val fwdInPort: UUID, val fwdOutPort: UUID,
        val fwdInPacket: Ethernet, val fwdOutPacket: Ethernet) {

        def flowCount: Int = natTable.getRefCount(key)
    }

    override def beforeTest(): Unit = {
        /*
         * Set up a topology with a bridge, two routers
         * and a VM
         *
         *   +-----+
         *   | VM  |
         *   +--+--+
         *      |10.0.0.1
         *      |
         *      +---------------+------
         *    bridge            |
         *                      |10.0.0.254
         *                 +----+----+
         *      180.100.0.2| Router  |
         *            +----+with SNAT|
         * 180.100.0.1|    +-----+---+
         *   +--------+-+        |180.0.1.2
         *   |failRouter|        |
         *   +--------+-+        |
         * 180.200.0.2|          +180.0.1.1
         *            |
         *            |
         *            +180.200.0.1
         *
         * The default route on the snat router sends via 180.0.1.1,
         * but 1.1.1.0/24 goes via 180.100.0.1. The default route on
         * failRouter sends via 180.200.0.1.
         * 2.2.2.0/24 loops between both routers.
         * The port for 180.200.0.1 doesn't have its arp entry seeded,
         * so simulation will never complete.
         */
        var portIdCounter = 1

        router = newRouter("router")

        val rtrPort = newRouterPort(router, routerMac,
            routerIp.toUnicastString, routerIp.toNetworkAddress.toString,
            routerIp.getPrefixLen)
        rtrPort should not be null

        newRoute(router, "0.0.0.0", 0,
            routerIp.toNetworkAddress.toString, routerIp.getPrefixLen,
            NextHop.PORT, rtrPort,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        val bridge = newBridge("bridge")

        val brPort = newBridgePort(bridge)
        brPort should not be null
        linkPorts(rtrPort, brPort)

        vmPort = newBridgePort(bridge)
        materializePort(vmPort, hostId, vmPortName)
        portMap += portIdCounter -> vmPort
        portIdCounter += 1

        uplinkPort = newRouterPort(
            router, uplinkPortMac, uplinkPortAddr.toString,
            uplinkNwAddr.getAddress.toString, uplinkNwAddr.getPrefixLen)
        uplinkPort should not be null
        materializePort(uplinkPort, hostId, "uplinkPort")
        portMap += portIdCounter -> uplinkPort
        portIdCounter += 1

        var route = newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0,
            NextHop.PORT, uplinkPort, uplinkGatewayAddr.toString, 1)
        route should not be null

        route = newRoute(router, "0.0.0.0", 0,
            uplinkNwAddr.getAddress.toString, uplinkNwAddr.getPrefixLen,
            NextHop.PORT, uplinkPort, intToIp(Route.NO_GATEWAY), 10)
        route should not be null

        val failRouter = newRouter("fail")
        val failRtrPort = newRouterPort(
            failRouter, failUplinkGatewayMac,
            failUplinkGatewayAddr.toUnicastString,
            failUplinkGatewayAddr.toNetworkAddress.toString,
            failUplinkGatewayAddr.getPrefixLen)
        val failUplinkPort = newRouterPort(
            router, failUplinkPortMac,
            failUplinkPortAddr.toUnicastString,
            failUplinkPortAddr.toNetworkAddress.toString,
            failUplinkPortAddr.getPrefixLen)
        val failSubnetPort = newRouterPort(
            failRouter, failSubnetPortMac,
            failSubnetPortAddr.toUnicastString,
            failSubnetPortAddr.toNetworkAddress.toString,
            failSubnetPortAddr.getPrefixLen)

        linkPorts(failRtrPort, failUplinkPort)
        materializePort(failSubnetPort, hostId, "failPort")

        newRoute(router, "0.0.0.0", 0,
                 failUplinkPortAddr.toNetworkAddress.toString,
                 failUplinkPortAddr.getPrefixLen,
                 NextHop.PORT, failUplinkPort,
                 new IPv4Addr(Route.NO_GATEWAY).toString, 10)
        newRoute(router, "0.0.0.0", 0,
                 failUplinkTargetSubnet.toNetworkAddress.toString,
                 failUplinkTargetSubnet.getPrefixLen,
                 NextHop.PORT, failUplinkPort,
                 failUplinkGatewayAddr.getAddress.toString, 1)
        newRoute(failRouter, "0.0.0.0", 0,
                 failSubnetPortAddr.toNetworkAddress.toString,
                 failSubnetPortAddr.getPrefixLen,
                 NextHop.PORT, failSubnetPort,
                 new IPv4Addr(Route.NO_GATEWAY).toString, 10)
        newRoute(failRouter, "0.0.0.0", 0,
                 failUplinkTargetSubnet.toNetworkAddress.toString,
                 failUplinkTargetSubnet.getPrefixLen,
                 NextHop.PORT, failSubnetPort,
                 failSubnetPortGatewayAddr.getAddress.toString, 1)

        // create a loop between the routers
        newRoute(router, "0.0.0.0", 0,
                 loopSubnet.toNetworkAddress.toString,
                 loopSubnet.getPrefixLen,
                 NextHop.PORT, failUplinkPort,
                 failUplinkGatewayAddr.getAddress.toString, 1)
        newRoute(failRouter, "0.0.0.0", 0,
                 loopSubnet.toNetworkAddress.toString,
                 loopSubnet.getPrefixLen,
                 NextHop.PORT, failRtrPort,
                 failUplinkPortAddr.getAddress.toString, 1)

        // create SNAT rules
        val rtrOutChain = newOutboundChainOnRouter("rtrOutChain", router)
        val rtrInChain = newInboundChainOnRouter("rtrInChain", router)

        val revSnatRule = newReverseNatRuleOnChain(rtrInChain, 1,
            new Condition(), RuleResult.Action.CONTINUE, isDnat = false)
        revSnatRule should not be null

        val snatCond = new Condition()
        snatCond.nwSrcIp = new IPv4Subnet(
                           IPv4Addr.fromString(vmNetworkIp.toUnicastString), 24)
        snatCond.nwDstIp = new IPv4Subnet(
                           IPv4Addr.fromString(vmNetworkIp.toUnicastString), 24)
        snatCond.nwDstInv = true

        val snatTarget = new NatTarget(snatAddressStart, snatAddressEnd,
                                       10001, 65535)
        val snatRule = newForwardNatRuleOnChain(rtrOutChain, 1, snatCond,
                RuleResult.Action.CONTINUE, Set(snatTarget), isDnat = false)
        snatRule should not be null

        val simRouter: SimRouter = fetchDevice[SimRouter](router)
        val simBridge: SimBridge = fetchDevice[SimBridge](bridge)

        // feed the router arp cache with the uplink gateway's mac address
        feedArpTable(simRouter, uplinkGatewayAddr.addr, uplinkGatewayMac)
        feedArpTable(simRouter, uplinkPortAddr.addr, uplinkPortMac)
        feedArpTable(simRouter, routerIp.getAddress.addr, routerMac)
        feedArpTable(simRouter, failUplinkGatewayAddr.getAddress.addr,
                     failUplinkGatewayMac)

        // feed the router's arp cache with each of the vm's addresses
        feedArpTable(simRouter, vmIp, vmMac)
        feedMacTable(simBridge, vmMac, vmPort)

        fetchDevice[SimRouter](failRouter)
        fetchPorts(rtrPort,  uplinkPort, failUplinkPort,
                   failRtrPort, failSubnetPort, vmPort)
        fetchChains(rtrInChain, rtrOutChain)
        pktWkfl = packetWorkflow(portMap, natTable = natTable,
                                 natLeaser = natLeaser)
        pktWkfl.process()

        mockDpChannel.packetsExecuteSubscribe(
            (packet, actions) => packetOutQueue.add((packet,actions)) )
    }

    feature("NAT leases are freed") {
        scenario("after flow state expires") {
            Given("a topology with SNAT")
            pktWkfl.process()
            allocatedBlocks.size shouldBe 0
            When("a packet is sent via the uplink")
            injectTcp(vmPort, vmMac, vmIp, 30501,
                      routerMac, IPv4Addr("62.72.82.1"), 22,
                      syn = true)
            val newMappings: Set[NatKey] = updateAndDiffMappings()
            newMappings.size should be (1)
            natTable.getRefCount(newMappings.head) should be (1)
            packetOutQueue.size should be (1)
            val (packet, actions) = packetOutQueue.remove()
            val outPorts = getOutPorts(actions)
            outPorts.size should be (1)
            portMap(outPorts(0)) should be (uplinkPort)

            Then("a nat binding is allocated")
            val mapping = new Mapping(newMappings.head,
                                      vmPort, uplinkPort,
                                      packet.getEthernet,
                                      applyPacketActions(packet.getEthernet, actions))
            allocatedBlocks.size shouldBe 1

            When("the flow is expired")
            simBackChannel.tell(FlowTagger.tagForRouter(router))
            pktWkfl.process()

            And("flow state expires")
            mapping.flowCount should be (0)
            clock.time = FlowStateStore.DEFAULT_EXPIRATION.toNanos + 1
            pktWkfl.process()

            Then("the flow state should be removed from the table")
            fwdKeys().size should be (0)

            And("the block should still be allocated")
            allocatedBlocks.size shouldBe 1

            When("the block expiration period is passed")
            clock.time += NatLeaser.BLOCK_EXPIRATION.toNanos + 1
            pktWkfl.process()

            Then("the block should be released")
            allocatedBlocks.size shouldBe 0
        }


        scenario("after error drop (due to loop)") {
            Given("a topology with SNAT")

            pktWkfl.process()
            allocatedBlocks.size shouldBe 0
            pktWkfl.postponedContexts shouldBe 0

            When("a packet is sent via a error path (error port)")
            injectTcp(vmPort, vmMac, vmIp, 30501,
                      routerMac, IPv4Addr("2.2.2.2"), 22,
                      syn = true)

            Then("a block should have been allocated")
            allocatedBlocks.size shouldBe 1

            And("no keys committed")
            fwdKeys().size should be (0)

            When("the block expiration period passes")
            clock.time = NatLeaser.BLOCK_EXPIRATION.toNanos + 1
            pktWkfl.process() // obliterate expired blocks

            Then("no nat binding is allocated")
            fwdKeys().size should be (0)
            allocatedBlocks.size shouldBe 0
        }


        scenario("after arp timeout drop simulation") {
            val arpTimeout = NANOS.convert(config.arptable.timeout, MILLIS)
            val blockExpiration = NatLeaser.BLOCK_EXPIRATION.toNanos
            Given("a topology with SNAT")
            pktWkfl.process()
            allocatedBlocks.size shouldBe 0
            pktWkfl.postponedContexts shouldBe 0
            arpTimeout should be < blockExpiration

            When("a packet is sent via a broken path (failRouter)")
            injectTcp(vmPort, vmMac, vmIp, 30501,
                      routerMac, IPv4Addr("1.1.1.1"), 22,
                      syn = true)

            Then("a nat binding is allocated")
            allocatedBlocks.size shouldBe 1

            And("the simulation doesn't complete")
            pktWkfl.postponedContexts shouldBe 1

            And("no keys should be committed")
            fwdKeys().size should be (0)

            When("the postponed simulation times out")
            clock.time = NANOS.convert(config.arptable.timeout, MILLIS) + 1
            pktWkfl.process() // expire arp
            pktWkfl.process() // expire postponed simulation

            Then("there should a be no postponed simulations")
            pktWkfl.postponedContexts shouldBe 0

            And("there should still be a block allocated")
            allocatedBlocks.size shouldBe 1

            When("the block expiration period passes")
            clock.time = NatLeaser.BLOCK_EXPIRATION.toNanos + 1
            pktWkfl.process() // obliterate expired blocks

            Then("the lease on the nat binding is released")
            allocatedBlocks.size shouldBe 0
            pktWkfl.postponedContexts shouldBe 0
        }
    }

    def fwdKeys(): Set[NatKey] = {
        val reducer =  new Reducer[NatKey, NatBinding, Set[NatKey]] {
            def apply(acc: Set[NatKey], key: NatKey,
                      value: NatBinding): Set[NatKey] =
                key.keyType match {
                    case BaseNatState.FWD_SNAT | BaseNatState.FWD_DNAT |
                            BaseNatState.FWD_STICKY_DNAT =>
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
        val packet = new Packet(frame,
                                FlowMatches.fromEthernetPacket(frame)
                                    .addKey(FlowKeys.inPort(inPortNum))
                                    .setInputPortNumber(inPortNum))
        pktWkfl.handlePackets(packet)
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

    def getOutPorts(actions: ju.List[FlowAction]): ju.List[Int] = {
        val ports = new ju.ArrayList[Int]()
        actions.asScala.foreach {
            case o: FlowActionOutput =>
                ports.add(o.getPortNumber())
            case _ =>
        }
        ports
    }
}
