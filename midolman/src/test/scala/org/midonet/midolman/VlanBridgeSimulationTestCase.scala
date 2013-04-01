/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Bridge, VlanAwareBridge}
import org.midonet.cluster.data.ports.{MaterializedBridgePort, LogicalBridgePort, TrunkPort, LogicalVlanBridgePort}
import org.midonet.packets._
import org.junit.Test
import org.slf4j.LoggerFactory
import org.midonet.midolman.PacketWorkflow.PacketIn
import topology.VirtualTopologyActor.{BridgeRequest, PortRequest}
import topology.{VirtualTopologyActor, LocalPortActive}
import org.midonet.midolman.FlowController.{InvalidateFlowsByTag, WildcardFlowAdded}
import util.SimulationHelper
import akka.testkit.TestProbe
import java.nio.ByteBuffer
import java.util.UUID
import org.midonet.midolman.DeduplicationActor.DiscardPacket
import akka.util.Duration
import java.util.concurrent.TimeUnit
import org.midonet.odp.flows.{FlowActionPushVLAN, FlowActionOutput}


@RunWith(classOf[JUnitRunner])
class VlanBridgeSimulationTestCase extends MidolmanTestCase
with VirtualConfigurationBuilders with SimulationHelper {

    private val log = LoggerFactory.getLogger(this.getClass)

    private var trunkPort1: TrunkPort = null
    private var trunkPort2: TrunkPort = null
    private var intVlanPort1: LogicalVlanBridgePort = null
    private var intVlanPort2: LogicalVlanBridgePort = null
    private var br1IntPort: LogicalBridgePort = null
    private var br2IntPort: LogicalBridgePort = null
    private var vm1_1ExtPort: MaterializedBridgePort = null
    private var vm2_1ExtPort: MaterializedBridgePort = null
    private var vm2_2ExtPort: MaterializedBridgePort = null

    private var vlanBr: VlanAwareBridge = null
    private var br1: Bridge = null // vlan 1
    private var br2: Bridge = null // vlan 2

    private val vlanId1: Short = 101
    private val vlanId2: Short = 202

    // MAC of something plugged at the exterior ports of the vlan-bridge
    private val trunkMac = MAC.fromString("aa:bb:cc:dd:dd:ff")
    private val vm1_1Mac = MAC.fromString("aa:bb:aa:bb:cc:cc")
    private val vm2_1Mac = MAC.fromString("cc:cc:aa:aa:bb:bb")
    private val vm2_2Mac = MAC.fromString("cc:cc:aa:aa:ee:ee")

    private val trunkIp = IPv4Addr.fromString("10.1.1.1")
    private val vm1_1Ip = IPv4Addr.fromString("10.1.1.1")
    private val vm2_1Ip = IPv4Addr.fromString("10.2.2.2")
    private val vm2_2Ip = IPv4Addr.fromString("10.2.2.2")

    private var packetEventsProbe: TestProbe = null

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    override def beforeTest() {

        val host = newHost("host1", hostId())

        vlanBr = newVlanAwareBridge("vlan-bridge")
        br1 = newBridge("bridge1")
        br2 = newBridge("bridge2")

        // Ports on the VLAN bridge
        trunkPort1 = newVlanBridgeTrunkPort(vlanBr)
        trunkPort2 = newVlanBridgeTrunkPort(vlanBr)
        trunkPort1 = clusterDataClient().portsGet(trunkPort1.getId).asInstanceOf[TrunkPort]
        trunkPort2 = clusterDataClient().portsGet(trunkPort2.getId).asInstanceOf[TrunkPort]

        // Exterior ports on the bridges
        vm1_1ExtPort = newExteriorBridgePort(br1)
        vm2_1ExtPort = newExteriorBridgePort(br2)
        vm2_2ExtPort = newExteriorBridgePort(br2)

        // Logical ports connecting the Vlan-bridge to each bridge
        intVlanPort1 = newInteriorVlanBridgePort(vlanBr, vlanId1)
        intVlanPort2 = newInteriorVlanBridgePort(vlanBr, vlanId2)
        br1IntPort = newInteriorBridgePort(br1)
        br2IntPort = newInteriorBridgePort(br2)

        log.info("Binding Vlan-bridge logical ports to bridges interior ports")
        clusterDataClient().portsLink(intVlanPort1.getId, br1IntPort.getId)
        clusterDataClient().portsLink(intVlanPort2.getId, br2IntPort.getId)

        materializePort(trunkPort1, host, "trunkPort1")
        materializePort(trunkPort2, host, "trunkPort2")
        materializePort(vm1_1ExtPort, host, "vm1_1Port")
        materializePort(vm2_1ExtPort, host, "vm2_1Port")
        materializePort(vm2_2ExtPort, host, "vm2_2Port")

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady]
            .datapath should not be (null)

        for(i <- 1.to(5)) log.info(" EXT. PORT ACTIVE: {}",
            portsProbe.expectMsgClass(classOf[LocalPortActive]))

        drainProbes()

        packetEventsProbe = newProbe()
        actors().eventStream.subscribe(packetEventsProbe.ref, classOf[PacketsExecute])

        // Request ports for the first time so that we trigger associated
        // flow invalidations now and they don't impact expects during the tests
        val vta = VirtualTopologyActor.getRef(actors())
        ask(vta, PortRequest(trunkPort1.getId, update = false))
        ask(vta, PortRequest(trunkPort2.getId, update = false))
        ask(vta, PortRequest(intVlanPort1.getId, update = false))
        ask(vta, PortRequest(intVlanPort2.getId, update = false))
        ask(vta, PortRequest(br1IntPort.getId, update = false))
        ask(vta, PortRequest(br2IntPort.getId, update = false))
        ask(vta, BridgeRequest(br1.getId, update = false))
        ask(vta, BridgeRequest(br2.getId, update = false))

        drainProbes()
        drainProbe(packetEventsProbe)

    }

    private def feedBridgeArpCaches() {
        feedArpCache("vm1_1Port", vm1_1Ip.toInt, vm1_1Mac, vm2_1Ip.toInt, vm2_1Mac)
        feedArpCache("vm2_1Port", vm2_1Ip.toInt, vm2_1Mac, vm1_1Ip.toInt, vm1_1Mac)
        feedArpCache("vm2_2Port", vm2_2Ip.toInt, vm2_2Mac, vm1_1Ip.toInt, vm1_1Mac)
        // TODO (galo) replace this sleep with appropriate probing
        Thread.sleep(2000)
        drainProbes()
        drainProbe(packetEventsProbe)
    }

    private def getPortName (portNo: UUID): String = {
        // TODO this doesn't seem to work with match :(
        if (portNo.equals(trunkPort1.getId)) "trunkPort1"
        else if (portNo.equals(trunkPort2.getId)) "trunkPort2"
        else if (portNo.equals(vm1_1ExtPort.getId)) "vm1_1Port"
        else if (portNo.equals(vm2_1ExtPort.getId)) "vm2_1Port"
        else if (portNo.equals(vm2_2ExtPort.getId)) "vm2_2Port"
        else null
    }

    // TODO(galo) review this method, copied in a rush from
    // BridgeSimulationTestCase
    private def injectOnePacket (ethPkt : Ethernet, ingressPortId: UUID,
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
    private def sendFrame(fromPort: UUID, toPorts: List[UUID],
                          fromMac: MAC, toMac: MAC,
                          fromIp: IPv4Addr, toIp: IPv4Addr,
                          vlanId: Short, vlanOnInject: Boolean = false,
                          expectFlowAdded: Boolean = true) {

        val ethRaw = Packets.udp(fromMac, toMac,
            fromIp.toIntIPv4, toIp.toIntIPv4,
            10, 11, "hello".getBytes)

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
        val ethRcv = expectPacketOut(toPortNos, packetEventsProbe,
                                     vlanIdsToPush, vlanIdsToPop)

        log.debug("SENT: {}", inEth)
        log.debug("GOT: {}", ethRcv)
        // Note that actions do not get applied at this point, so the ingress
        // and egress packets will be the same. Since we told expectPacketOut
        // to expect a vlanPushPop we can trust that the relevant actions to
        // remove the vlan id is there.
        ethRcv should be === inEth

    }

    private def arpReq(srcMac: MAC, srcIp: Int, dstIp: Int, vlanId: Short) = {
        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6)
        arp.setProtocolAddressLength(4)
        arp.setOpCode(ARP.OP_REQUEST)
        arp.setSenderHardwareAddress(srcMac)
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(srcIp))
        arp.setTargetHardwareAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(dstIp))
        val eth = new Ethernet()
        eth.setPayload(arp)
        eth.setSourceMACAddress(trunkMac)
        eth.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        eth.setEtherType(ARP.ETHERTYPE)
        if (vlanId != 0)
            eth.setVlanID(vlanId)
        eth
    }

    private def expectBroadCast(materializedPort: Short,trunk1: Short,
    trunk2: Short, vlanId: Short) = {
        val msg = packetEventsProbe.expectMsgClass(classOf[PacketsExecute])
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
        packetEventsProbe.expectNoMsg()

        log.info("ARP reply from trunk to VM 2_1")
        val arpRepEth = ARP.makeArpReply(trunkMac, vm2_1Mac,
            IPv4.toIPv4AddressBytes(trunkIp.toInt),
            IPv4.toIPv4AddressBytes(vm2_1Ip.toInt))
        arpRepEth.setVlanID(vlanId2)
        triggerPacketIn("trunkPort1", arpRepEth)

        val inEth = expectPacketOut(List(getPortNumber("vm2_1Port")),
                                    packetEventsProbe, List(), List(vlanId2))
        inEth.getEtherType should be === ARP.ETHERTYPE

        // Again, no other packets should've been transmitted
        packetEventsProbe.expectNoMsg()
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
        var toPorts = List[Int](getPortNumber("vm2_1Port"),
                                getPortNumber("vm2_2Port"))
        var inEth = expectPacketOut(toPorts, packetEventsProbe,
                                    List(), List(vlanId2))
        inEth.getEtherType should be === ARP.ETHERTYPE
        inEth.getPayload.asInstanceOf[ARP].getOpCode should be === ARP.OP_REQUEST
        inEth.getSourceMACAddress should be === trunkMac
        inEth.getDestinationMACAddress should be === MAC.fromString("ff:ff:ff:ff:ff:ff")

        // no other packet should be transmitted
        packetEventsProbe.expectNoMsg()

        log.info("ARP reply from VM to trunk")
        val arpRepEth = ARP.makeArpReply(vm2_1Mac, trunkMac,
                                        IPv4.toIPv4AddressBytes(vm2_1Ip.toInt),
                                        IPv4.toIPv4AddressBytes(trunkIp.toInt))
        triggerPacketIn("vm2_1Port", arpRepEth)

        toPorts = List[Int](getPortNumber("trunkPort1"),
                            getPortNumber("trunkPort2"))
        inEth = expectPacketOut(toPorts, packetEventsProbe,
                                List(vlanId2), List())
        inEth.getEtherType should be === ARP.ETHERTYPE
        inEth.getSourceMACAddress should be === vm2_1Mac
        inEth.getDestinationMACAddress should be === trunkMac

        // Again, no other packets should've been transmitted
        packetEventsProbe.expectNoMsg()

    }

    /**
     * Sends frames from the trunks, then from the VMs, expects that actions
     * are correctly injected.
     */
    @Test
    def testFrameExchangeThroughVlanBridge() {
        feedBridgeArpCaches()
        sendFrame(trunkPort1.getId, List(vm1_1ExtPort.getId), trunkMac,
                  vm1_1Mac, trunkIp, vm1_1Ip, vlanId1, vlanOnInject = true)
        sendFrame(trunkPort1.getId, List(vm2_1ExtPort.getId), trunkMac,
                  vm2_1Mac, trunkIp, vm2_1Ip, vlanId2, vlanOnInject = true)

        log.debug("The bridge plugged to VM1 has learned trunkMac, active " +
            "trunk port is trunkPort1 ({})", trunkPort1.getId)

        sendFrame(vm1_1ExtPort.getId, List(trunkPort1.getId, trunkPort2.getId),
                  vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, vlanId1)
        sendFrame(vm2_1ExtPort.getId, List(trunkPort1.getId, trunkPort2.getId),
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
        sendFrame(trunkPort1.getId, List(vm1_1ExtPort.getId), trunkMac,
                  vm1_1Mac, trunkIp, vm1_1Ip, vlanId1, vlanOnInject = true)

        // Send a frame from VM1 without VLAN ID (normal case)
        sendFrame(vm1_1ExtPort.getId, List(trunkPort1.getId, trunkPort2.getId),
                  vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, vlanId1)

        // Now send a frame but with some private VLAN ID
        val eth = Packets.udp(vm1_1Mac, trunkMac, vm1_1Ip.toIntIPv4,
                              trunkIp.toIntIPv4, 10, 11, "hello".getBytes)
        eth.setVlanID(666)

        var trunks = Set(getPortNumber("trunkPort1"),
                         getPortNumber("trunkPort2"))
        val addFlowMsg = injectOnePacket(eth, vm1_1ExtPort.getId)
        addFlowMsg match {
            case None => fail("Expecting a flow added")
            case Some(msg: WildcardFlowAdded) =>
                msg.f.getActions should not be null
                msg.f.getActions should have size (3)
                msg.f.getActions foreach ( act => act match {
                    case a: FlowActionOutput => trunks -= a.getPortNumber
                    case a: FlowActionPushVLAN =>
                        (a.getTagControlIdentifier & 0x0fff) should be === (vlanId1)
            })
        }
        trunks should have size (0)
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
        sendFrame(trunkPort1.getId, List(vm1_1ExtPort.getId), trunkMac,
                  vm1_1Mac, trunkIp, vm1_1Ip, vlanId1, vlanOnInject = true)
        sendFrame(vm1_1ExtPort.getId, List(trunkPort1.getId, trunkPort2.getId),
                  vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, vlanId1)

        log.info("Now let's send a frame from trunk port 2")
        sendFrame(trunkPort2.getId, List(vm2_2ExtPort.getId), trunkMac,
                  vm2_2Mac, trunkIp, vm2_2Ip, vlanId2, vlanOnInject = true)

        log.info("Sending frames from VMs, should go to the two trunks, hitting flows")
        sendFrame(vm1_1ExtPort.getId, List(trunkPort1.getId, trunkPort2.getId),
                  vm1_1Mac, trunkMac, vm1_1Ip, trunkIp, vlanId1,
                  vlanOnInject = false, expectFlowAdded = false)
    }

    /**
     * Tests that the vlan-bridge drops a frame if it brings a vlan-id that
     * is not assigned to one of its logical ports.
     */
    @Test
    def testFrameFromTrunkWithUnknownVlanId() {
        feedBridgeArpCaches()
        val eth = Packets.udp(trunkMac, vm1_1Mac, trunkIp.toIntIPv4,
                              vm1_1Ip.toIntIPv4, 10, 11, "hello".getBytes)
        eth.deserialize(ByteBuffer.wrap(eth.serialize()))
        eth.setVlanID(500)
        injectOnePacket(eth, trunkPort1.getId)
        discardPacketProbe.expectMsgClass(classOf[DiscardPacket])
    }

    /**
     *
     * Send a broadcast from the trunk to a specific vlan
     */
    @Test
    def testBroadcastFrameWithVlanIdFromTrunk() {
        feedBridgeArpCaches()
        sendFrame(trunkPort1.getId,
            List(vm2_1ExtPort.getId, vm2_2ExtPort.getId), trunkMac,
            MAC.fromString("ff:ff:ff:ff:ff:ff"), trunkIp,
            vm2_1Ip, vlanId2, vlanOnInject = true)
    }

    /**
     * Send a broadcast from a vm in a vlan, should get to the trunk
     *
     * TODO (galo) test that if we send a broadcast first without knowing an
     * active trunk, then learn the trunk, then send another broadcast, things
     * go well - depends on flow invalidation
     */
    @Test
    def testBroadcastFrameWithoutVlanIdFromVM() {
        feedBridgeArpCaches()
        // learn an active trunk first
        sendFrame(trunkPort1.getId, List(vm2_1ExtPort.getId), trunkMac, vm2_1Mac,
            trunkIp, vm2_1Ip, vlanId2, vlanOnInject = true)

        val fromMac = vm2_1Mac
        val toMac = MAC.fromString("ff:ff:ff:ff:ff:ff")
        val fromIp = vm2_1Ip.toIntIPv4
        val toIp = trunkIp.toIntIPv4

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
    def testUnknownTrunk() {
        feedBridgeArpCaches()
        // Broadcast frame from the VM, no trunk is learned yet
        sendFrame(vm1_1ExtPort.getId, List(trunkPort1.getId, trunkPort2.getId),
            vm1_1Mac, MAC.fromString("ff:ff:ff:ff:ff:ff"), vm1_1Ip,
            trunkIp, vlanId1, vlanOnInject = false)
    }

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
        doBpdu(trunkPort1.getId, trunkPort2.getId)
        doBpdu(trunkPort2.getId, trunkPort1.getId)
    }

    /**
     * TODO (galo, rossella)
     */
    @Test
    def testTrunksUpDown() {
        log.warn("-------- --------- -------- NOT IMPLEMENTED !!!!!! ")
    }

    /*
     * TODO - TESTS MIRRORED FROM Bridge tests
     *
     * These tests reproduce identical cases to the ones tested for the
     * vlan-unaware bridge, but having traffic go through the vlan-aware bridge
     *
     * The goal is to ensure that L2 functionality keeps working normally
     * when we add the vlan-aware bridge in the picture.
     */

}