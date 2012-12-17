/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import akka.util.Duration
import collection.mutable
import com.midokura.midolman.FlowController.{RemoveWildcardFlow, WildcardFlowRemoved, WildcardFlowAdded}
import com.midokura.midolman.topology.LocalPortActive
import flows.WildcardFlow
import util.{TestHelpers, RouterHelper}
import com.midokura.sdn.dp.flows.{FlowActions, FlowAction}
import java.util.concurrent.TimeUnit
import org.apache.commons.configuration.HierarchicalConfiguration
import akka.testkit.TestProbe
import com.midokura.sdn.dp.Datapath
import java.util.UUID
import com.midokura.midonet.cluster.data.Bridge
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midonet.cluster.data.ports.MaterializedBridgePort
import collection.immutable.HashMap
import com.midokura.midolman.DatapathController.PacketIn
import scala.collection.JavaConversions._


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

    var bridge: Bridge = null
    var host: Host = null
    var port1: MaterializedBridgePort = null
    var port2: MaterializedBridgePort = null
    var port3: MaterializedBridgePort = null

    var mapPortNameShortNumber: Map[String,Short] = new HashMap[String, Short]()



    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        //config.setProperty("datapath.max_flow_count", 3)
        //config.setProperty("midolman.check_flow_expiration_interval", 10)
        config.setProperty("midolman.enable_monitoring", "false")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    override def beforeTest() {

        eventProbe = newProbe()
        addRemoveFlowsProbe = newProbe()

        drainProbes()
        drainProbe(eventProbe)
        drainProbe(addRemoveFlowsProbe)

        host = newHost("myself", hostId())
        bridge = newBridge("bridge")

        bridge should not be null

        clusterDataClient().portSetsAddHost(bridge.getId, host.getId)


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
        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(mutable.Buffer[FlowAction[_]]
            (FlowActions.output(mapPortNameShortNumber(port1Name)))))
        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(mutable.Buffer[FlowAction[_]]
            (FlowActions.output(mapPortNameShortNumber(port2Name)))))
        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(mutable.Buffer[FlowAction[_]]
            (FlowActions.output(mapPortNameShortNumber(port3Name)))))
    }

    def testLearnMAC() {
        // this packet should be flooded
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        dpProbe().expectMsgClass(classOf[PacketIn])
        val m = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's make the bridge learn vmMac2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))
        // the flood flow should be invalidated
        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(TestHelpers.matchActionsFlowAddedOrRemoved(
            asScalaBuffer[FlowAction[_]](m.f.getActions)))
        // this is the flow from 2 to 1
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        addRemoveFlowsProbe.expectNoMsg()
    }

    def testFlowCountZeroForgetMac() {
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        dpProbe().expectMsgClass(classOf[PacketIn])
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's make the bridge learn vmMac2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))
        // this is the flooded flow that has been invalidated
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        // this is the flow from 2 to 1
        val flowToRemove = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's create another flow from 1 to 2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        val flowShouldBeInvalidated = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])


        // this will make the flowCount for vmMac2 go to 0, so it will be unlearnt
        // and the flow from 1 to 2 should get invalidated
        flowProbe().testActor ! RemoveWildcardFlow(new WildcardFlow().setMatch(flowToRemove.f.getMatch))
        // expect flow invalidation
        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(TestHelpers.matchActionsFlowAddedOrRemoved(
            asScalaBuffer[FlowAction[_]](flowShouldBeInvalidated.f.getActions)))
    }

    def testVmMigration() {
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm1, ipVm1, macVm2, ipVm2))
        dpProbe().expectMsgClass(classOf[PacketIn])
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // let's make the bridge learn vmMac2
        triggerPacketIn(port2Name, TestHelpers.createUdpPacket(macVm2, ipVm2, macVm1, ipVm1))
        // this is the flooded flow that has been invalidated
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        // this is the flow from 2 to 1
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])
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
}
