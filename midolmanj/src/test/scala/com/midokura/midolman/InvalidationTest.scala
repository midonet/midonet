/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import layer3.Route._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.testkit.TestProbe
import com.midokura.sdn.dp.Datapath
import com.midokura.packets._
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
import topology.LocalPortActive
import util.RouterHelper
import com.midokura.midonet.cluster.data.Router
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.FlowController.WildcardFlowRemoved
import com.midokura.midolman.FlowController.WildcardFlowAdded
import com.midokura.midolman.FlowController.DiscardPacket
import com.midokura.midonet.cluster.client.Port
import com.midokura.midonet.cluster.data.ports.MaterializedRouterPort

@RunWith(classOf[JUnitRunner])
class InvalidationTest extends MidolmanTestCase with VirtualConfigurationBuilders
                       with RouterHelper{

    var eventProbe: TestProbe = null
    var datapath: Datapath = null

    var timeOutFlow: Long = 500
    var delayAsynchAddRemoveInDatapath: Long = timeOutFlow/3
    val ipInPort = "10.10.0.1"
    val ipOutPort = "11.11.0.10"
    // this is the network reachable from outPort
    val networkToReach = "11.11.0.0"
    val networkToReachLength = 16
    val ipSource = "20.20.0.20"
    val macInPort = "02:11:22:33:44:10"
    val macOutPort = "02:11:22:33:46:10"

    val macSource = "02:11:22:33:44:11"

    var routeId: UUID = null
    val inPortName = "inPort"
    val outPortName = "outPort"
    var clusterRouter: Router = null
    var host: Host = null
    val ttl: Byte = 17
    var outPort: MaterializedRouterPort = null
    var inPort: MaterializedRouterPort = null



    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        //config.setProperty("datapath.max_flow_count", 3)
        //config.setProperty("midolman.check_flow_expiration_interval", 10)
        config.setProperty("midolman.enable_monitoring", "false")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    override def beforeTest() {
        host = newHost("myself", hostId())
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        eventProbe = newProbe()
        actors().eventStream.subscribe(eventProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(eventProbe.ref, classOf[WildcardFlowRemoved])
        actors().eventStream.subscribe(eventProbe.ref, classOf[LocalPortActive])

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        inPort = newPortOnRouter(clusterRouter, MAC.fromString(macInPort),
            ipInPort, ipInPort, 32)
        inPort should not be null

        outPort = newPortOnRouter(clusterRouter, MAC.fromString(macOutPort),
            ipOutPort, networkToReach, networkToReachLength)


        materializePort(inPort, host, inPortName)
        requestOfType[LocalPortActive](eventProbe)
        materializePort(outPort, host, outPortName)
        requestOfType[LocalPortActive](eventProbe)

    }

    def createUdpPacket(srcMac: String, srcIp: String, dstMac: String, dstIp: String): Ethernet = {
        Packets.udp(
            MAC.fromString(srcMac),
            MAC.fromString(dstMac),
            IntIPv4.fromString(srcIp),
            IntIPv4.fromString(dstIp),
            10, 11, "My UDP packet".getBytes)
    }

    def getMatchFlowRemovedPacketPartialFunction: PartialFunction[Any, Boolean] = {
        {
            case msg: WildcardFlowRemoved => true
            case _ => false
        }
    }
    // Characters of this test
    // inPort: it's the port that receives the inject packets
    // outPort: the port assigned as next hop in the routes
    // ipSource, macSource: the source of the packets we inject, we imagine packets arrive
    // from a remote source
    // ipToReach, macToReach: it's the Ip that is the destination of the injected packets
    def atestRouteRemovedInvalidation() {
        val ipToReach = "11.11.0.2"
        val macToReach = "02:11:22:33:48:10"
        // add a route from ipSource to ipToReach/32, next hop is outPort
        routeId = newRoute(clusterRouter, ipSource, 32, ipToReach, 32,
            NextHop.PORT, outPort.getId, new IntIPv4(NO_GATEWAY).toString,
            2)
        // packet from ipSource to ipToReach enters from inPort
        triggerPacketIn(inPortName, createUdpPacket(macSource, ipSource,
            macInPort, ipToReach))
        // we trigger the learning of macToReach
        feedArpCache(outPortName,
            IntIPv4.fromString(ipToReach).addressAsInt,
            MAC.fromString(macToReach),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        dpProbe().expectMsgClass(classOf[PacketIn])
        dpProbe().expectMsgClass(classOf[PacketIn])

        requestOfType[DiscardPacket](flowProbe())

        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        // when we delete the routes we expect the flow to be invalidated
        clusterDataClient().routesDelete(routeId)
        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])

    }

    // We add a route to 11.11.0.0/16 reachable from outPort, we create 3 flows
    // from outPort to 11.1.1.2
    // from outPort to 11.1.1.3
    // from outPort to 11.11.2.4
    // We add a new route to 11.11.1.0/24 and we expect only the flow to 11.1.1.2
    // and 11.1.1.3 to be invalidated because they belong to 11.11.1.0/24
    def testAddRouteInvalidation() {

        val ipVm1 = "11.11.1.2"
        val macVm1 = "11:22:33:44:55:02"
        val ipVm2 = "11.11.1.3"
        val macVm2 = "11:22:33:44:55:03"

        // this vm is on a different sub network
        val ipVm3 = "11.11.2.4"
        val macVm3 = "11:22:33:44:55:04"

        // create a route to the network to reach
        newRoute(clusterRouter, ipSource, 32, networkToReach, networkToReachLength,
            NextHop.PORT, outPort.getId, new IntIPv4(NO_GATEWAY).toString,
            2)
        triggerPacketIn(inPortName, createUdpPacket(macSource, ipSource, macInPort, ipVm1))
        feedArpCache(outPortName,
            IntIPv4.fromString(ipVm1).addressAsInt,
            MAC.fromString(macVm1),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        triggerPacketIn(inPortName, createUdpPacket(macSource, ipSource, macInPort, ipVm2))
        feedArpCache(outPortName,
            IntIPv4.fromString(ipVm2).addressAsInt,
            MAC.fromString(macVm2),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))
        triggerPacketIn(inPortName, createUdpPacket(macSource, ipSource, macInPort, ipVm3))
        feedArpCache(outPortName,
            IntIPv4.fromString(ipVm3).addressAsInt,
            MAC.fromString(macVm3),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))
        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])
        eventProbe.expectMsgClass(classOf[WildcardFlowAdded])

        newRoute(clusterRouter, ipSource, 32, "11.11.1.0", networkToReachLength+8,
            NextHop.PORT, outPort.getId, new IntIPv4(NO_GATEWAY).toString,
            2)
        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        eventProbe.expectMsgClass(classOf[WildcardFlowRemoved])

    }

}
