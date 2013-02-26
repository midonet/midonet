/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman

import akka.testkit.TestProbe
import akka.util.Duration
import collection.JavaConversions._
import collection.immutable.HashMap
import collection.mutable
import java.util.UUID
import java.util.concurrent.TimeUnit

import org.apache.commons.configuration.HierarchicalConfiguration

import org.midonet.midolman.DatapathController.PacketIn
import org.midonet.midolman.FlowController.{RemoveWildcardFlow,
    WildcardFlowRemoved, WildcardFlowAdded}
import topology.BridgeManager.CheckExpiredMacPorts
import topology.{FlowTagger, BridgeManager, LocalPortActive}
import org.midonet.midolman.util.{TestHelpers, RouterHelper}
import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.{LogicalBridgePort, LogicalRouterPort, MaterializedBridgePort}
import org.midonet.odp.Datapath
import org.midonet.odp.flows.{FlowAction, FlowActions}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.midonet.packets.{IntIPv4, MAC}

@RunWith(classOf[JUnitRunner])
class BridgeInvalidationTest extends MidolmanTestCase with VirtualConfigurationBuilders
with RouterHelper{


    var eventProbe: TestProbe = null
    var addRemoveFlowsProbe: TestProbe = null
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
    val routerIp = IntIPv4.fromString("11.11.11.1")

    var bridge: Bridge = null
    var host: Host = null
    var port1: MaterializedBridgePort = null
    var port2: MaterializedBridgePort = null
    var port3: MaterializedBridgePort = null
    var rtrPort: LogicalRouterPort = null
    var brPort1: LogicalBridgePort = null
    val macPortExpiration = 1000

    var mapPortNameShortNumber: Map[String,Short] = new HashMap[String, Short]()

    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        //config.setProperty("datapath.max_flow_count", 3)
        //config.setProperty("midolman.check_flow_expiration_interval", 10)
        config.setProperty("monitoring.enable_monitoring", "false")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    override def beforeTest() {
        BridgeManager.setMacPortExpiration(macPortExpiration)

        eventProbe = newProbe()
        addRemoveFlowsProbe = newProbe()

        drainProbes()
        drainProbe(eventProbe)
        drainProbe(addRemoveFlowsProbe)

        host = newHost("myself", hostId())
        bridge = newBridge("bridge")

        bridge should not be null

        val router = newRouter("router")
        router should not be null

        // set up logical port on router
        rtrPort = newInteriorRouterPort(router, routerMac,
            routerIp.toUnicastString,
            routerIp.toNetworkAddress.toUnicastString,
            routerIp.getMaskLength)
        rtrPort should not be null

        brPort1 = newInteriorBridgePort(bridge)
        brPort1 should not be null

        actors().eventStream.subscribe(addRemoveFlowsProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(addRemoveFlowsProbe.ref, classOf[WildcardFlowRemoved])
        actors().eventStream.subscribe(eventProbe.ref, classOf[LocalPortActive])

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        port1 = newExteriorBridgePort(bridge)
        port2 = newExteriorBridgePort(bridge)
        port3 = newExteriorBridgePort(bridge)

        //requestOfType[WildcardFlowAdded](flowProbe)
        materializePort(port1, host, port1Name)
        mapPortNameShortNumber += port1Name -> 1
        requestOfType[LocalPortActive](eventProbe)
        materializePort(port2, host, port2Name)
        mapPortNameShortNumber += port2Name -> 2
        requestOfType[LocalPortActive](eventProbe)
        materializePort(port3, host, port3Name)
        mapPortNameShortNumber += port3Name -> 3
        requestOfType[LocalPortActive](eventProbe)

        // this is added when the port becomes active. A flow that takes care of
        // the tunnelled packets to this port
        addRemoveFlowsProbe.fishForMessage(Duration(5, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(mutable.Buffer[FlowAction[_]]
            (FlowActions.output(mapPortNameShortNumber(port1Name)))))
        addRemoveFlowsProbe.fishForMessage(Duration(5, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(mutable.Buffer[FlowAction[_]]
            (FlowActions.output(mapPortNameShortNumber(port2Name)))))
        addRemoveFlowsProbe.fishForMessage(Duration(5, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(mutable.Buffer[FlowAction[_]]
            (FlowActions.output(mapPortNameShortNumber(port3Name)))))

    }

    def testLearnMAC() {
        // this packet should be flooded
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        fishForRequestOfType[PacketIn](dpProbe())
        val m = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's make the bridge learn vmMac2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))

        val s = addRemoveFlowsProbe.expectMsgAllClassOf(
            Duration(3, TimeUnit.SECONDS),
            classOf[WildcardFlowRemoved], classOf[WildcardFlowAdded])

        for (w <- s) w match {
            case w: WildcardFlowRemoved =>
                // the flood flow should be invalidated
                w.f.getActions should be (m.f.getActions)
            case w: WildcardFlowAdded =>
                // this is the flow from 2 to 1
        }

        addRemoveFlowsProbe.expectNoMsg()
    }

    def testFlowCountZeroForgetMac() {
        triggerPacketIn(port1Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        fishForRequestOfType[PacketIn](dpProbe())
        val floodedFlow = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's make the bridge learn vmMac2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))
        // this is the flooded flow that has been invalidated
        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(
            asScalaBuffer[FlowAction[_]](floodedFlow.f.getActions)))
        // this is the flow from 2 to 1
        val flowToRemove = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's create another flow from 1 to 2
        triggerPacketIn(port1Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        val flowShouldBeInvalidated = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // this will make the flowCount for vmMac2 go to 0, so it will be unlearnt
        // and the flow from 1 to 2 should get invalidated
        flowProbe().testActor ! RemoveWildcardFlow(flowToRemove.f)
        fishForRequestOfType[WildcardFlowRemoved](addRemoveFlowsProbe)
        // wait for the flow to be removed
        Thread.sleep(macPortExpiration + 100)
        val bridgeManagerPath = virtualTopologyActor().path + "/" + bridge.getId.toString
        // Since the check for the expiration of the MAC port association is done
        // every 2 seconds, let's trigger it
        actors().actorFor(bridgeManagerPath) ! new CheckExpiredMacPorts()
        // expect flow invalidation
        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(TestHelpers.matchActionsFlowAddedOrRemoved(
            asScalaBuffer[FlowAction[_]](flowShouldBeInvalidated.f.getActions)))
    }

    def testVmMigration() {
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        fishForRequestOfType[PacketIn](dpProbe())
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's make the bridge learn vmMac2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))

        addRemoveFlowsProbe.expectMsgAllClassOf(
            // this is the flooded flow that has been invalidated
            classOf[WildcardFlowRemoved],
            // this is the flow from 2 to 1
            classOf[WildcardFlowAdded])

        // let's create another flow from 1 to 2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        val flowShouldBeInvalidated = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // vm2 migrate to port 3
        triggerPacketIn(port3Name, TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))

        // this will trigger the invalidation of flows tagged bridgeId + MAC + oldPort
        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(TestHelpers.matchActionsFlowAddedOrRemoved(
            asScalaBuffer[FlowAction[_]](flowShouldBeInvalidated.f.getActions)))
    }

    def testUnicastAddLogicalPort() {
        drainProbes()
        // trigger packet in before linking the port. Since routerMac is unknow
        // the bridge logic will flood the packet
        triggerPacketIn(port1Name, TestHelpers.createUdpPacket(macVm1, ipVm1,
            routerMac.toString, routerIp.toString))

        val flowShouldBeInvalidated =
            addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        fishForRequestOfType[FlowController.AddWildcardFlow](flowProbe())
        clusterDataClient().portsLink(rtrPort.getId, brPort1.getId)

        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(TestHelpers.matchActionsFlowAddedOrRemoved(
            asScalaBuffer[FlowAction[_]](flowShouldBeInvalidated.f.getActions)))
        // Expect the invalidation of the following flows:
        // 1. ARP requests
        // 2. All the flows to routerMac that were flooded
        flowProbe().fishForMessage(Duration(3, TimeUnit.SECONDS), "InvalidateFlowsByTag")(
            TestHelpers.matchFlowTag(
                FlowTagger.invalidateArpRequests(bridge.getId)))

        flowProbe().fishForMessage(Duration(3, TimeUnit.SECONDS), "InvalidateFlowsByTag")(
        TestHelpers.matchFlowTag(
            FlowTagger.invalidateFloodedFlowsByDstMac(bridge.getId, routerMac)))

    }

    def testArpRequestAddLogicalPort() {
        drainProbes()
        // send arp request before linking the port. The arp request will be flooded
        injectArpRequest(port1Name, IntIPv4.fromString(ipVm1).addressAsInt(),
            MAC.fromString(macVm1), routerIp.addressAsInt())

        val flowShouldBeInvalidated =
            addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        fishForRequestOfType[FlowController.AddWildcardFlow](flowProbe())

        clusterDataClient().portsLink(rtrPort.getId, brPort1.getId)

        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(TestHelpers.matchActionsFlowAddedOrRemoved(
            asScalaBuffer[FlowAction[_]](flowShouldBeInvalidated.f.getActions)))

        // Expect the invalidation of the following flows:
        // 1. ARP requests
        // 2. All the flows to routerMac that were flooded
        flowProbe().fishForMessage(Duration(3, TimeUnit.SECONDS), "InvalidateFlowsByTag")(
            TestHelpers.matchFlowTag(
                FlowTagger.invalidateArpRequests(bridge.getId)))
        flowProbe().fishForMessage(Duration(3, TimeUnit.SECONDS), "InvalidateFlowsByTag")(
            TestHelpers.matchFlowTag(
                FlowTagger.invalidateFloodedFlowsByDstMac(bridge.getId, routerMac)))
    }

    def testUnicastDeleteLogicalPort() {

        drainProbes()
        clusterDataClient().portsLink(rtrPort.getId, brPort1.getId)

        triggerPacketIn(port1Name, TestHelpers.createUdpPacket(macVm1, ipVm1,
            routerMac.toString, routerIp.toString))

        val flowShouldBeInvalidated =
            addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        fishForRequestOfType[FlowController.AddWildcardFlow](flowProbe())

        clusterDataClient().portsUnlink(brPort1.getId)

        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(TestHelpers.matchActionsFlowAddedOrRemoved(
            asScalaBuffer[FlowAction[_]](flowShouldBeInvalidated.f.getActions)))

        // All the flows to the logical port should be invalidated
        flowProbe().fishForMessage(Duration(3, TimeUnit.SECONDS), "InvalidateFlowsByTag")(
            TestHelpers.matchFlowTag(
                FlowTagger.invalidateFlowsByLogicalPort(bridge.getId, brPort1.getId)))
    }
}
