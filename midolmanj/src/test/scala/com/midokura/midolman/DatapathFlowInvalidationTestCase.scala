/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import scala.collection.mutable
import scala.collection.immutable.HashMap
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.util.Duration
import akka.testkit.TestProbe

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import com.midokura.sdn.dp.Datapath
import com.midokura.sdn.dp.flows.{FlowActions, FlowAction}
import com.midokura.sdn.dp.ports.GreTunnelPort
import com.midokura.packets.{IntIPv4, MAC}
import com.midokura.midonet.cluster.data.Router
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midonet.cluster.data.ports.MaterializedRouterPort
import com.midokura.midonet.cluster.data.zones.{GreTunnelZone, GreTunnelZoneHost}
import com.midokura.midolman.DatapathController.{DatapathPortChangedEvent, TunnelChangeEvent, PacketIn}
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.FlowController.WildcardFlowAdded
import com.midokura.midolman.FlowController.WildcardFlowRemoved
import com.midokura.midolman.FlowController.InvalidateFlowsByTag
import com.midokura.midolman.datapath.FlowActionOutputToVrnPortSet
import com.midokura.midolman.flows.{WildcardMatch, WildcardFlow}
import com.midokura.midolman.layer3.Route
import com.midokura.midolman.layer3.Route.NextHop
import com.midokura.midolman.topology.VirtualToPhysicalMapper.GreZoneChanged
import com.midokura.midolman.topology.VirtualToPhysicalMapper.GreZoneMembers
import com.midokura.midolman.topology.{FlowTagger, LocalPortActive}
import com.midokura.midolman.util.{TestHelpers, RouterHelper}

