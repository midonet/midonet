/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import layer3.Route._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.testkit.TestProbe
import com.midokura.sdn.dp.Datapath
import com.midokura.packets.{IPv4, IntIPv4, MAC, Packets}
import org.apache.commons.configuration.HierarchicalConfiguration
import com.midokura.midolman.FlowController._
import topology.LocalPortActive
import topology.LocalPortActive
import java.util.UUID
import com.midokura.midolman.FlowController.FlowUpdateCompleted
import topology.LocalPortActive
import com.midokura.midolman.FlowController.WildcardFlowRemoved
import com.midokura.midolman.FlowController.WildcardFlowAdded
import com.midokura.midolman.DatapathController.PacketIn
import util.RouterHelper

@RunWith(classOf[JUnitRunner])
class InvalidationTest extends MidolmanTestCase with VirtualConfigurationBuilders
                       with RouterHelper{

    var eventProbe: TestProbe = null
    var datapath: Datapath = null

    var timeOutFlow: Long = 500
    var delayAsynchAddRemoveInDatapath: Long = timeOutFlow/3
    val ipInPort = "10.10.0.1"
    val ipOutPort = "11.11.0.4"
    val ipToReach = "11.11.0.2"
    val macToReach = "02:11:22:33:48:10"
    val ipSource = "12.12.0.2"
    val macInPort = "02:11:22:33:44:10"
    val macOutPort = "02:11:22:33:46:10"

    val macRemoteMachine = "02:11:22:33:44:11"
    val ethPkt = Packets.udp(
        MAC.fromString(macRemoteMachine),
        MAC.fromString(macInPort),
        IntIPv4.fromString(ipSource),
        IntIPv4.fromString(ipToReach),
        10, 11, "My UDP packet".getBytes)

    val ethPkt1 = Packets.udp(
        MAC.fromString("02:11:22:33:44:10"),
        MAC.fromString("02:11:22:33:44:12"),
        IntIPv4.fromString("10.0.1.10"),
        IntIPv4.fromString("10.0.1.11"),
        10, 11, "My UDP packet 2".getBytes)

    var routeId: UUID = null
    val inPortName = "inPort"
    val outPortName = "outPort"


    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        //config.setProperty("datapath.max_flow_count", 3)
        //config.setProperty("midolman.check_flow_expiration_interval", 10)
        config.setProperty("midolman.enable_monitoring", "false")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    override def beforeTest() {
        val host = newHost("myself", hostId())
        val clusterRouter = newRouter("router")
        clusterRouter should not be null

        eventProbe = newProbe()
        actors().eventStream.subscribe(eventProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(eventProbe.ref, classOf[WildcardFlowRemoved])
        actors().eventStream.subscribe(eventProbe.ref, classOf[FlowUpdateCompleted])
        actors().eventStream.subscribe(eventProbe.ref, classOf[LocalPortActive])

        // The port will route to 10.0.<i>.<j*4>/30

        val inPort = newPortOnRouter(clusterRouter, MAC.fromString(macInPort),
            ipInPort, ipInPort, 32)
        inPort should not be null

        val outPort = newPortOnRouter(clusterRouter, MAC.fromString(macOutPort),
            ipOutPort, "11.11.0.0", 24)

        routeId = newRoute(clusterRouter, ipSource, 32, ipToReach, 32,
            NextHop.PORT, outPort.getId, new IntIPv4(NO_GATEWAY).toString,
            2)

        /*val bridge = newBridge("bridge")

        val port1 = newPortOnBridge(bridge)
        val port2 = newPortOnBridge(bridge)

        materializePort(port1, myHost, "port1")
        materializePort(port2, myHost, "port2") */

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        materializePort(inPort, host, inPortName)
        requestOfType[LocalPortActive](eventProbe)
        materializePort(outPort, host, outPortName)
        requestOfType[LocalPortActive](eventProbe)

        // Now disable sending messages to the DatapathController
        //dpProbe().testActor.tell("stop")
        //dpProbe().expectMsg("stop")
    }

    def getMatchFlowRemovedPacketPartialFunction: PartialFunction[Any, Boolean] = {
        {
            case msg: WildcardFlowRemoved => true
            case _ => false
        }
    }

    def testRouteRemovedInvalidation() {
        val ttl: Byte = 17

        triggerPacketIn(inPortName, ethPkt)
        feedArpCache(outPortName,
            IntIPv4.fromString(ipToReach).addressAsInt,
            MAC.fromString(macToReach),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        dpProbe().expectMsgClass(classOf[PacketIn])
        dpProbe().expectMsgClass(classOf[PacketIn])

        requestOfType[DiscardPacket](flowProbe())

        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())

        clusterDataClient().routesDelete(routeId)
        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])

    }

}
