/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import org.junit.runner.RunWith
import org.scalatest.Ignore
import org.scalatest.junit.JUnitRunner
import scala.collection.JavaConversions._

import org.apache.commons.configuration.HierarchicalConfiguration

import com.midokura.midolman.FlowController._
import com.midokura.sdn.flows.WildcardFlow
import com.midokura.sdn.dp._
import akka.testkit.{TestKit, TestProbe}
import com.midokura.packets.{IntIPv4, MAC, Packets}
import com.midokura.midolman.DatapathController.PacketIn
import akka.util.Duration
import java.util.concurrent.TimeUnit
import com.midokura.midolman.FlowController.WildcardFlowAdded
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.FlowController.WildcardFlowRemoved
import org.apache.log4j.{Logger, Level}
import com.midokura.sdn.flows.FlowManager
import akka.util.duration._

@RunWith(classOf[JUnitRunner])
class FlowsExpirationTest extends MidolmanTestCase with VirtualConfigurationBuilders{

    var eventProbe: TestProbe = null
    var datapath: Datapath = null

    val timeOutFlow: Long = 300
    val delayAsynchAddRemoveInDatapath = timeOutFlow/3

    val ethPkt = Packets.udp(
        MAC.fromString("02:11:22:33:44:10"),
        MAC.fromString("02:11:22:33:44:11"),
        IntIPv4.fromString("10.0.1.10"),
        IntIPv4.fromString("10.0.1.11"),
        10, 11, "My UDP packet".getBytes)


    val log =  Logger.getLogger(classOf[FlowManager])
    log.setLevel(Level.TRACE)


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
        actors().eventStream.subscribe(eventProbe.ref, classOf[FlowUpdateCompleted])

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

    def IGNOREtestHardTimeExpiration() {
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

        val timeAdded: Long = System.currentTimeMillis()
        // we have to wait because adding the flow into the dp is asynch
        Thread.sleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        val timeDeleted: Long = System.currentTimeMillis()

        Thread.sleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should be (null)

        (timeDeleted - timeAdded) should (be >= timeOutFlow)
        (timeDeleted - timeAdded) should (be < 2*timeOutFlow)

    }

    def IGNOREtestIdleTimeExpiration() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setIdleExpirationMillis(timeOutFlow)

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        val timeAdded: Long = System.currentTimeMillis()


        Thread.sleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        eventProbe.expectMsgAllClassOf(classOf[WildcardFlowRemoved])

        val timeDeleted: Long = System.currentTimeMillis()

        Thread.sleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should be (null)
        (timeDeleted - timeAdded) should (be >= timeOutFlow)

    }

    def IGNOREtestIdleTimeExpirationUpdated() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setIdleExpirationMillis(timeOutFlow)

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        val timeAdded: Long = System.currentTimeMillis()


        // this sleep is also for triggering the packet-in after reasonable amount
        // of time to be able to check if the flow lastUsedTime was updated
        Thread.sleep(timeOutFlow/3)
        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        // Now trigger another packet that matches the flow.
        triggerPacketIn("port1", ethPkt)
        // increase the timeout of this expectMsg. Otherwise the expect could
        // timeout before the flow. We use expectMsgAllClassOf because we could
        // receive some FlowUpdateCompleted, depending on how often we check for
        // flow expiration, so we filter them and make sure that there's at least
        // one WildcardFlowRemoved
        eventProbe.expectMsgAllClassOf(Duration(timeOutFlow, TimeUnit.MILLISECONDS),
            classOf[WildcardFlowRemoved])
        val timeDeleted: Long = System.currentTimeMillis()

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get() should be (null)
        (timeDeleted - timeAdded) should (be >= timeOutFlow + timeOutFlow/3)
    }

    def IGNOREtestIdleAndHardTimeOutOfTheSameFlow() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setIdleExpirationMillis(timeOutFlow)
            .setHardExpirationMillis(timeOutFlow)

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        val timeAdded: Long = System.currentTimeMillis()


        Thread.sleep(delayAsynchAddRemoveInDatapath)
        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        eventProbe.expectMsgAllClassOf(Duration(timeOutFlow, TimeUnit.MILLISECONDS),
            classOf[WildcardFlowRemoved])
        val timeDeleted: Long = System.currentTimeMillis()

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get() should be (null)

        (timeDeleted - timeAdded) should (be >= timeOutFlow)
        (timeDeleted - timeAdded) should (be < timeOutFlow*2)

    }

    def IGNOREtestIdleTimeExpirationKernelFlowUpdated() {

        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setIdleExpirationMillis(timeOutFlow)

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                null, null))

        flowProbe().expectMsgClass(classOf[AddWildcardFlow])
        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        val timeAdded = System.currentTimeMillis()

        Thread.sleep(timeOutFlow/3)
        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        setFlowLastUsedTimeToNow(pktInMsg.dpMatch)

        eventProbe.expectMsgClass(classOf[FlowUpdateCompleted])

        eventProbe.expectMsgAllClassOf(Duration(timeOutFlow, TimeUnit.MILLISECONDS),
            classOf[WildcardFlowRemoved])
        val timeDeleted = System.currentTimeMillis()

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get() should be (null)
        (timeDeleted-timeAdded) should (be >= timeOutFlow+timeOutFlow/3)
    }
}
