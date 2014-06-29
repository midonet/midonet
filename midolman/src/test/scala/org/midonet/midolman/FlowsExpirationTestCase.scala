/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman

import java.util.ArrayList
import java.util.concurrent.TimeUnit

import scala.Predef._

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.FlowController._
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.topology.VirtualTopologyActor.BridgeRequest
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.{LocalPortActive, VirtualTopologyActor}
import org.midonet.midolman.util.Dilation
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.TestHelpers
import org.midonet.odp._
import org.midonet.packets.{IPv4Addr, MAC, Packets}
import org.midonet.sdn.flows.WildcardFlow
import scala.concurrent.duration.Duration
import org.midonet.util.functors.Callback0

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class FlowsExpirationTestCase extends MidolmanTestCase with Dilation {

    var datapath: Datapath = null

    var timeOutFlow: Long = 500
    var delayAsynchAddRemoveInDatapath: Long = timeOutFlow/3

    val ethPkt = Packets.udp(
        MAC.fromString("02:11:22:33:44:10"),
        MAC.fromString("02:11:22:33:44:11"),
        IPv4Addr.fromString("10.0.1.10"),
        IPv4Addr.fromString("10.0.1.11"),
        10, 11, "My UDP packet".getBytes)

    val ethPkt1 = Packets.udp(
        MAC.fromString("02:11:22:33:44:10"),
        MAC.fromString("02:11:22:33:44:12"),
        IPv4Addr.fromString("10.0.1.10"),
        IPv4Addr.fromString("10.0.1.11"),
        10, 11, "My UDP packet 2".getBytes)

    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("datapath.max_flow_count", 3)
        config.setProperty("midolman.check_flow_expiration_interval", 10)
        config.setProperty("midolman.enable_monitoring", "false")
        config.setProperty("midolman.idle_flow_tolerance_interval", 1)
        config
    }

    override def beforeTest() {
        val myHost = newHost("myself", hostId())

        val bridge = newBridge("bridge")

        val port1 = newBridgePort(bridge)
        val port2 = newBridgePort(bridge)

        materializePort(port1, myHost, "port1")
        materializePort(port2, myHost, "port2")

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        // Now disable sending messages to the DatapathController
        dpProbe().testActor ! "stop"
        dpProbe().expectMsg("stop")

        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        askAndAwait(VirtualTopologyActor, PortRequest(port1.getId))
        askAndAwait(VirtualTopologyActor, PortRequest(port2.getId))
        askAndAwait(VirtualTopologyActor, BridgeRequest(bridge.getId))

        requestOfType[LocalPortActive](portsProbe)
        requestOfType[LocalPortActive](portsProbe)

        drainProbes()
    }

    def testHardTimeExpiration() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = fishForRequestOfType[PacketIn](packetInProbe)
        val wflow = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded]).f
        flowProbe().testActor ! RemoveWildcardFlow(wflow.getMatch)
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        val flow = new Flow(FlowMatches.fromEthernetPacket(ethPkt))
        dpConn().futures.flowsCreate(datapath, flow)

        val newMatch = wflow.getMatch
        newMatch.unsetInputPortUUID()
        newMatch.unsetInputPortNumber()
        val newWildFlow = WildcardFlow(
                newMatch,
                hardExpirationMillis = getDilatedTime(timeOutFlow).toInt)

        // we take a timestamp just before sending the AddWcF msg
        val timeAdded: Long = System.currentTimeMillis()
        flowProbe().testActor !
            AddWildcardFlow(newWildFlow, flow, new ArrayList[Callback0],
                            Set.empty)

        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // we have to wait because adding the flow into the dp is async
        dilatedSleep(delayAsynchAddRemoveInDatapath)

        dpConn().futures.flowsGet(datapath, flow.getMatch).get should not be (null)
        // we wait for the flow removed message that will be triggered because
        // the flow expired
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        // we take a time stamp just after getting the del event
        val timeDeleted: Long = System.currentTimeMillis()

        dilatedSleep(delayAsynchAddRemoveInDatapath)

        dpConn().futures.flowsGet(datapath, pktInMsg.dpMatch).get should be (null)

        // check that the flow expired in the correct time range. timeDeleted is
        // taken after flow deletion and timeAdded is taken before sending
        // the AddWcFlow msg, ignoring jitter, it is therefore guaranteed
        // that timeDeleted - timeAdded > realTimeDeleted - realTimeAdded
        (timeDeleted - timeAdded) should (be >= timeOutFlow)
        (timeDeleted - timeAdded) should (be < 2*timeOutFlow)

    }

    def testIdleTimeExpiration() {
        triggerPacketIn("port1", ethPkt)

        val timeAdded: Long = System.currentTimeMillis()

        val pktInMsg = fishForRequestOfType[PacketIn](packetInProbe)
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        dilatedSleep(delayAsynchAddRemoveInDatapath)

        dpConn().futures.flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        // wait to get a FlowRemoved message that will be triggered by invalidation

        ackWCRemoved(Duration(timeOutFlow, TimeUnit.SECONDS))

        val timeDeleted: Long = System.currentTimeMillis()

        dilatedSleep(delayAsynchAddRemoveInDatapath)

        dpConn().futures.flowsGet(datapath, pktInMsg.dpMatch).get should be (null)
        // check that the invalidation happened in the right time frame
        (timeDeleted - timeAdded) should (be >= timeOutFlow)

    }


    def testIdleTimeExpirationUpdated() {
        triggerPacketIn("port1", ethPkt)

        val addedFlow = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded]).f
        flowProbe().testActor ! RemoveWildcardFlow(addedFlow.getMatch)
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        val flow = new Flow(FlowMatches.fromEthernetPacket(ethPkt))
        dpConn().futures.flowsCreate(datapath, flow)

        addedFlow.wcmatch.unsetInputPortUUID()
        val newWildFlow = WildcardFlow(addedFlow.wcmatch,
                idleExpirationMillis = getDilatedTime(timeOutFlow).toInt)

        val timeAdded: Long = System.currentTimeMillis()
        flowProbe().testActor !
            AddWildcardFlow(newWildFlow, flow, new ArrayList[Callback0], Set.empty)

        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // this sleep is needed because the flow installation is async. We use a
        // large interval also to execute the following triggerPacketIn and thus
        // causing the flow's LastUsedTime after a reasonable amount of time
        dilatedSleep(timeOutFlow/3)
        dpConn().futures.flowsGet(datapath, flow.getMatch).get should not be (null)

        // Now trigger another packet that matches the flow. This will update
        // the lastUsedTime
        setFlowLastUsedTimeToNow(flow.getMatch)

        flowUpdateProbe.expectMsgClass(classOf[FlowUpdateCompleted])
        // wait for FlowRemoval notification
        ackWCRemoved(Duration(timeOutFlow, TimeUnit.SECONDS))

        val timeDeleted: Long = System.currentTimeMillis()

        dpConn().futures.flowsGet(datapath, flow.getMatch).get() should be (null)
        // check that the invalidation happened in the right time frame
        (timeDeleted - timeAdded) should be >= (timeOutFlow + timeOutFlow/3)
    }

    def testIdleAndHardTimeOutOfTheSameFlow() {
        triggerPacketIn("port1", ethPkt)


        val addedFlow = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded]).f
        flowProbe().testActor ! RemoveWildcardFlow(addedFlow.getMatch)
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        val flow = new Flow(FlowMatches.fromEthernetPacket(ethPkt))
        dpConn().futures.flowsCreate(datapath, flow)

        addedFlow.getMatch.unsetInputPortUUID()
        val newWildFlow = WildcardFlow(addedFlow.wcmatch,
                hardExpirationMillis = getDilatedTime(timeOutFlow).toInt)

        val timeAdded: Long = System.currentTimeMillis()
        flowProbe().testActor !
            AddWildcardFlow(newWildFlow, flow, new ArrayList[Callback0], Set.empty)

        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        dilatedSleep(delayAsynchAddRemoveInDatapath)
        dpConn().futures.flowsGet(datapath, flow.getMatch).get should not be (null)

        ackWCRemoved(Duration(timeOutFlow, TimeUnit.SECONDS))

        val timeDeleted: Long = System.currentTimeMillis()

        dpConn().futures.flowsGet(datapath, flow.getMatch).get() should be (null)

        // check that the invalidation happened in the right time frame
        (timeDeleted - timeAdded) should (be >= timeOutFlow)
        (timeDeleted - timeAdded) should (be < timeOutFlow*2)

    }

    def testIdleTimeExpirationKernelFlowUpdated() {

        triggerPacketIn("port1", ethPkt)

        val pktInMsg = fishForRequestOfType[PacketIn](packetInProbe)

        val addedFlow = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded]).f
        flowProbe().testActor ! RemoveWildcardFlow(addedFlow.getMatch)
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        val flow = new Flow(FlowMatches.fromEthernetPacket(ethPkt))
        dpConn().futures.flowsCreate(datapath, flow)

        addedFlow.wcmatch.unsetInputPortUUID()
        val newWildFlow = WildcardFlow(addedFlow.wcmatch,
                idleExpirationMillis = getDilatedTime(timeOutFlow).toInt)

        val timeAdded = System.currentTimeMillis()
        flowProbe().testActor !
            AddWildcardFlow(newWildFlow, flow, new ArrayList[Callback0], Set.empty)

        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        dilatedSleep(timeOutFlow/3)
        dpConn().futures.flowsGet(datapath, flow.getMatch).get should not be (null)
        // update the LastUsedTime of the flow
        setFlowLastUsedTimeToNow(flow.getMatch)
        // expect that the FlowController requests an update for this flow
        // because (timeLived > timeout/2) and that the update will be received
        flowUpdateProbe.expectMsgClass(classOf[FlowUpdateCompleted])
        // wait for flow expiration
        ackWCRemoved(Duration(timeOutFlow, TimeUnit.SECONDS))

        val timeDeleted = System.currentTimeMillis()

        dpConn().futures.flowsGet(datapath, pktInMsg.dpMatch).get() should be (null)
        // check that the invalidation happened in the right time frame
        (timeDeleted-timeAdded) should (be >= timeOutFlow+timeOutFlow/3)
    }
}

