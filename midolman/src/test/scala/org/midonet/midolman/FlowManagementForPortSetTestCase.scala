/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman


import scala.collection.JavaConversions._
import scala.util.control.Breaks._
import akka.testkit.TestProbe
import akka.util.Duration
import java.util.concurrent.TimeUnit

import akka.util.Duration
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.scalatest.Ignore
import org.scalatest.junit.JUnitRunner
import org.scalatest.Ignore
import org.slf4j.LoggerFactory

import org.midonet.midolman.DatapathController.TunnelChangeEvent
import org.midonet.midolman.DeduplicationActor.HandlePacket
import org.midonet.midolman.FlowController.{WildcardFlowAdded,
        InvalidateFlowsByTag, WildcardFlowRemoved}
import org.midonet.midolman.PacketWorkflowActor.AddVirtualWildcardFlow
import org.midonet.midolman.datapath.FlowActionOutputToVrnPortSet
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.topology.{FlowTagger, LocalPortActive}
import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.zones.{GreTunnelZone, GreTunnelZoneHost}
import org.midonet.packets.{Data, IPv4, Ethernet, IntIPv4, MAC}
import org.midonet.odp.FlowMatch
import org.midonet.odp.Packet
import org.midonet.odp.flows._
import org.midonet.odp.flows.FlowKeys.{ethernet, inPort, tunnelID}
import org.midonet.sdn.flows.{WildcardFlowBuilder, WildcardMatch}


