/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import org.junit.runner.RunWith
import org.scalatest.Ignore
import org.scalatest.junit.JUnitRunner
import scala.collection.JavaConversions._

import org.apache.commons.configuration.HierarchicalConfiguration

import com.midokura.midolman.FlowController.{WildcardFlowRemoved, CheckFlowExpiration, WildcardFlowAdded, AddWildcardFlow}
import com.midokura.sdn.flows.{WildcardMatches, WildcardFlow}
import com.midokura.sdn.dp.flows.FlowKeys
import com.midokura.sdn.dp._
import akka.testkit.TestProbe
import com.midokura.packets.{IntIPv4, MAC, Packets}
import com.midokura.midolman.DatapathController.PacketIn


@RunWith(classOf[JUnitRunner]) @Ignore
class FlowsExpirationTest extends MidolmanTestCase with VirtualConfigurationBuilders{

    var eventProbe: TestProbe = null
    var datapath: Datapath = null

    val timeOutFlow = 200
    val delayAsynchAddRemoveInDatapath = 20

    val ethPkt = Packets.udp(
        MAC.fromString("02:11:22:33:44:10"),
        MAC.fromString("02:11:22:33:44:11"),
        IntIPv4.fromString("10.0.1.10"),
        IntIPv4.fromString("10.0.1.11"),
        10, 11, "My UDP packet".getBytes)


    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("max_flow_count", "3")
        config
    }

    override def before() {
        val myHost = newHost("myself", hostId())
        eventProbe = newProbe()
        actors().eventStream.subscribe(eventProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(eventProbe.ref, classOf[WildcardFlowRemoved])
        actors().eventStream.subscribe(eventProbe.ref, classOf[CheckFlowExpiration])

        val bridge = newBridge("bridge")

        val port1 = newPortOnBridge(bridge)
        val port2 = newPortOnBridge(bridge)

        materializePort(port1, myHost, "port1")
        materializePort(port2, myHost, "port2")

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        // Now disable sending messages to the DatapathController
        dpProbe().testActor.tell("stop")
        dpProbe().expectMsg("stop")
    }

    def testHardTimeExpiration() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setActions(List().toList)
            .setHardExpirationMillis(timeOutFlow)

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                            null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        Thread.sleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        Thread.sleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should be (null)
    }

    def testIdleTimeExpiration() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setIdleExpirationMillis(timeOutFlow)

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        Thread.sleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        Thread.sleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should be (null)
    }

    def testIdleTimeExpirationUpdated() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setIdleExpirationMillis(timeOutFlow)

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        Thread.sleep(2*timeOutFlow/3)
        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        // Now trigger another packet that matches the flow.
        triggerPacketIn("port1", ethPkt)

        Thread.sleep(2*timeOutFlow/3)
        // The flow should not have expired since it was used again.
        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get() should be (null)
    }
}
