/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman

import java.util.UUID
import scala.collection.JavaConversions._
import scala.collection.immutable.HashMap
import scala.collection.mutable

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.{BridgePort, RouterPort}
import org.midonet.midolman.FlowController.RemoveWildcardFlow
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.FlowController.WildcardFlowRemoved
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.topology.BridgeManager.CheckExpiredMacPorts
import org.midonet.midolman.topology.FlowTagger
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.BridgeRequest
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.VirtualTopologyActor.RouterRequest
import org.midonet.midolman.util.{SimulationHelper, TestHelpers}
import org.midonet.odp.Datapath
import org.midonet.odp.flows.{FlowAction, FlowActions}
import org.midonet.packets.{IPv4Subnet, IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class BridgeInvalidationTestCase extends MidolmanTestCase
        with VirtualConfigurationBuilders with SimulationHelper {

    var datapath: Datapath = null

    val ipVm1 = "10.10.0.1"
    val ipVm2 = "11.11.0.10"
    // this is the network reachable from outPort
    val networkToReach = "11.11.0.0"
    val networkToReachLength = 16
    val ipSource = "20.20.0.20"
    val macVm1 = "02:11:22:33:44:10"
    val macVm2 = "02:11:22:33:46:10"

    val macSource = "02:11:22:33:44:11"

    var routeId: UUID = null
    val port1Name = "port1"
    val port2Name = "port2"
    val port3Name = "port3"

    val routerMac = MAC.fromString("02:11:22:33:46:13")
    val routerIp = new IPv4Subnet("11.11.11.1", 32)

    var bridge: Bridge = null
    var host: Host = null
    var port1: BridgePort = null
    var port2: BridgePort = null
    var port3: BridgePort = null
    var rtrPort: RouterPort = null
    var brPort1: BridgePort = null
    val macPortExpiration = 1000

    var mapPortNameShortNumber: Map[String,Short] = new HashMap[String, Short]()

    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        //config.setProperty("datapath.max_flow_count", 3)
        //config.setProperty("midolman.check_flow_expiration_interval", 10)
        config.setProperty("monitoring.enable_monitoring", "false")
        config.setProperty("cassandra.servers", "localhost:9171")
        config.setProperty("bridge.mac_port_mapping_expire_millis", macPortExpiration)
        config
    }

    override def beforeTest() {

        drainProbes()

        host = newHost("myself", hostId())
        bridge = newBridge("bridge")

        bridge should not be null

        val router = newRouter("router")
        router should not be null

        // set up logical port on router
        rtrPort = newRouterPort(router, routerMac,
            routerIp.toUnicastString,
            routerIp.toNetworkAddress.toString,
            routerIp.getPrefixLen)
        rtrPort should not be null

        brPort1 = newBridgePort(bridge)
        brPort1 should not be null


        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        port1 = newBridgePort(bridge)
        port2 = newBridgePort(bridge)
        port3 = newBridgePort(bridge)

        //requestOfType[WildcardFlowAdded](flowProbe)
        materializePort(port1, host, port1Name)
        mapPortNameShortNumber += port1Name -> 1
        requestOfType[LocalPortActive](portsProbe)
        materializePort(port2, host, port2Name)
        mapPortNameShortNumber += port2Name -> 2
        requestOfType[LocalPortActive](portsProbe)
        materializePort(port3, host, port3Name)
        mapPortNameShortNumber += port3Name -> 3
        requestOfType[LocalPortActive](portsProbe)

        // this is added when the port becomes active. A flow that takes care of
        // the tunnelled packets to this port
        // wait for these WC events and take them out of the probe
        ackWCAdded() // port 1
        ackWCAdded() // port 2
        ackWCAdded() // port 3
    }

    def testLearnMAC() {
        // this packet should be flooded
        triggerPacketIn(port1Name,
            TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        fishForRequestOfType[PacketIn](packetInProbe)
        val add1 = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // TODO: check that add1 actions are correct

        // let's make the bridge learn vmMac2.
        // the first flow will be removed, and a new one will be added
        triggerPacketIn(port2Name,
            TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))
        val rem1 = wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        val add2 = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // check the first removal is correct
        rem1.f.actions.equals(add1.f.actions) should be (true)

        // TODO: check that add2 actions are correct

        // Now store the add message for comparison with the next removal.
        // The MacPortExpiration is set to only one second. The association
        // between port1 and macVm1 will expire approximately one second after
        // its only representative flow was removed. At that point, any flow
        // directed at macVm1 is invalidated.

        // this creates many spurious failure on Jenkins which I can't reproduce
        // locally. For now
        //val rem2 = wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        // check the second removal is correct
        //rem2.f.actions.equals(add2.f.actions) should be (true)

        //wflowAddedProbe.expectNoMsg()
        //wflowRemovedProbe.expectNoMsg()

    }

    def testLearnMAC2() {

        val dpFlowAddedProbe = newProbe()
        actors().eventStream.subscribe(dpFlowAddedProbe.ref, classOf[FlowAdded])

        val dpFlowRemovedProbe = newProbe()
        actors().eventStream.subscribe(dpFlowRemovedProbe.ref, classOf[FlowRemoved])

        // Inject a packet from 1 to 2.
        val udp = TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2)
        triggerPacketIn(port1Name, udp)
        val pktIn1 = packetInProbe.expectMsgClass(classOf[PacketIn])
        pktIn1.wMatch.getInputPort should be (getPortNumber(port1Name).toShort)

        // expect one wild flow to be added
        val add1 = ackWCAdded()
        val outports = actionsToOutputPorts(add1.actions)
        outports should have size (2)
        outports should contain (getPortNumber(port2Name).toShort)
        outports should contain (getPortNumber(port3Name).toShort)

        // one dp flow should also have been added and one packet forwarded
        dpFlowAddedProbe.expectMsgClass(classOf[FlowAdded])
        mockDpConn().flowsTable.keySet() should contain (pktIn1.dpMatch)
        mockDpConn().packetsSent.length should be (1)
        mockDpConn().packetsSent.get(0).getMatch should be (pktIn1.dpMatch)

        // Resend the packet. No new wild flow, but the packet is sent.
        triggerPacketIn(port1Name, udp)
        wflowRemovedProbe.expectNoMsg()
        wflowAddedProbe.expectNoMsg()
        mockDpConn().packetsSent.length should be (2)
        mockDpConn().packetsSent.get(1).getMatch() should be (pktIn1.dpMatch)

        drainProbes()

        // let's make the bridge learn vmMac2. The first wild and dp flows
        // should be removed. One new wild and dp flow should be added.
        triggerPacketIn(port2Name,
            TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))
        val pktIn2 = packetInProbe.expectMsgClass(classOf[PacketIn])
        pktIn2.wMatch.getInputPort should be (getPortNumber(port2Name).toShort)

        // expect one wc flow removed and check it matches the first wc added
        val rem = ackWCRemoved()
        rem.actions should be === (add1.actions)

        // the corresponding dp flow should have been removed too
        dpFlowRemovedProbe.expectMsgClass(classOf[FlowRemoved])

        // Now check that the new wild flow is correct. Forward to port1 only.
        val add2 = ackWCAdded()
        val outports2 = actionsToOutputPorts(add2.getActions)
        outports2 should have size (1)
        outports2 should contain (getPortNumber(port1Name).toShort)

        // the matching dp flow should be installed
        dpFlowAddedProbe.expectMsgClass(classOf[FlowAdded])

        mockDpConn().flowsTable.keySet() should not contain (pktIn1.dpMatch)
        mockDpConn().flowsTable.keySet() should contain (pktIn2.dpMatch)
        mockDpConn().packetsSent.length should be (3)
        mockDpConn().packetsSent.get(2).getMatch should be (pktIn2.dpMatch)

        drainProbes()

        // Resend the first packet. It's now forwarded only to port2.
        triggerPacketIn(port1Name, udp)

        // expect one wild flow to be added
        val add3 = ackWCAdded()
        val outports3 = actionsToOutputPorts(add3.getActions)
        outports3 should have size (1)
        outports3 should contain (getPortNumber(port2Name).toShort)

        // one dp flow should also have been added
        dpFlowAddedProbe.expectMsgClass(classOf[FlowAdded])
        mockDpConn().flowsTable.keySet() should contain (pktIn1.dpMatch)
        mockDpConn().packetsSent.length should be (4)
        mockDpConn().packetsSent.get(3).getMatch should be (pktIn1.dpMatch)

    }

    def testFlowCountZeroForgetMac() {
        triggerPacketIn(port1Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        fishForRequestOfType[PacketIn](packetInProbe)
        val floodedFlow = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's make the bridge learn vmMac2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))
        // this is the flooded flow that has been invalidated

        ackWCRemoved()

        // this is the flow from 2 to 1
        val flowToRemove = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's create another flow from 1 to 2
        triggerPacketIn(port1Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        val flowShouldBeInvalidated = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // this will make the flowCount for vmMac2 go to 0, so it will be unlearnt
        // and the flow from 1 to 2 should get invalidated
        flowProbe().testActor ! RemoveWildcardFlow(flowToRemove.f.getMatch)
        fishForRequestOfType[WildcardFlowRemoved](wflowRemovedProbe)
        // wait for the flow to be removed
        Thread.sleep(macPortExpiration + 100)
        val bridgeManagerPath = virtualTopologyActor().path + "/" + bridge.getId.toString
        // Since the check for the expiration of the MAC port association is done
        // every 2 seconds, let's trigger it
        actors().actorFor(bridgeManagerPath) ! new CheckExpiredMacPorts()
        // expect flow invalidation

        ackWCRemoved()
    }

    def testVmMigration() {
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        fishForRequestOfType[PacketIn](packetInProbe)
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's make the bridge learn vmMac2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))
        // this is the flooded flow that has been invalidated
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        // this is the flow from 2 to 1
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's create another flow from 1 to 2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        val flowShouldBeInvalidated = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // vm2 migrate to port 3
        triggerPacketIn(port3Name, TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))

        // this will trigger the invalidation of flows tagged bridgeId + MAC + oldPort
        ackWCRemoved()
    }

    def testUnicastAddLogicalPort() {
        drainProbes()
        // trigger packet in before linking the port. Since routerMac is unknown
        // the bridge logic will flood the packet
        triggerPacketIn(port1Name, TestHelpers.createUdpPacket(macVm1, ipVm1,
            routerMac.toString, routerIp.getAddress.toString))

        val flowShouldBeInvalidated =
            wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        clusterDataClient().portsLink(rtrPort.getId, brPort1.getId)

        ackWCRemoved()

        // Expect the invalidation of the following flows:
        // 1. ARP requests
        // 2. All the flows to routerMac that were flooded
        flowProbe().fishForMessage(timeout, "InvalidateFlowsByTag")(
            TestHelpers.matchFlowTag(
                FlowTagger.invalidateArpRequests(bridge.getId)))

        flowProbe().fishForMessage(timeout, "InvalidateFlowsByTag")(
        TestHelpers.matchFlowTag(
            FlowTagger.invalidateFloodedFlowsByDstMac(bridge.getId, routerMac,
                Bridge.UNTAGGED_VLAN_ID)))

    }

    def testArpRequestAddLogicalPort() {
        drainProbes()
        // send arp request before linking the port. The arp request will be flooded
        injectArpRequest(port1Name, IPv4Addr(ipVm1).addr,
            MAC.fromString(macVm1), routerIp.getAddress.addr)

        val flowShouldBeInvalidated =
            wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        clusterDataClient().portsLink(rtrPort.getId, brPort1.getId)

        wflowRemovedProbe.fishForMessage(timeout,
            "WildcardFlowRemoved")(TestHelpers.matchActionsFlowAddedOrRemoved(
            asScalaBuffer[FlowAction[_]](flowShouldBeInvalidated.f.getActions)))

        // Expect the invalidation of the following flows:
        // 1. ARP requests
        // 2. All the flows to routerMac that were flooded
        flowProbe().fishForMessage(timeout, "InvalidateFlowsByTag")(
            TestHelpers.matchFlowTag(
                FlowTagger.invalidateArpRequests(bridge.getId)))
        flowProbe().fishForMessage(timeout, "InvalidateFlowsByTag")(
            TestHelpers.matchFlowTag(
                FlowTagger.invalidateFloodedFlowsByDstMac(bridge.getId, routerMac,
                    Bridge.UNTAGGED_VLAN_ID)))
    }

    def testUnicastDeleteLogicalPort() {
        val vta = VirtualTopologyActor.getRef(actors())
        ask(vta, BridgeRequest(brPort1.getDeviceId, false))
        ask(vta, RouterRequest(rtrPort.getDeviceId, false))
        ask(vta, PortRequest(rtrPort.getId, false))
        ask(vta, PortRequest(brPort1.getId, false))

        clusterDataClient().portsLink(rtrPort.getId, brPort1.getId)
        dilatedSleep(1000)
        // guillermo: wait for all invalidations, the alternative to sleep()
        // is have 4 waitForMsg[InvalidateFlowByTag].in a row. That'd be
        // equally ugly and it'd more sensitive to code changes.
        drainProbes()

        triggerPacketIn(port1Name, TestHelpers.createUdpPacket(macVm1, ipVm1,
            routerMac.toString, routerIp.getAddress.toString))

        val flowShouldBeInvalidated =
            wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        clusterDataClient().portsUnlink(brPort1.getId)

        ackWCRemoved()

        // All the flows to the logical port should be invalidated
        flowProbe().fishForMessage(timeout, "InvalidateFlowsByTag")(
            TestHelpers.matchFlowTag(
                FlowTagger.invalidateFlowsByLogicalPort(bridge.getId, brPort1.getId)))
    }
}
