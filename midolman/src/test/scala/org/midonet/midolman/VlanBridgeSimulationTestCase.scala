/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import java.nio.ByteBuffer
import java.util.UUID

import akka.testkit.TestProbe
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.junit.{Ignore, Test}
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.{RouterPort, BridgePort}
import org.midonet.cluster.data.{Entity, Bridge}
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.FlowController.WildcardFlowRemoved
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.topology.VirtualTopologyActor.BridgeRequest
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.{VirtualTopologyActor, LocalPortActive}
import org.midonet.midolman.util.SimulationHelper
import org.midonet.odp.flows.FlowActionOutput
import org.midonet.odp.flows.FlowActionPopVLAN
import org.midonet.odp.flows.FlowActionPushVLAN
import org.midonet.packets._

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class VlanBridgeSimulationTestCase extends SimulationHelper
        with VirtualConfigurationBuilders {

    val log = LoggerFactory.getLogger(this.getClass)

    var trunk1Id: UUID = null
    var trunk2Id: UUID = null
    var intVlanPort1Id: UUID = null
    var intVlanPort2Id: UUID = null

    val vlanId1: Short = 101
    val vlanId2: Short = 202

    // MAC of something plugged at the exterior ports of the vlan-bridge
    val trunkMac = MAC.fromString("aa:bb:cc:dd:dd:ff")
    val vm1_1Mac = MAC.fromString("aa:bb:aa:bb:cc:cc")
    val vm2_1Mac = MAC.fromString("cc:cc:aa:aa:bb:bb")
    val vm2_2Mac = MAC.fromString("cc:cc:aa:aa:ee:ee")

    // various IPs
    val trunkIp = IPv4Addr.fromString("10.1.1.1")
    val vm1_1Ip = IPv4Addr.fromString("10.1.1.1")
    val vm2_1Ip = IPv4Addr.fromString("10.2.2.2")
    val vm2_2Ip = IPv4Addr.fromString("10.2.2.2")

    // Two ports in the normal bridges connecting to the VAB (be it the
    // VlanAwareBridge or a normal, enhanced Brige
    var br1IntPort: BridgePort = null
    var br2IntPort: BridgePort = null
    // These are the interior ports tagged with a VLAN ID that can be
    // in the legacy VAB or in the enhanced Bridge
    // The VMs have exterior ports
    var vm1_1ExtPort: BridgePort = null
    var vm2_1ExtPort: BridgePort = null
    var vm2_2ExtPort: BridgePort = null

    var vlanBridge: Bridge = null
    var br1: Bridge = null // on vlan 1
    var br2: Bridge = null // on vlan 2

    var host: Host = null

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    def feedBridgeArpCaches() {
        feedArpCache("vm1_1Port", vm1_1Ip.toInt, vm1_1Mac, vm2_1Ip.toInt, vm2_1Mac)
        feedArpCache("vm2_1Port", vm2_1Ip.toInt, vm2_1Mac, vm1_1Ip.toInt, vm1_1Mac)
        feedArpCache("vm2_2Port", vm2_2Ip.toInt, vm2_2Mac, vm1_1Ip.toInt, vm1_1Mac)
        // TODO (galo) replace this sleep with appropriate probing
        Thread.sleep(2000)
        drainProbes()
    }

    def getPortName (portNo: UUID): String = {
        // TODO this doesn't seem to work with match :(
        if (portNo.equals(trunk1Id)) "trunkPort1"
        else if (portNo.equals(trunk2Id)) "trunkPort2"
        else if (portNo.equals(vm1_1ExtPort.getId)) "vm1_1Port"
        else if (portNo.equals(vm2_1ExtPort.getId)) "vm2_1Port"
        else if (portNo.equals(vm2_2ExtPort.getId)) "vm2_2Port"
        else null
    }

    // TODO(galo) review this method, copied in a rush from
    // BridgeSimulationTestCase
    def injectOnePacket (ethPkt : Ethernet, ingressPortId: UUID,
                         expectFlowAdded: Boolean = true):
    Option[WildcardFlowAdded] = {

        val ingressPortName = getPortName(ingressPortId)
        triggerPacketIn(ingressPortName, ethPkt)

        if (!expectFlowAdded)
            return None

        val pktInMsg = packetInProbe.expectMsgClass(classOf[PacketIn])
        pktInMsg should not be null
        pktInMsg.eth should not be null
        pktInMsg.wMatch should not be null
        // We're racing with DatapathController here. DC's job is to remove
        // the inputPortUUID field and set the corresponding inputPort (short).
        if (pktInMsg.wMatch.getInputPortUUID != null)
            pktInMsg.wMatch.getInputPortUUID should be(ingressPortId)
        else
            pktInMsg.wMatch.getInputPort should be(getPortNumber(ingressPortName))

        Some(wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded]))
    }

    /**
     * Sends an eth frame from the given port, mac, ip, addresed to the dst
     * mac and IP. Will verify that the frame arrives at toPorts.
     *
     * if vlanOnInject is set, it will inject vlanId in the outbound frame
     * and expect that the received frame doesn't have any. If vlanOnInject
     * is false, it will not put a vlan id when injecting, but will expect it
     * when received on the other end.
     */
    def sendFrame(fromPort: UUID, toPorts: List[UUID],
                  fromMac: MAC, toMac: MAC,
                  fromIp: IPv4Addr, toIp: IPv4Addr,
                  vlanId: Short, vlanOnInject: Boolean = false,
                  expectFlowAdded: Boolean = true) {

        val ethRaw = Packets.udp(fromMac, toMac, fromIp, toIp, 10, 11,
                                 "hello".getBytes)

        // same, with VLAN
        val ethVlan = new Ethernet()
        ethVlan.deserialize(ByteBuffer.wrap(ethRaw.serialize()))
        ethVlan.setVlanID(vlanId)

        val vlanIdsToPush = if (vlanOnInject) List() else List(vlanId)
        val vlanIdsToPop = if (vlanOnInject) List(vlanId) else List()

        val inEth = if (vlanOnInject) ethVlan else ethRaw

        val toPortNos = toPorts map (p => getPortNumber(getPortName(p)))
        val addFlowMsg = injectOnePacket(inEth, fromPort, expectFlowAdded)
        if (expectFlowAdded)
            addFlowMsg.get.f should not be null
        val ethRcv =
            expectPacketOutWithVlanIds(toPortNos, vlanIdsToPush, vlanIdsToPop)

        log.debug("SENT: {}", inEth)
        log.debug("GOT: {}", ethRcv)
        // Note that actions do not get applied at this point, so the ingress
        // and egress packets will be the same. Since we told expectPacketOut
        // to expect a vlanPushPop we can trust that the relevant actions to
        // remove the vlan id is there.
        ethRcv should be === inEth

    }

    def sendFrameExpectDrop(fromPort: UUID, toPorts: List[UUID],
                            fromMac: MAC, toMac: MAC,
                            fromIp: IPv4Addr, toIp: IPv4Addr,
                            vlanId: Short, vlanOnInject: Boolean = false) {

        val ethRaw = Packets.udp(fromMac, toMac, fromIp, toIp, 10, 11,
                                 "hello".getBytes)

        // same, with VLAN
        val ethVlan = new Ethernet()
        ethVlan.deserialize(ByteBuffer.wrap(ethRaw.serialize()))
        ethVlan.setVlanID(vlanId)
        val inEth = if (vlanOnInject) ethVlan else ethRaw
        injectOnePacket(inEth, fromPort, expectFlowAdded = false)
        packetsEventsProbe.expectNoMsg()
        val wFlowAdded = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        wFlowAdded.f.getMatch.getEthernetSource should be (fromMac)
        wFlowAdded.f.getMatch.getEthernetDestination should be (toMac)
        log.info("Actions {}", wFlowAdded)
    }

    def arpReq(srcMac: MAC, srcIp: Int, dstIp: Int, vlanId: Short) = {
        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6)
        arp.setProtocolAddressLength(4)
        arp.setOpCode(ARP.OP_REQUEST)
        arp.setSenderHardwareAddress(srcMac)
        arp.setSenderProtocolAddress(IPv4Addr.intToBytes(srcIp))
        arp.setTargetHardwareAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        arp.setTargetProtocolAddress(IPv4Addr.intToBytes(dstIp))
        val eth = new Ethernet()
        eth.setPayload(arp)
        eth.setSourceMACAddress(trunkMac)
        eth.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        eth.setEtherType(ARP.ETHERTYPE)
        if (vlanId != 0)
            eth.setVlanID(vlanId)
        eth
    }

    def expectBroadCast(materializedPort: Short,trunk1: Short,
                        trunk2: Short, vlanId: Short) {
        val msg = packetsEventsProbe.expectMsgClass(classOf[PacketsExecute])
        var trunkPorts = List[Short](trunk1, trunk2)

        msg.packet.getActions.size should be === 4

        msg.packet.getActions.get(0) match {
            case act: FlowActionOutput =>
                act.getPortNumber should be === materializedPort
            case _ => fail("Action didn't match expected FlowActionOutput")
        }

        msg.packet.getActions.get(1) match {
            case act: FlowActionPushVLAN =>
                (act.getValue.getTagControlIdentifier & 0x0fff)
                    .toShort should be === vlanId
            case _ => fail("Action didn't match expected FlowActionPushVLAN")
        }

        msg.packet.getActions.get(2) match {
            case act: FlowActionOutput =>
                trunkPorts -= act.getPortNumber.toShort
            case _ => fail("Action didn't match expected FlowActionOutput")
        }

        msg.packet.getActions.get(3) match {
            case act: FlowActionOutput =>
                trunkPorts -= act.getPortNumber.toShort
            case _ => fail("Action didn't match expected FlowActionOutput")
        }

        trunkPorts should have size 0
    }

    override def beforeTest() {
        vlanBridge = newBridge("vlan-bridge")
        val trunkPort1 = newBridgePort(vlanBridge)
        val trunkPort2 = newBridgePort(vlanBridge)
        trunk1Id = trunkPort1.getId
        trunk2Id = trunkPort2.getId
        val intVlanPort1 = newBridgePort(vlanBridge, Some(vlanId1))
        val intVlanPort2 = newBridgePort(vlanBridge, Some(vlanId2))
        intVlanPort1Id = intVlanPort1.getId
        intVlanPort2Id = intVlanPort2.getId

        host = newHost("host1", hostId())

        br1 = newBridge("bridge1")
        br2 = newBridge("bridge2")

        vm1_1ExtPort = newBridgePort(br1)
        vm2_1ExtPort = newBridgePort(br2)
        vm2_2ExtPort = newBridgePort(br2)

        br1IntPort = newBridgePort(br1)
        br2IntPort = newBridgePort(br2)

        materializePort(vm1_1ExtPort, host, "vm1_1Port")
        materializePort(vm2_1ExtPort, host, "vm2_1Port")
        materializePort(vm2_2ExtPort, host, "vm2_2Port")

        clusterDataClient().portsLink(intVlanPort1Id, br1IntPort.getId)
        clusterDataClient().portsLink(intVlanPort2Id, br2IntPort.getId)

        log.debug("VLAN BRIDGE SIMULATION TEST CASE -- TOPOLOGY SUMMARY")
        log.debug("Bridge 1: {}, on vlan {}", br1.getId, vlanId1)
        log.debug("Bridge 2: {}, on vlan {}", br2.getId, vlanId2)
        log.debug("Vlan Bridge: {}", vlanBridge.getId)
        log.debug("Vlan Bridge port to bridge 1: {}", intVlanPort1)
        log.debug("Vlan Bridge port to bridge 2: {}", intVlanPort2)

        initialize()
    }

    /**
     * Tests that the vlan-bridge receives a frame in a trunk with a vlan id
     * that it doesn't know.
     *
     * When the VAB is implemented by the Bridge, it'll chose to broadcast to
     * all the other trunk ports. In this case, we send an ARP from a trunk,
     * with a vlan-id that the bridge will not recognize. It should just send
     * the frame to the other trunks (in this scenario, one).
     */
    @Test
    def testFrameFromTrunkWithUnknownVlanId() {
        feedBridgeArpCaches()
        val eth = Packets.arpRequest(trunkMac, trunkIp,
                                     IPv4Addr.fromString("10.1.1.22"))
        eth.deserialize(ByteBuffer.wrap(eth.serialize()))
        eth.setVlanID(500)
        injectOnePacket(eth, trunk1Id)
        val msg = packetsEventsProbe.expectMsgClass(classOf[PacketsExecute])
        msg.packet.getActions.size() should be === 1
        msg.packet.getActions.get(0) match {
            case act: FlowActionOutput =>
                act.getPortNumber should be === getPortNumber("trunkPort2")
        }
    }

    /**
     * Materializes the two ports with ids set in trunk1Id and trunk2Id
     * and initializes the datapath.
     */
    def initialize() {
        val trunk1 = clusterDataClient().portsGet(trunk1Id)
        val trunk2 = clusterDataClient().portsGet(trunk2Id)
        materializePort(trunk1, host, "trunkPort1")
        materializePort(trunk2, host, "trunkPort2")

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady]
            .datapath should not be (null)

        // We expect 5 of these: 1 for each of 3 vlans + 2 trunks
        for(i <- 1.to(5)) log.info(" EXT. PORT ACTIVE: {}",
            portsProbe.expectMsgClass(classOf[LocalPortActive]))

        drainProbes()

        //packetsEventsProbe = newProbe()
        //actors().eventStream.subscribe(packetsEventsProbe.ref, classOf[PacketsExecute])

        // Request ports for the first time so that we trigger associated
        // flow invalidations now and they don't impact expects during the tests
        val vta = VirtualTopologyActor.getRef(actors())
        ask(vta, PortRequest(trunk1Id))
        ask(vta, PortRequest(trunk2Id))
        ask(vta, PortRequest(intVlanPort1Id))
        ask(vta, PortRequest(intVlanPort2Id))
        ask(vta, PortRequest(br1IntPort.getId))
        ask(vta, PortRequest(br2IntPort.getId))
        ask(vta, BridgeRequest(br1.getId))
        ask(vta, BridgeRequest(br2.getId))

        drainProbes()
    }

    /**
     * Does an ARP request/reply starting on the VM2, asking for VM2_1's MAC
     */
    @Test
    def testArpStartedFromVM () {

        // Let's make the bridges learn the MACs of the vms connected to
        // them so that they can route the ARP reply from the trunk to the VM
        feedBridgeArpCaches()

        log.info("ARP request from VM 2_1 to trunk")
        val arpReqEth = arpReq(vm2_1Mac, vm2_1Ip.toInt, trunkIp.toInt, 0)
        triggerPacketIn("vm2_1Port", arpReqEth)

        // we should see the ARP on both trunks AND the other VM on the same
        // bridge
        expectBroadCast(getPortNumber("vm2_2Port").toShort,
            getPortNumber("trunkPort1").toShort,
            getPortNumber("trunkPort2").toShort, vlanId2)

        // no other packet should be transmitted
        packetsEventsProbe.expectNoMsg()

        log.info("ARP reply from trunk to VM 2_1")
        val arpRepEth = ARP.makeArpReply(trunkMac, vm2_1Mac,
            IPv4Addr.intToBytes(trunkIp.toInt),
            IPv4Addr.intToBytes(vm2_1Ip.toInt))
        arpRepEth.setVlanID(vlanId2)
        triggerPacketIn("trunkPort1", arpRepEth)

        val inEth = expectPacketOutWithVlanIds(
            List(getPortNumber("vm2_1Port")), List(), List(vlanId2))
        inEth.getEtherType should be === ARP.ETHERTYPE

        // Again, no other packets should've been transmitted
        packetsEventsProbe.expectNoMsg()
    }

    /**
     * Does an ARP request/reply starting on the trunk, asking for VM2_2's MAC
     */
    @Test
    def testArpStartedFromTrunk () {

        log.info("ARP request from trunk to VM 2_1")
        val arpReqEth = arpReq(trunkMac, trunkIp.toInt, vm2_1Ip.toInt, vlanId2)
        triggerPacketIn("trunkPort1", arpReqEth)
        // The vlan-bridge will pop the vlan tag and pass the ARP to the
        // unaware bridge, who should flood on all VMs on bridge 2
        var toPorts = List(getPortNumber("trunkPort2"),
                           getPortNumber("vm2_1Port"),
                           getPortNumber("vm2_2Port"))

        var inEth = expectPacketOutWithVlanIds(toPorts, List(), List(vlanId2))
        inEth.getEtherType should be === ARP.ETHERTYPE
        inEth.getPayload.asInstanceOf[ARP].getOpCode should be === ARP.OP_REQUEST
        inEth.getSourceMACAddress should be === trunkMac
        inEth.getDestinationMACAddress should be === MAC.fromString("ff:ff:ff:ff:ff:ff")

        // no other packet should be transmitted
        packetsEventsProbe.expectNoMsg()

        log.info("ARP reply from VM to trunk")
        val arpRepEth = ARP.makeArpReply(vm2_1Mac, trunkMac,
                                         IPv4Addr.intToBytes(vm2_1Ip.toInt),
                                         IPv4Addr.intToBytes(trunkIp.toInt))
        triggerPacketIn("vm2_1Port", arpRepEth)

        toPorts = List(getPortNumber("trunkPort1"))
        inEth = expectPacketOutWithVlanIds(toPorts, List(vlanId2), List())
        inEth.getEtherType should be === ARP.ETHERTYPE
        inEth.getSourceMACAddress should be === vm2_1Mac
        inEth.getDestinationMACAddress should be === trunkMac

        // Again, no other packets should've been transmitted
        packetsEventsProbe.expectNoMsg()

    }

    /**
     * Sends frames from the trunks, then from the VMs, expects that actions
     * are correctly injected.
     */
    @Test
    def testFrameExchangeThroughVlanBridge() {
        feedBridgeArpCaches()
        sendFrame(trunk1Id, List(vm1_1ExtPort.getId), trunkMac,
            vm1_1Mac, trunkIp, vm1_1Ip, vlanId1, vlanOnInject = true)
        sendFrame(trunk1Id, List(vm2_1ExtPort.getId), trunkMac,
            vm2_1Mac, trunkIp, vm2_1Ip, vlanId2, vlanOnInject = true)

        log.debug("The bridge plugged to VM1 has learned trunkMac, active " +
            "trunk port is trunkPort1 ({})", trunk1Id)

        sendFrame(vm1_1ExtPort.getId, List(trunk1Id),
                  vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, vlanId1)
        sendFrame(vm2_1ExtPort.getId, List(trunk1Id),
                  vm2_1Mac, trunkMac, vm2_1Ip, trunkIp, vlanId2)
    }

    /**
     * This test sends a frame from a VM that contains a random vlan id, and
     * expects to receive it at the trunks with a PUSH VLAN action that would
     * put the VLAN ID corresponding to that vm's interior port connected to
     * the vlan bridge.
     *
     * So, if I send a frame(vlanIds=[10]) from a vm that is connected on an
     * interior port tagged with vlan id = 20 to the vlan aware bridge, then
     * the egressing packet on the trunks should be frame(vlanIds=[20, 10])
     */
    @Test
    def testFrameWithNestedVlanTags() {
        feedBridgeArpCaches()
        // Activate the trunk
        sendFrame(trunk1Id, List(vm1_1ExtPort.getId), trunkMac,
                  vm1_1Mac, trunkIp, vm1_1Ip, vlanId1, vlanOnInject = true)

        // Send a frame from VM1 without VLAN ID (normal case)
        sendFrame(vm1_1ExtPort.getId, List(trunk1Id),
                  vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, vlanId1)

        // Now send a frame but with some private VLAN ID
        val eth = Packets.udp(vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, 10, 11,
                              "hello".getBytes)
        eth.setVlanID(666)

        var trunks = Set(getPortNumber("trunkPort1"))
        val addFlowMsg = injectOnePacket(eth, vm1_1ExtPort.getId)
        addFlowMsg match {
            case None => fail("Expecting a flow added")
            case Some(msg: WildcardFlowAdded) =>
                msg.f.getActions should not be null
                msg.f.getActions should have size (trunks.size + 1)
                msg.f.getActions foreach ( act => act match {
                    case a: FlowActionOutput => trunks -= a.getPortNumber
                    case a: FlowActionPushVLAN =>
                        (a.getTagControlIdentifier & 0x0fff) should be === (vlanId1)
                    case a: FlowActionPopVLAN =>
                })
        }
        trunks should have size (0)
        wflowAddedProbe.expectNoMsg()
    }

    /**
     * This test sends frames from one trunk port, then starts sending from
     * the other. The Vlan-Aware bridge always sends frames from the virtual
     * network to both trunks.
     */
    @Test
    def testTrunkPortChange() {
        feedBridgeArpCaches()
        // Send a frame from one port in the VLAN-Bridge that is
        // connected to a VM behind a bridge, to a VM on the other side
        log.info("Send a frame from trunk port 1, traffic should go to the vNw")
        sendFrame(trunk1Id, List(vm1_1ExtPort.getId), trunkMac,
                  vm1_1Mac, trunkIp, vm1_1Ip, vlanId1, vlanOnInject = true)
        sendFrame(vm1_1ExtPort.getId, List(trunk1Id),
                  vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, vlanId1)

        log.info("Now let's send a frame from trunk port 2, same vlan / src mac")
        sendFrame(trunk2Id, List(vm1_1ExtPort.getId), trunkMac,
                  vm1_1Mac, trunkIp, vm1_1Ip, vlanId1, vlanOnInject = true)

        // We've learned a new port for trunkMac, the old flows must be gone
        val wfrs = wflowRemovedProbe.expectMsgAllClassOf(
            classOf[WildcardFlowRemoved], classOf[WildcardFlowRemoved])
        wfrs.foreach( wfr => wfr.f.actions match {
            case List(popAct: FlowActionPopVLAN, outAct: FlowActionOutput) =>
                // the flow trunk -> intPort that pops the vlan
                outAct.getPortNumber should be === getPortNumber("vm1_1Port")
            case List(pushAct: FlowActionPushVLAN, outAct: FlowActionOutput) =>
                // the flow intPort -> trunk that pushes the vlan
                (pushAct.getTagControlIdentifier & 0x0fff) should be === (vlanId1)
                outAct.getPortNumber should be === getPortNumber("trunkPort1")
            case acts =>
                fail("Unexpected WildcardFlowRemoved with actions: " + acts)
        })
        wflowAddedProbe.expectNoMsg()
        // A new flow should be added, this is checked already in sendFrame
        log.info("Sending frames from VMs, should go to trunk 2")
        sendFrame(vm1_1ExtPort.getId, List(trunk2Id),
            vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, vlanId1,
            vlanOnInject = false, expectFlowAdded = false)
    }

    /**
     *
     * Send a broadcast from the trunk to a specific vlan
     */
    @Test
    def testBroadcastFrameWithVlanIdFromTrunk() {
        feedBridgeArpCaches()
        val toPorts = List(vm2_1ExtPort.getId, vm2_2ExtPort.getId, trunk2Id)
        sendFrame(trunk1Id, toPorts, trunkMac,
                  MAC.fromString("ff:ff:ff:ff:ff:ff"), trunkIp,
                  vm2_1Ip, vlanId2, vlanOnInject = true)
    }

    /**
     * Send a broadcast from a vm in a vlan, should get to the trunk
     */
    @Test
    def testBroadcastFrameWithoutVlanIdFromVM() {
        feedBridgeArpCaches()
        // learn an active trunk first
        sendFrame(trunk1Id, List(vm2_1ExtPort.getId), trunkMac, vm2_1Mac,
            trunkIp, vm2_1Ip, vlanId2, vlanOnInject = true)

        val fromMac = vm2_1Mac
        val toMac = MAC.fromString("ff:ff:ff:ff:ff:ff")
        val fromIp = vm2_1Ip
        val toIp = trunkIp

        val ethRaw = Packets.udp(fromMac, toMac, fromIp, toIp, 10, 11,
            "hello".getBytes)

        val addFlowMsg = injectOnePacket(ethRaw, vm2_1ExtPort.getId)
        addFlowMsg.getOrElse(fail("Expecting a WildcardFlowAdded")).f should not be null

        // We should have 1 packet executes whose actions are output to the materialized
        // ports of the bridge and output to one on the active trunk,
        // with a push vlan action
        expectBroadCast(getPortNumber("vm2_2Port").toShort,
                        getPortNumber("trunkPort2").toShort,
                        getPortNumber("trunkPort1").toShort,
                        vlanId2)
    }

    /**
     * Tests that if the Vlan aware bridge sees traffic from the virtual network
     * before learning the active trunk, it'll send the frames on ALL trunks.
     */
    @Test
    def testUnknownTrunk() {
        feedBridgeArpCaches()
        // Broadcast frame from the VM, no trunk is learned yet
        sendFrame(vm1_1ExtPort.getId, List(trunk1Id, trunk2Id),
            vm1_1Mac, MAC.fromString("ff:ff:ff:ff:ff:ff"), vm1_1Ip,
            trunkIp, vlanId1, vlanOnInject = false)
    }

    /**
     * Tests that BPDU frames flow correctly accross trunks.
     */
    @Test
    def testBPDU() {
        def doBpdu(from: UUID, to: UUID) {
            val bpdu = Packets.bpdu(MAC.random(),
                MAC.fromString("01:80:c2:00:00:00"),
                BPDU.MESSAGE_TYPE_TCNBPDU, 0x0, 100, 10,
                1, 23, 1000, 2340, 100, 10)
            val eth = Ethernet.deserialize(bpdu)
            val afm = injectOnePacket(eth, from) // returns add flow msg
            afm match {
                case None =>
                case Some(msg: WildcardFlowAdded) =>
                    msg.f should not be null
                    msg.f.getActions should have size 1
                    val act = msg.f.getActions.head.asInstanceOf[FlowActionOutput]
                    act.getPortNumber should be === getPortNumber(getPortName(to))
            }
        }
        doBpdu(trunk1Id, trunk2Id)
        doBpdu(trunk2Id, trunk1Id)
    }

    /**
     * Basically the same as testFrameExchangeThroughVlanBridge, but will send
     * an additional frame from the trunk to a MAC using the wrong vlan id.
     *
     * So basically, if we have a MAC1 on interior port 1 which has vlan id 1,
     * we'll send a frame to MAC1 using vlan id 2.
     *
     * The expected behaviour is that the frame is dropped.
     *
     * In the old times, if a MAC resolved to a port, then we would expect that
     * this port was tagged with
     * the same vlan as the frame.
     *
     * Now we have per-vlan mac learning, so it makes sense that if a frame on
     * vlan X comes for mac M, if we knew the mac for another vlan but not for
     * X the bridge will start ARPing to both the trunks and the port (if any)
     * with vlan X.
     *
     * TODO extend and ensure that the frame exchanges keep working after
     * learning the mac from both macs.
     */
    @Test
    def testFrameToWrongPort() {
        feedBridgeArpCaches()
        sendFrame(trunk1Id, List(vm1_1ExtPort.getId), trunkMac,
            vm1_1Mac, trunkIp, vm1_1Ip, vlanId1, vlanOnInject = true)
        sendFrame(trunk1Id, List(vm2_1ExtPort.getId), trunkMac,
            vm2_1Mac, trunkIp, vm2_1Ip, vlanId2, vlanOnInject = true)

        log.debug("The bridge plugged to VM1 has learned trunkMac, active " +
            "trunk port is trunkPort1 ({})", trunk1Id)

        sendFrame(vm1_1ExtPort.getId, List(trunk1Id),
            vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, vlanId1)
        sendFrame(vm2_1ExtPort.getId, List(trunk1Id),
            vm2_1Mac, trunkMac, vm2_1Ip, trunkIp, vlanId2)

        log.debug("Sending to VM1's MAC but with the wrong vlan id (2)")
        sendFrame(trunk1Id, List(trunk2Id, vm2_1ExtPort.getId, vm2_2ExtPort.getId),
            trunkMac, vm1_1Mac, trunkIp, vm1_1Ip, vlanId2,
            vlanOnInject = true)

    }

}
