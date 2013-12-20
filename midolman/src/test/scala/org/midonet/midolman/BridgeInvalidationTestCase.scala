/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman

import java.util.UUID
import scala.collection.JavaConversions._
import scala.collection.immutable.HashMap

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports._
import org.midonet.midolman.FlowController.RemoveWildcardFlow
import org.midonet.midolman.simulation.{Router => RCURouter}
import org.midonet.midolman.topology.BridgeManager.CheckExpiredMacPorts
import org.midonet.midolman.topology.FlowTagger
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.BridgeRequest
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.VirtualTopologyActor.RouterRequest
import org.midonet.midolman.util.{SimulationHelper, TestHelpers}
import org.midonet.odp.Datapath
import org.midonet.packets._
import org.midonet.midolman.layer3.Route.NextHop

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class BridgeInvalidationTestCase extends MidolmanTestCase
                                 with SimulationHelper
                                 with VirtualConfigurationBuilders {

    var datapath: Datapath = null

    val ipVm1 = "10.10.0.1"
    val ipVm2 = "11.11.0.10"
    val macVm1 = "02:11:22:33:44:10"
    val macVm2 = "02:11:22:33:46:10"

    val macUplink = "02:11:22:44:55:aa"
    val ipUplink = "192.168.1.254"
    var uplinkNetAddress = "192.168.1.0"
    var uplinkNetmask = 24
    var uplinkPortMac = "02:11:22:44:55:bb"
    var uplinkPortAddress = "192.168.1.1"
    var uplinkPort: RouterPort = null
    var upLinkRoute: UUID = null

    var routeId: UUID = null
    val port1Name = "port1"
    val port2Name = "port2"
    val port3Name = "port3"

    val routerMac = MAC.fromString("02:11:22:33:46:13")
    val routerIp = new IPv4Subnet("11.11.11.1", 24)

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

        uplinkPort = newRouterPort(router, MAC.fromString(uplinkPortMac),
            uplinkPortAddress, uplinkNetAddress, uplinkNetmask)
        materializePort(uplinkPort, host, "uplinkPort")

        upLinkRoute = newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0,
            NextHop.PORT, uplinkPort.getId, ipUplink, 1)
        upLinkRoute should not be null

        val vta = VirtualTopologyActor.getRef()
        val simRouter: RCURouter = askAndAwait(vta, RouterRequest(router.getId, false))
        simRouter.arpTable.set(IPv4Addr(ipUplink), MAC.fromString(macUplink))

        // this is added when the port becomes active. A flow that takes care of
        // the tunnelled packets to this port
        // wait for these WC events and take them out of the probe
        ackWCAdded() // port 1
        ackWCAdded() // port 2
        ackWCAdded() // port 3
        ackWCAdded() // port 4

        drainProbes()
    }

    def udp1to2() = TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2)

    def udp2to1() = TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1)

    def udp1toRouter() = TestHelpers.createUdpPacket(
            macVm1, ipVm1, routerMac.toString, routerIp.getAddress.toString)

    def udp2toUplink(): Ethernet = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr macVm2 -> routerMac } <<
            { ip4 addr ipVm2 --> ipUplink } <<
                { udp src 2020 dst 53 } <<
                    payload("A DNS Packet")
    }

    def triggerUdp1to2() { triggerPacketIn(port1Name, udp1to2()) }

    def triggerUdp2to1() { triggerPacketIn(port2Name, udp2to1()) }

    def triggerUdp1toRouter() { triggerPacketIn(port1Name, udp1toRouter()) }

    def triggerUdp2toUplink() { triggerPacketIn(port2Name, udp2toUplink()) }

    def testLearnMAC() {
        // this packet should be flooded
        triggerUdp1to2()
        expectPacketIn()
        val add1 = ackWCAdded()

        // TODO: check that add1 actions are correct

        // let's make the bridge learn vmMac2.
        // the first flow will be removed, and a new one will be added
        triggerUdp2to1()
        val rem1 = ackWCRemoved()
        val add2 = ackWCAdded()

        // check the first removal is correct
        rem1.actions.equals(add1.actions) should be (true)

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

        val dpFlowAddProbe = makeEventProbe(classOf[FlowAdded])
        val dpFlowRemProbe = makeEventProbe(classOf[FlowRemoved])

        // Inject a packet from 1 to 2.
        triggerUdp1to2()
        val pktIn1 = expectPacketIn()
        //val pktIn1 = packetInProbe.expectMsgClass(classOf[PacketIn])
        pktIn1.wMatch.getInputPort should be (getPortNumber(port1Name).toShort)

        // expect one wild flow to be added
        val add1 = ackWCAdded()
        val outports = actionsToOutputPorts(add1.actions)
        outports should have size (2)
        outports should contain (getPortNumber(port2Name).toShort)
        outports should contain (getPortNumber(port3Name).toShort)

        // one dp flow should also have been added and one packet forwarded
        dpFlowAddProbe.expectMsgClass(classOf[FlowAdded])
        mockDpConn().flowsTable.keySet() should contain (pktIn1.dpMatch)
        mockDpConn().packetsSent.length should be (1)
        mockDpConn().packetsSent.get(0).getMatch should be (pktIn1.dpMatch)

        drainProbes()

        // Resend the packet. No new wild flow, but the packet is sent.
        triggerUdp1to2()
        wflowRemovedProbe.expectNoMsg()
        wflowAddedProbe.expectNoMsg()
        mockDpConn().packetsSent.length should be (2)
        mockDpConn().packetsSent.get(1).getMatch() should be (pktIn1.dpMatch)

        drainProbes()

        // let's make the bridge learn vmMac2. The first wild and dp flows
        // should be removed. One new wild and dp flow should be added.
        triggerUdp2to1()
        val pktIn2 = expectPacketIn()
        //val pktIn2 = packetInProbe.expectMsgClass(classOf[PacketIn])
        pktIn2.wMatch.getInputPort should be (getPortNumber(port2Name).toShort)

        // expect one wc flow removed and check it matches the first wc added
        val rem = ackWCRemoved()
        rem.actions should be ((add1.actions))

        // the corresponding dp flow should have been removed too
        dpFlowRemProbe.expectMsgClass(classOf[FlowRemoved])

        // Now check that the new wild flow is correct. Forward to port1 only.
        val add2 = ackWCAdded()
        val outports2 = actionsToOutputPorts(add2.getActions)
        outports2 should have size (1)
        outports2 should contain (getPortNumber(port1Name).toShort)

        // the matching dp flow should be installed
        dpFlowAddProbe.expectMsgClass(classOf[FlowAdded])

        mockDpConn().flowsTable.keySet() should not contain (pktIn1.dpMatch)
        mockDpConn().flowsTable.keySet() should contain (pktIn2.dpMatch)
        mockDpConn().packetsSent.length should be (3)
        mockDpConn().packetsSent.get(2).getMatch should be (pktIn2.dpMatch)

        drainProbes()

        // Resend the first packet. It's now forwarded only to port2.
        triggerUdp1to2()

        // expect one wild flow to be added
        val add3 = ackWCAdded()
        val outports3 = actionsToOutputPorts(add3.getActions)
        outports3 should have size (1)
        outports3 should contain (getPortNumber(port2Name).toShort)

        // one dp flow should also have been added
        dpFlowAddProbe.expectMsgClass(classOf[FlowAdded])
        mockDpConn().flowsTable.keySet() should contain (pktIn1.dpMatch)
        mockDpConn().packetsSent.length should be (4)
        mockDpConn().packetsSent.get(3).getMatch should be (pktIn1.dpMatch)

    }

    def testFlowCountZeroForgetMac() {
        triggerUdp1to2()
        expectPacketIn()
        ackWCAdded()

        // let's make the bridge learn vmMac2
        triggerUdp2to1()

        // this is the flooded flow that has been invalidated
        ackWCRemoved()

        // this is the flow from 2 to 1
        val flowToRemove = ackWCAdded()
        // let's create another flow from 1 to 2
        triggerUdp1to2()
        ackWCAdded()

        // this will make the flowCount for vmMac2 go to 0, so it will be unlearnt
        // and the flow from 1 to 2 should get invalidated
        flowProbe().testActor ! RemoveWildcardFlow(flowToRemove.getMatch)
        ackWCRemoved()

        // wait for the flow to be removed
        Thread.sleep(macPortExpiration + 100)

        // Since the check for the expiration of the MAC port association is
        // done every 2 seconds, let's trigger it
        val bridgeManagerPath =
            virtualTopologyActor().path + "/" + bridge.getId.toString
        actors().actorFor(bridgeManagerPath) ! new CheckExpiredMacPorts()

        // expect flow invalidation
        ackWCRemoved()
    }

    def testVmMigration() {
        triggerPacketIn(port2Name, udp1to2())
        expectPacketIn()
        ackWCAdded()

        // let's make the bridge learn vmMac2
        triggerUdp2to1

        // this is the flooded flow that has been invalidated
        ackWCRemoved()

        // this is the flow from 2 to 1
        ackWCAdded()

        // let's create another flow from 1 to 2
        triggerPacketIn(port2Name, udp1to2())
        ackWCAdded()

        // vm2 migrate to port 3
        triggerPacketIn(port3Name, udp2to1())

        // this will trigger the invalidation of flows tagged bridgeId + MAC + oldPort
        ackWCRemoved()
    }

    def testUnicastAddLogicalPort() {
        // trigger packet in before linking the port. Since routerMac is unknown
        // the bridge logic will flood the packet
        triggerUdp1toRouter()
        ackWCAdded()

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
        // send arp request before linking the port.
        // The arp request will be flooded
        injectArpRequest(port1Name, IPv4Addr(ipVm1).addr,
            MAC.fromString(macVm1), routerIp.getAddress.addr)
        val wcflowAdd = ackWCAdded()

        clusterDataClient().portsLink(rtrPort.getId, brPort1.getId)
        val wflowRem = ackWCRemoved()

        wflowRem.getActions should be (wcflowAdd.getActions)

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
        askAndAwait(VirtualTopologyActor, BridgeRequest(brPort1.getDeviceId))
        askAndAwait(VirtualTopologyActor, RouterRequest(rtrPort.getDeviceId))
        askAndAwait(VirtualTopologyActor, PortRequest(rtrPort.getId))
        askAndAwait(VirtualTopologyActor, PortRequest(brPort1.getId))

        clusterDataClient().portsLink(rtrPort.getId, brPort1.getId)
        dilatedSleep(1000)
        // guillermo: wait for all invalidations, the alternative to sleep()
        // is have 4 waitForMsg[InvalidateFlowByTag].in a row. That'd be
        // equally ugly and it'd more sensitive to code changes.
        drainProbes()

        triggerUdp2toUplink()
        ackWCAdded()

        clusterDataClient().portsUnlink(brPort1.getId)
        ackWCRemoved()

        // All the flows to the logical port should be invalidated
        flowProbe().fishForMessage(timeout, "InvalidateFlowsByTag")(
            TestHelpers.matchFlowTag(
                FlowTagger.invalidateFlowsByLogicalPort(bridge.getId, brPort1.getId)))
    }
}
