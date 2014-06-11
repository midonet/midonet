/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman

import scala.collection.mutable
import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import java.util.UUID

import akka.testkit.TestProbe
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{TunnelZone, Router}
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.RouterPort
import org.midonet.midolman.DatapathController.DpPortCreate
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.FlowController.WildcardFlowRemoved
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.topology.VirtualToPhysicalMapper.GreZoneChanged
import org.midonet.midolman.topology.VirtualToPhysicalMapper.GreZoneMembers
import org.midonet.midolman.topology.{FlowTagger, LocalPortActive}
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.{TestHelpers, RouterHelper}
import org.midonet.odp.Datapath
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowActions.output
import org.midonet.packets.IPv4Addr
import org.midonet.packets.MAC
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPortSet
import org.midonet.sdn.flows.{WildcardMatch, WildcardFlow}

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class DatapathFlowInvalidationTestCase extends MidolmanTestCase
        with RouterHelper {

    var tagEventProbe: TestProbe = null

    var datapath: Datapath = null

    val ipInPort = "10.10.0.1"
    val ipOutPort = "11.11.0.10"
    // this is the network reachable from outPort
    val networkToReach = "11.11.0.0"
    val networkToReachLength = 16
    val ipSource = "20.20.0.20"
    val macInPort = "02:11:22:33:44:10"
    val macOutPort = "02:11:22:33:46:10"

    val macSource = "02:11:22:33:44:11"

    var tunnelZone: TunnelZone = null
    var routeId: UUID = null
    val inPortName = "inPort"
    val outPortName = "outPort"
    var clusterRouter: Router = null
    var host1: Host = null
    var host2: Host = null
    var host3: Host = null
    val ttl: Byte = 17
    var outPort: RouterPort = null
    var inPort: RouterPort = null
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
        drainProbes()

        host1 = newHost("myself", hostId())
        host2 = newHost("host2")
        host3 = newHost("host3")
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        inPort = newRouterPort(clusterRouter, MAC.fromString(macInPort),
            ipInPort, ipInPort, 32)
        inPort should not be null

        outPort = newRouterPort(clusterRouter, MAC.fromString(macOutPort),
            ipOutPort, networkToReach, networkToReachLength)

        //requestOfType[WildcardFlowAdded](flowProbe)
        inPort = materializePort(
            inPort, host1, inPortName).asInstanceOf[RouterPort]
        mapPortNameShortNumber += inPortName -> 1
        requestOfType[LocalPortActive](portsProbe)
        outPort = materializePort(
            outPort, host1, outPortName).asInstanceOf[RouterPort]
        mapPortNameShortNumber += outPortName -> 2
        requestOfType[LocalPortActive](portsProbe)

        // this is added when the port becomes active. A flow that takes care of
        // the tunnelled packets to this port
        wflowAddedProbe.expectMsgPF(3 seconds,
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(
            mutable.Buffer[FlowAction](output(mapPortNameShortNumber(inPortName)))))
        wflowAddedProbe.expectMsgPF(3 seconds,
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(
            mutable.Buffer[FlowAction](output(mapPortNameShortNumber(outPortName)))))

    }

    def testDpInPortDeleted() {

        val ipToReach = "11.11.0.2"
        val macToReach = "02:11:22:33:48:10"
        // add a route from ipSource to ipToReach/32, next hop is outPort
        newRoute(clusterRouter, ipSource, 32, ipToReach, 32,
            NextHop.PORT, outPort.getId, IPv4Addr(Route.NO_GATEWAY).toString,
            2)

        // we trigger the learning of macToReach
        drainProbes()
        feedArpCache(outPortName,
            IPv4Addr(ipToReach).addr,
            MAC.fromString(macToReach),
            IPv4Addr(ipOutPort).addr,
            MAC.fromString(macOutPort))

        // packet from ipSource to ipToReach enters from inPort
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource,
            macInPort, ipToReach))
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        deletePort(inPort, host1)

        // We expect 2 flows to be invalidated: the one created automatically
        // when the port becomes active to handle tunnelled packets for that port
        // and the second installed after the packet it
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
    }

    def testDpOutPortDeleted() {
        val ipToReach = "11.11.0.2"
        val macToReach = "02:11:22:33:48:10"
        // add a route from ipSource to ipToReach/32, next hop is outPort
        newRoute(clusterRouter, ipSource, 32, ipToReach, 32,
            NextHop.PORT, outPort.getId, IPv4Addr(Route.NO_GATEWAY).toString,
            2)

        // we trigger the learning of macToReach
        drainProbes()
        feedArpCache(outPortName,
            IPv4Addr(ipToReach).addr,
            MAC.fromString(macToReach),
            IPv4Addr(ipOutPort).addr,
            MAC.fromString(macOutPort))

        // packet from ipSource to ipToReach enters from inPort
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource,
            macInPort, ipToReach))
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        deletePort(outPort, host1)
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        /*addRemoveFlowsProbe.fishForMessage(3 seconds,
            "WildcardFlowRemoved")(matchActionsFlowAddedOrRemoved(flowAddedMessage.f.getActions.asScala))
        addRemoveFlowsProbe.fishForMessage(3 seconds,
            "WildcardFlowRemoved")(matchActionsFlowAddedOrRemoved(mutable.Buffer[FlowAction]
            (output(mapPortNameShortNumber(outPortName)))))*/
    }

    def testTunnelPortAddedAndRemoved() {

        drainProbes()
        tunnelZone = greTunnelZone("default")
        host2 = newHost("host2")

        val bridge = newBridge("bridge")

        val port1OnHost1 = newBridgePort(bridge)
        val portOnHost2 = newBridgePort(bridge)

        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")

        // The local MM adds the local host to the PortSet. We add the remote.
        //clusterDataClient().portSetsAddHost(bridge.getId, host1.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)

        // flows installed for tunnel key = port when the port becomes active.
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        requestOfType[DpPortCreate](datapathEventsProbe)

        val srcIp = IPv4Addr("192.168.100.1")
        val dstIp1 = IPv4Addr("192.168.125.1")
        val dstIp2 = IPv4Addr("192.168.210.1")

        val tag1 = FlowTagger invalidateTunnelRoute (srcIp.toInt, dstIp1.toInt)
        val tag2 = FlowTagger invalidateTunnelRoute (srcIp.toInt, dstIp2.toInt)

        val output = dpState().asInstanceOf[DatapathStateManager]
                              .overlayTunnellingOutputAction
        val route1 = UnderlayResolver.Route(srcIp.toInt, dstIp1.toInt, output.get)
        val route2 = UnderlayResolver.Route(srcIp.toInt, dstIp2.toInt, output.get)

        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new TunnelZone.HostConfig(host1.getId).setIp(srcIp.toIntIPv4))
        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new TunnelZone.HostConfig(host2.getId).setIp(dstIp1.toIntIPv4))

        fishForReplyOfType[GreZoneMembers](vtpProbe())
        fishForReplyOfType[GreZoneChanged](vtpProbe())

        // assert that the tunnel route was added
        (dpState() peerTunnelInfo host2.getId) shouldBe Some(route1)

        // assert that a invalidateFlowByTag where tag is the route info is sent
        flowProbe().fishForMessage(3 seconds,"Tag")(matchATagInvalidation(tag1))

        val wildcardFlow = WildcardFlow(
            wcmatch = new WildcardMatch().setInputPortUUID(port1OnHost1.getId),
            actions = List(new FlowActionOutputToVrnPortSet(bridge.getId)))

        dpProbe().testActor ! AddVirtualWildcardFlow(
            wildcardFlow, Nil, Set.empty)

        val flow = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // update the gre ip of the second host
        val secondGreConfig =
            new TunnelZone.HostConfig(host2.getId).setIp(dstIp2.toIntIPv4)
        clusterDataClient().tunnelZonesDeleteMembership(
            tunnelZone.getId, host2.getId)
        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId, secondGreConfig)

        // assert that the old route was removed and a invalidateFlowByTag is sent
        flowProbe().fishForMessage(3 seconds,"Tag")(matchATagInvalidation(tag1))

        // assert that the flow gets deleted
        val flowRemoved = wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        flowRemoved.f.getMatch shouldBe flow.f.getMatch

        // assert that a flow invalidation by tag is sent with new tunnel route
        flowProbe().expectMsg(new InvalidateFlowsByTag(tag2))

        // at that point, the tunnel route should be in place, assert it
        (dpState() peerTunnelInfo host2.getId) shouldBe Some(route2)
    }

    def matchATagInvalidation(tagToTest: Any): PartialFunction[Any, Boolean] = {
        case msg: InvalidateFlowsByTag => msg.tag.equals(tagToTest)
        case _ => false
    }
}