@RunWith(classOf[JUnitRunner])
class FlowManagementForPortSetTestCase extends MidolmanTestCase
        with VirtualConfigurationBuilders {

    var tunnelZone: GreTunnelZone = null

    var host1: Host = null
    var host2: Host = null
    var host3: Host = null

    var bridge: Bridge = null
    var tunnelEventsProbe: TestProbe = null

    private val log = LoggerFactory.getLogger(
        classOf[FlowManagementForPortSetTestCase])

    override def beforeTest() {
        tunnelZone = greTunnelZone("default")

        host1 = newHost("host1", hostId())
        host2 = newHost("host2")
        host3 = newHost("host3")

        bridge = newBridge("bridge")

        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new GreTunnelZoneHost(host1.getId)
                .setIp(IntIPv4.fromString("192.168.100.1")))
        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new GreTunnelZoneHost(host2.getId)
                .setIp(IntIPv4.fromString("192.168.125.1")))
        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new GreTunnelZoneHost(host3.getId)
                .setIp(IntIPv4.fromString("192.168.150.1")))

        tunnelEventsProbe = newProbe()
        actors().eventStream.subscribe(tunnelEventsProbe.ref,
            classOf[TunnelChangeEvent])

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
    }

    @Ignore
    def testInstallFlowForPortSet() {

        val port1OnHost1 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val port2OnHost1 = newExteriorBridgePort(bridge)
        //port2OnHost1.getTunnelKey should be (3)
        val port3OnHost1 = newExteriorBridgePort(bridge)
        //port3OnHost1.getTunnelKey should be (4)
        val portOnHost2 = newExteriorBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (5)
        val portOnHost3 = newExteriorBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (6)

        val srcMAC = MAC.fromString("00:11:22:33:44:55")
        val chain1 = newOutboundChainOnPort("chain1", port2OnHost1)
        val condition = new Condition
        condition.dlSrc = srcMAC
        val rule1 = newLiteralRuleOnChain(chain1, 1, condition,
                RuleResult.Action.DROP)

        materializePort(port1OnHost1, host1, "port1a")
        materializePort(port2OnHost1, host1, "port1b")
        materializePort(port3OnHost1, host1, "port1c")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")


        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        val tunnelId1 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get
        val tunnelId2 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get


        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val localPortNumber1 = dpController().underlyingActor
            .ifaceNameToDpPort("port1a").getPortNo
        val localPortNumber2 = dpController().underlyingActor
            .ifaceNameToDpPort("port1b").getPortNo
        val localPortNumber3 = dpController().underlyingActor
            .ifaceNameToDpPort("port1c").getPortNo
        val wildcardFlow = new WildcardFlowBuilder()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId)
                                         .setEthernetSource(srcMAC))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        val addFlowMsg = fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        addFlowMsg should not be null
        addFlowMsg.f should equal (wildcardFlow)
        addFlowMsg.f.getMatch.getInputPortUUID should be(null)
        addFlowMsg.f.getMatch.getInputPortNumber should be(localPortNumber1)

        val flowActs = addFlowMsg.f.getActions.toList

        flowActs should not be (null)
        // The ingress port should not be in the expanded port set
        flowActs should have size (4)

        // Compare FlowKeyTunnelID against bridge.getTunnelKey
        val setKeyAction = as[FlowActionSetKey](flowActs.get(flowActs.length - 3))
        as[FlowKeyTunnelID](setKeyAction.getFlowKey).getTunnelID should be(bridge.getTunnelKey)

        // TODO: Why does the "should contain" syntax fail here?
        assert(flowActs.contains(FlowActions.output(tunnelId1)))
        assert(flowActs.contains(FlowActions.output(tunnelId2)))
        assert(!flowActs.contains(FlowActions.output(localPortNumber2)))
        assert(flowActs.contains(FlowActions.output(localPortNumber3)))
    }

    @Ignore
    def testInstallFlowForPortSetFromTunnel() {
        log.debug("Starting testInstallFlowForPortSetFromTunnel")

        val port1OnHost1 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val port2OnHost1 = newExteriorBridgePort(bridge)
        //port2OnHost1.getTunnelKey should be (3)
        val port3OnHost1 = newExteriorBridgePort(bridge)
        //port3OnHost1.getTunnelKey should be (4)
        val portOnHost2 = newExteriorBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (5)
        val portOnHost3 = newExteriorBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (6)

        val srcMAC = MAC.fromString("00:11:22:33:44:55")
        val dstMAC = MAC.fromString("ff:ff:ff:ff:ff:ff")
        val chain1 = newOutboundChainOnPort("chain1", port2OnHost1)
        val condition = new Condition
        condition.dlSrc = srcMAC
        val rule1 = newLiteralRuleOnChain(chain1, 1, condition,
                RuleResult.Action.DROP)

        materializePort(port1OnHost1, host1, "port1a")
        materializePort(port2OnHost1, host1, "port1b")
        materializePort(port3OnHost1, host1, "port1c")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")


        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        val tunnelId1 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get
        val tunnelId2 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get


        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val localPortNumber1 = dpController().underlyingActor
            .ifaceNameToDpPort("port1a").getPortNo
        val localPortNumber2 = dpController().underlyingActor
            .ifaceNameToDpPort("port1b").getPortNo
        val localPortNumber3 = dpController().underlyingActor
            .ifaceNameToDpPort("port1c").getPortNo
        val dpMatch = new FlowMatch().
                addKey(ethernet(srcMAC.getAddress, dstMAC.getAddress)).
                addKey(inPort(tunnelId1)).
                addKey(tunnelID(bridge.getTunnelKey))

        val eth = new Ethernet().
                setSourceMACAddress(srcMAC).
                setDestinationMACAddress(dstMAC).
                setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setPayload(new Data("Payload".getBytes)))
        val packet = new Packet().setMatch(dpMatch).setPacket(eth)
        dedupProbe().testActor.tell(HandlePacket(packet))

        val addFlowMsg = fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        addFlowMsg should not be null
        addFlowMsg.f.getMatch.getInputPortUUID should be (null)
        addFlowMsg.f.getMatch.getInputPortNumber should be (tunnelId1)

        val flowActs = addFlowMsg.f.getActions.toList

        flowActs should not be (null)
        // Should output to local ports 1 and 3 only.
        flowActs should have size (2)

        // TODO: Why does the "should contain" syntax fail here?
        assert(flowActs.contains(FlowActions.output(localPortNumber1)))
        assert(!flowActs.contains(FlowActions.output(localPortNumber2)))
        assert(flowActs.contains(FlowActions.output(localPortNumber3)))
    }

    def testInvalidationHostRemovedFromPortSet() {

        val port1OnHost1 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val portOnHost2 = newExteriorBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (3)
        val portOnHost3 = newExteriorBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (4)


        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")

        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        requestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = new WildcardFlowBuilder()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        val flowToInvalidate = requestOfType[WildcardFlowAdded](wflowAddedProbe)
        // delete one host from the portSet
        clusterDataClient().portSetsDelHost(bridge.getId, host3.getId)
        // expect flow invalidation for the flow tagged using the bridge id and portSet id,
        // which are the same

        val wanted = FlowTagger.invalidateBroadcastFlows(bridge.getId, bridge.getId)
        breakable { while (true) {
            val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
            if (msg.tag.equals(wanted))
                break()
        } }

        val flowInvalidated = wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        assert(flowInvalidated.f.equals(flowToInvalidate.f))
    }

    def testInvalidationNewHostAddedToPortSet() {

        val port1OnHost1 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val portOnHost2 = newExteriorBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (3)
        val portOnHost3 = newExteriorBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (4)


        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")


        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)

        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = new WildcardFlowBuilder()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        val flowToInvalidate =
            fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // expect flow invalidation for the flow tagged using the bridge id and
        // portSet id, which are the same
        val wanted = FlowTagger.invalidateBroadcastFlows(bridge.getId, bridge.getId)
        breakable { while (true) {
            val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
            if (msg.tag.equals(wanted))
                break()
        } }

        val flowInvalidated = wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        assert(flowInvalidated.f.equals(flowToInvalidate.f))
    }

    def testInvalidationNewPortAddedToPortSet() {

        val port1OnHost1 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val portOnHost2 = newExteriorBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (3)
        val portOnHost3 = newExteriorBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (4)


        materializePort(port1OnHost1, host1, "port1a")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")


        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = new WildcardFlowBuilder()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        val flowToInvalidate =
            fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        val port2OnHost1 = newExteriorBridgePort(bridge)
        port2OnHost1.getTunnelKey should be (5)
        // Drain the flowProbe
        while(flowProbe().msgAvailable)
            flowProbe().receiveOne(Duration(10, TimeUnit.MILLISECONDS))
        materializePort(port2OnHost1, host1, "port1b")
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        var numPort2OnHost1: Short = 0
        dpController().underlyingActor.vifToLocalPortNumber(
                port2OnHost1.getId) match {
            case Some(portNo : Short) => numPort2OnHost1 = portNo
            case None =>
                fail("Short data port number for port2OnHost1 not found.")
        }
        // Expect various invalidation messages
        var msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        assert(msg.tag.equals(
            FlowTagger.invalidateFlowsByDevice(port2OnHost1.getId)))
        // invalidation with tag datapath port short id
        msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        assert(msg.tag.equals(FlowTagger.invalidateDPPort(numPort2OnHost1)))
        // flow installed for tunnel key = port
        msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        assert(msg.tag.equals(
            FlowTagger.invalidateByTunnelKey(port2OnHost1.getTunnelKey)))
        msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        assert(msg.tag.equals(FlowTagger.invalidateBroadcastFlows(bridge.getId,
            bridge.getId)))

        val flowInvalidated = wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        assert(flowInvalidated.f.equals(flowToInvalidate.f))
    }

    def testInvalidationRemovePortFromPortSet() {

        val port1OnHost1 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val port2OnHost1 = newExteriorBridgePort(bridge)
        //port2OnHost1.getTunnelKey should be (3)
        val portOnHost2 = newExteriorBridgePort(bridge)
        //portOnHost2.getTunnelKey should be (4)
        val portOnHost3 = newExteriorBridgePort(bridge)
        //portOnHost3.getTunnelKey should be (5)

        materializePort(port1OnHost1, host1, "port1a")
        materializePort(port2OnHost1, host1, "port1b")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")


        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // flows installed for tunnel key = port when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portsProbe.expectMsgClass(classOf[LocalPortActive])
        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = new WildcardFlowBuilder()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        // delete the port. The dp controller will invalidate all the flow whose
        // source or destination is this port. The port set is expanded into several
        // flows, the one corresponding to this port will be removed
        deletePort(port2OnHost1, host1)
        val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        //assert(msg.tag.equals(FlowTagger.invalidateDPPort(localPortNumber2.asShort())))
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
    }
}