@RunWith(classOf[JUnitRunner])
class DatapathFlowInvalidationTestCase extends MidolmanTestCase with VirtualConfigurationBuilders
with RouterHelper{

    var addRemoveFlowsProbe: TestProbe = null
    var tagEventProbe: TestProbe = null
    var tunnelEventsProbe: TestProbe = null
    var portEventsProbe: TestProbe = null
    var datapathEventsProbe: TestProbe = null

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

    var tunnelZone: GreTunnelZone = null
    var routeId: UUID = null
    val inPortName = "inPort"
    val outPortName = "outPort"
    var clusterRouter: Router = null
    var host1: Host = null
    var host2: Host = null
    var host3: Host = null
    val ttl: Byte = 17
    var outPort: MaterializedRouterPort = null
    var inPort: MaterializedRouterPort = null
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
        addRemoveFlowsProbe = newProbe()
        tunnelEventsProbe = newProbe()
        portEventsProbe = newProbe()
        datapathEventsProbe = newProbe()

        drainProbes()

        host1 = newHost("myself", hostId())
        host2 = newHost("host2")
        host3 = newHost("host3")
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        actors().eventStream.subscribe(addRemoveFlowsProbe.ref,
            classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(addRemoveFlowsProbe.ref,
            classOf[WildcardFlowRemoved])
        actors().eventStream.subscribe(tunnelEventsProbe.ref,
            classOf[TunnelChangeEvent])
        actors().eventStream.subscribe(portEventsProbe.ref,
            classOf[LocalPortActive])
        actors().eventStream.subscribe(datapathEventsProbe.ref,
            classOf[DatapathPortChangedEvent])


        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        inPort = newExteriorRouterPort(clusterRouter, MAC.fromString(macInPort),
            ipInPort, ipInPort, 32)
        inPort should not be null

        outPort = newExteriorRouterPort(clusterRouter, MAC.fromString(macOutPort),
            ipOutPort, networkToReach, networkToReachLength)

        //requestOfType[WildcardFlowAdded](flowProbe)
        materializePort(inPort, host1, inPortName)
        mapPortNameShortNumber += inPortName -> 1
        requestOfType[LocalPortActive](portEventsProbe)
        materializePort(outPort, host1, outPortName)
        mapPortNameShortNumber += outPortName -> 2
        requestOfType[LocalPortActive](portEventsProbe)

        // this is added when the port becomes active. A flow that takes care of
        // the tunnelled packets to this port
        addRemoveFlowsProbe.expectMsgPF(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(
            mutable.Buffer[FlowAction[_]](FlowActions.output(mapPortNameShortNumber(inPortName)))))
        addRemoveFlowsProbe.expectMsgPF(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(
            mutable.Buffer[FlowAction[_]](FlowActions.output(mapPortNameShortNumber(outPortName)))))

    }

    def testDpInPortDeleted() {

        val ipToReach = "11.11.0.2"
        val macToReach = "02:11:22:33:48:10"
        // add a route from ipSource to ipToReach/32, next hop is outPort
        newRoute(clusterRouter, ipSource, 32, ipToReach, 32,
            NextHop.PORT, outPort.getId, new IntIPv4(Route.NO_GATEWAY).toString,
            2)
        // packet from ipSource to ipToReach enters from inPort
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource,
            macInPort, ipToReach))
        // we trigger the learning of macToReach
        feedArpCache(outPortName,
            IntIPv4.fromString(ipToReach).addressAsInt,
            MAC.fromString(macToReach),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        fishForRequestOfType[PacketIn](dpProbe())
        fishForRequestOfType[PacketIn](dpProbe())

        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])

        deletePort(inPort, host1)

        // We expect 2 flows to be invalidated: the one created automatically
        // when the port becomes active to handle tunnelled packets for that port
        // and the second installed after the packet it
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowRemoved])
    }

    def atestDpOutPortDeleted() {
        val ipToReach = "11.11.0.2"
        val macToReach = "02:11:22:33:48:10"
        // add a route from ipSource to ipToReach/32, next hop is outPort
        newRoute(clusterRouter, ipSource, 32, ipToReach, 32,
            NextHop.PORT, outPort.getId, new IntIPv4(Route.NO_GATEWAY).toString,
            2)
        // packet from ipSource to ipToReach enters from inPort
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource,
            macInPort, ipToReach))
        // we trigger the learning of macToReach
        feedArpCache(outPortName,
            IntIPv4.fromString(ipToReach).addressAsInt,
            MAC.fromString(macToReach),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        fishForRequestOfType[PacketIn](dpProbe())
        fishForRequestOfType[PacketIn](dpProbe())

        val flowAddedMessage = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])

        deletePort(outPort, host1)
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        /*addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(matchActionsFlowAddedOrRemoved(flowAddedMessage.f.getActions.asScala))
        addRemoveFlowsProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(matchActionsFlowAddedOrRemoved(mutable.Buffer[FlowAction[_]]
            (FlowActions.output(mapPortNameShortNumber(outPortName)))))*/
    }


    def testTunnelPortAddedAndRemoved() {

        drainProbe(datapathEventsProbe)
        drainProbe(addRemoveFlowsProbe)
        drainProbes()
        tunnelZone = greTunnelZone("default")
        host2 = newHost("host2")

        val bridge = newBridge("bridge")

        val port1OnHost1 = newExteriorBridgePort(bridge)
        val portOnHost2 = newExteriorBridgePort(bridge)

        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new GreTunnelZoneHost(host1.getId)
                .setIp(IntIPv4.fromString("192.168.100.1")))
        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new GreTunnelZoneHost(host2.getId)
                .setIp(IntIPv4.fromString("192.168.125.1")))

        // The local MM adds the local host to the PortSet. We add the remote.
        //clusterDataClient().portSetsAddHost(bridge.getId, host1.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)

        fishForReplyOfType[GreZoneMembers](vtpProbe())
        fishForReplyOfType[GreZoneChanged](vtpProbe())

        // assert that the creation event for the tunnel was fired.
        var portChangedEvent = requestOfType[DatapathPortChangedEvent](datapathEventsProbe)
        portChangedEvent.op should be(PortOperation.Create)
        portChangedEvent.port.isInstanceOf[GreTunnelPort] should be(true)

        var tunnelPortNumber = portChangedEvent.port.getPortNo

        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])
        requestOfType[DatapathPortChangedEvent](datapathEventsProbe)

        // flows installed for tunnel key = port when the port becomes active.
        addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // assert that a invalidateFlowByTag where tag is the port datapath short
        // id is sent
        flowProbe().fishForMessage(Duration(3, TimeUnit.SECONDS),
            "Tag")(matchATagInvalidation(FlowTagger.invalidateDPPort(tunnelPortNumber.shortValue)))

        val wildcardFlow = new WildcardFlow()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        val pktBytes = "My packet".getBytes
        dpProbe().testActor.tell(AddWildcardFlow(
            wildcardFlow, None, pktBytes, null, null))

        val flowToInvalidate = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // update the gre ip of the second host
        val secondGreConfig = new GreTunnelZoneHost(host2.getId)
            .setIp(IntIPv4.fromString("192.168.210.1"))
        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId, secondGreConfig)

        // assert a delete event was fired on the bus.
        portChangedEvent = requestOfType[DatapathPortChangedEvent](datapathEventsProbe)
        portChangedEvent.op should be(PortOperation.Delete)
        portChangedEvent.port.isInstanceOf[GreTunnelPort] should be(true)

        // assert that a invalidateFlowByTag is sent
        flowProbe().fishForMessage(Duration(3, TimeUnit.SECONDS),
            "Tag")(matchATagInvalidation(FlowTagger.invalidateDPPort(tunnelPortNumber.shortValue)))
        // assert that the flow gets deleted
        val flowRemoved = addRemoveFlowsProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        flowRemoved.f.getMatch should be(flowToInvalidate.f.getMatch)

        // assert the proper datapath port changed event is fired
        portChangedEvent = requestOfType[DatapathPortChangedEvent](datapathEventsProbe)

        portChangedEvent.op should be(PortOperation.Create)
        portChangedEvent.port.isInstanceOf[GreTunnelPort] should be(true)

        tunnelPortNumber = portChangedEvent.port.getPortNo
        // assert that a flow invalidation by tag is sent with tag = port short id
        flowProbe().expectMsg(new InvalidateFlowsByTag(
            FlowTagger.invalidateDPPort(tunnelPortNumber.shortValue)))
    }

    def matchATagInvalidation(tagToTest: Any):
    PartialFunction[Any, Boolean] = {
        {
            case msg: InvalidateFlowsByTag =>
                if(msg.tag.equals(tagToTest)){
                    true
                }
                else {
                    false

                }
            case _ => false
        }
    }
}
