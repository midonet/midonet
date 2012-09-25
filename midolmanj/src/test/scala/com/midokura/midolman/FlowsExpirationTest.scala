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
import com.midokura.sdn.flows.{WildcardMatches, WildcardFlow, FlowManager}
import com.midokura.sdn.dp._
import akka.testkit.{TestKitExtension, TestKit, TestProbe}
import com.midokura.packets.{IntIPv4, MAC, Packets}
import com.midokura.midolman.DatapathController.PacketIn
import akka.util.Duration
import java.util.concurrent.TimeUnit
import com.midokura.midolman.FlowController.WildcardFlowAdded
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.FlowController.WildcardFlowRemoved
import org.apache.log4j.{Logger, Level}
import akka.util.duration._
import scala.Predef._
import com.midokura.midolman.FlowController.FlowUpdateCompleted
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.FlowController.WildcardFlowRemoved
import com.midokura.midolman.FlowController.WildcardFlowAdded
import com.midokura.sdn.dp.flows.FlowKeys
import akka.actor.ActorSystem
;


@RunWith(classOf[JUnitRunner])
class FlowsExpirationTest extends MidolmanTestCase with VirtualConfigurationBuilders {

    var eventProbe: TestProbe = null
    var datapath: Datapath = null

    var timeOutFlow: Long = 500
    var delayAsynchAddRemoveInDatapath: Long = timeOutFlow/3

    val ethPkt = Packets.udp(
        MAC.fromString("02:11:22:33:44:10"),
        MAC.fromString("02:11:22:33:44:11"),
        IntIPv4.fromString("10.0.1.10"),
        IntIPv4.fromString("10.0.1.11"),
        10, 11, "My UDP packet".getBytes)

    val ethPkt1 = Packets.udp(
        MAC.fromString("02:11:22:33:44:10"),
        MAC.fromString("02:11:22:33:44:12"),
        IntIPv4.fromString("10.0.1.10"),
        IntIPv4.fromString("10.0.1.11"),
        10, 11, "My UDP packet 2".getBytes)



    //val log =  Logger.getLogger(classOf[FlowManager])
    //log.setLevel(Level.TRACE)


    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("datapath.max_flow_count", 3)
        config.setProperty("midolman.check_flow_expiration_interval", 10)
        config
    }

    override def beforeTest() {
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

    def getMatchFlowRemovedPacketPartialFunction: PartialFunction[Any, Boolean] = {
        {
            case msg: WildcardFlowRemoved => true
            case _ => false
        }
    }

    def testHardTimeExpiration() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setActions(List().toList)
            .setHardExpirationMillis(getDilatedTime(timeOutFlow))

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                            null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])

        val timeAdded: Long = System.currentTimeMillis()
        // we have to wait because adding the flow into the dp is asynch
        dilatedSleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        val timeDeleted: Long = System.currentTimeMillis()

        dilatedSleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should be (null)

        (timeDeleted - timeAdded) should (be >= timeOutFlow)
        (timeDeleted - timeAdded) should (be < 2*timeOutFlow)

    }

    def testIdleTimeExpiration() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setIdleExpirationMillis(getDilatedTime(timeOutFlow))

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        val timeAdded: Long = System.currentTimeMillis()

        dilatedSleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        eventProbe.fishForMessage(Duration(timeOutFlow, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(getMatchFlowRemovedPacketPartialFunction)

        val timeDeleted: Long = System.currentTimeMillis()

        dilatedSleep(delayAsynchAddRemoveInDatapath)

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should be (null)
        (timeDeleted - timeAdded) should (be >= timeOutFlow)

    }


    def testIdleTimeExpirationUpdated() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val flowMatch = new FlowMatch().addKey(FlowKeys.etherType(ethPkt.getEtherType))
        val wFlow = new WildcardFlow()
            .setMatch(WildcardMatches.fromFlowMatch(flowMatch))
            .setIdleExpirationMillis(getDilatedTime(timeOutFlow))

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        val timeAdded: Long = System.currentTimeMillis()


        // this sleep is also for triggering the packet-in after reasonable amount
        // of time to be able to check if the flow lastUsedTime was updated
        dilatedSleep(timeOutFlow/3)
        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        // Now trigger another packet that matches the flow.
        triggerPacketIn("port1", ethPkt)

        eventProbe.expectMsgClass(classOf[FlowUpdateCompleted])

        eventProbe.fishForMessage(Duration(timeOutFlow, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(getMatchFlowRemovedPacketPartialFunction)
        val timeDeleted: Long = System.currentTimeMillis()

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get() should be (null)
        (timeDeleted - timeAdded) should (be >= timeOutFlow + timeOutFlow/3)
    }

    def testIdleAndHardTimeOutOfTheSameFlow() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setIdleExpirationMillis(getDilatedTime(timeOutFlow))
            .setHardExpirationMillis(getDilatedTime(timeOutFlow))

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                null, null))

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        val timeAdded: Long = System.currentTimeMillis()

        dilatedSleep(delayAsynchAddRemoveInDatapath)
        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        eventProbe.fishForMessage(Duration(timeOutFlow, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(getMatchFlowRemovedPacketPartialFunction)
        val timeDeleted: Long = System.currentTimeMillis()

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get() should be (null)

        (timeDeleted - timeAdded) should (be >= timeOutFlow)
        (timeDeleted - timeAdded) should (be < timeOutFlow*2)

    }

    def testIdleTimeExpirationKernelFlowUpdated() {

        triggerPacketIn("port1", ethPkt)

        val pktInMsg = dpProbe().expectMsgType[PacketIn]
        val wFlow = new WildcardFlow()
            .setMatch(pktInMsg.wMatch)
            .setIdleExpirationMillis(getDilatedTime(timeOutFlow))

        flowProbe().testActor.tell(
            AddWildcardFlow(wFlow, pktInMsg.cookie, pktInMsg.pktBytes,
                null, null))

        flowProbe().expectMsgClass(classOf[AddWildcardFlow])
        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        val timeAdded = System.currentTimeMillis()

        dilatedSleep(timeOutFlow/3)
        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get should not be (null)

        setFlowLastUsedTimeToNow(pktInMsg.dpMatch)

        eventProbe.expectMsgClass(classOf[FlowUpdateCompleted])

        eventProbe.fishForMessage(Duration(timeOutFlow, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(getMatchFlowRemovedPacketPartialFunction)
        val timeDeleted = System.currentTimeMillis()

        dpConn().flowsGet(datapath, pktInMsg.dpMatch).get() should be (null)
        (timeDeleted-timeAdded) should (be >= timeOutFlow+timeOutFlow/3)
    }
}

