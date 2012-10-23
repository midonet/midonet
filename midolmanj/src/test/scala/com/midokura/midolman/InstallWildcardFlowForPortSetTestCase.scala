/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman


import scala.collection.JavaConversions._
import akka.testkit.TestProbe

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.DatapathController.TunnelChangeEvent
import com.midokura.midolman.FlowController.{AddWildcardFlow,
        InvalidateFlowsByTag, RemoveWildcardFlow, WildcardFlowRemoved}
import com.midokura.midolman.datapath.FlowActionOutputToVrnPortSet
import com.midokura.midolman.rules.{Condition, RuleResult}
import com.midokura.midolman.topology.{FlowTagger, LocalPortActive}
import com.midokura.midonet.cluster.data.Bridge
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.midonet.cluster.data.zones.{GreTunnelZone, GreTunnelZoneHost}
import com.midokura.packets.{IntIPv4, MAC}
import com.midokura.sdn.dp.flows._
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}


@RunWith(classOf[JUnitRunner])
class InstallWildcardFlowForPortSetTestCase extends MidolmanTestCase
with VirtualConfigurationBuilders {

    var tunnelZone: GreTunnelZone = null

    var host1: Host = null
    var host2: Host = null
    var host3: Host = null

    var bridge: Bridge = null
    var tunnelEventsProbe: TestProbe = null
    var portEventsProbe: TestProbe = null
    var flowRemovedProbe: TestProbe = null

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

        portEventsProbe = newProbe()
        actors().eventStream.subscribe(portEventsProbe.ref,
            classOf[LocalPortActive])

        flowRemovedProbe = newProbe()
        actors().eventStream.subscribe(flowRemovedProbe.ref,
                                       classOf[WildcardFlowRemoved])

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
    }

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
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[AddWildcardFlow](flowProbe())

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        val localPortNumber1 = dpController().underlyingActor
            .localPorts("port1a").getPortNo
        val localPortNumber2 = dpController().underlyingActor
            .localPorts("port1b").getPortNo
        val localPortNumber3 = dpController().underlyingActor
            .localPorts("port1c").getPortNo
        val wildcardFlow = new WildcardFlow()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId)
                                         .setEthernetSource(srcMAC))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        val pktBytes = "My packet".getBytes
        dpProbe().testActor.tell(AddWildcardFlow(
            wildcardFlow, None, pktBytes, null, null, null))

        val addFlowMsg = fishForRequestOfType[AddWildcardFlow](flowProbe())

        addFlowMsg should not be null
        addFlowMsg.pktBytes should equal(pktBytes)
        addFlowMsg.flow should equal (wildcardFlow)
        addFlowMsg.flow.getMatch.getInputPortUUID should be(null)
        addFlowMsg.flow.getMatch.getInputPortNumber should be(localPortNumber1)

        val flowActs = addFlowMsg.flow.getActions.toList

        flowActs should not be (null)
        // The ingress port should not be in the expanded port set
        flowActs should have size (5)

        // Compare FlowKeyTunnelID against bridge.getTunnelKey
        val setKeyAction = as[FlowActionSetKey](flowActs.get(flowActs.length - 3))
        as[FlowKeyTunnelID](setKeyAction.getFlowKey).getTunnelID should be(bridge.getTunnelKey)

        // TODO: Why does the "should contain" syntax fail here?
        assert(flowActs.contains(FlowActions.output(tunnelId1)))
        assert(flowActs.contains(FlowActions.output(tunnelId2)))
        // TODO(jlm): This shouldn't contain port2 because the chain should
        // drop it.
        assert(flowActs.contains(FlowActions.output(localPortNumber2))) //XXX
        //assert(!flowActs.contains(FlowActions.output(localPortNumber2))) //XXX
        assert(flowActs.contains(FlowActions.output(localPortNumber3)))
    }

    def testNewHostRemovedFromPortSet() {

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
        fishForRequestOfType[AddWildcardFlow](flowProbe())

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = new WildcardFlow()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        val pktBytes = "My packet".getBytes
        dpProbe().testActor.tell(AddWildcardFlow(
            wildcardFlow, None, pktBytes, null, null, null))

        val flowToInvalidate = fishForRequestOfType[AddWildcardFlow](flowProbe())

        clusterDataClient().portSetsDelHost(bridge.getId, host3.getId)
        // expect flow invalidation for the flow tagged using the bridge id and portSet id,
        // which are the same
        val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        assert(msg.tag.equals(FlowTagger.invalidateBroadcastFlows(bridge.getId,
            bridge.getId)) )

        val flowInvalidated = flowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        assert(flowInvalidated.f.equals(flowToInvalidate.flow))
    }

    def testNewHostAddedToPortSet() {

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
        fishForRequestOfType[AddWildcardFlow](flowProbe())

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = new WildcardFlow()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        val pktBytes = "My packet".getBytes
        dpProbe().testActor.tell(AddWildcardFlow(
            wildcardFlow, None, pktBytes, null, null, null))

        val flowToInvalidate = fishForRequestOfType[AddWildcardFlow](flowProbe())

        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        // expect flow invalidation for the flow tagged using the bridge id and portSet id,
        // which are the same
        val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        assert(msg.tag.equals(FlowTagger.invalidateBroadcastFlows(bridge.getId,
            bridge.getId)) )

        val flowInvalidated = flowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        assert(flowInvalidated.f.equals(flowToInvalidate.flow))
    }

    def testNewPortAddedToPortSet() {

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
        fishForRequestOfType[AddWildcardFlow](flowProbe())

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = new WildcardFlow()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        val pktBytes = "My packet".getBytes
        dpProbe().testActor.tell(AddWildcardFlow(
            wildcardFlow, None, pktBytes, null, null, null))

        val flowToInvalidate = fishForRequestOfType[AddWildcardFlow](flowProbe())

        val port2OnHost1 = newExteriorBridgePort(bridge)
        // TODO(ross) why is this not working?
        //materializePort(port2OnHost1, host1, "port1b")
        // flow installed for tunnel key = port
        // fishForRequestOfType[AddWildcardFlow](flowProbe())
        vtpProbe().testActor.tell(new LocalPortActive(port2OnHost1.getId, true))
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])
        // invalidation with tag port id
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        // expect flow invalidation for the flow tagged using the bridge id and portSet id,
        // which are the same
        val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        assert(msg.tag.equals(FlowTagger.invalidateBroadcastFlows(bridge.getId,
            bridge.getId)) )

        val flowInvalidated = flowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        assert(flowInvalidated.f.equals(flowToInvalidate.flow))

    }

    def testRemovePortFromPortSet() {

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
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[AddWildcardFlow](flowProbe())

        // Wait for LocalPortActive messages - they prove the
        // VirtualToPhysicalMapper has the correct information for the PortSet.
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        val wildcardFlow = new WildcardFlow()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        val pktBytes = "My packet".getBytes
        dpProbe().testActor.tell(AddWildcardFlow(
            wildcardFlow, None, pktBytes, null, null, null))

        fishForRequestOfType[AddWildcardFlow](flowProbe())
        // delete the port. The dp controller will invalidate all the flow whose
        // source or destination is this port. The port set is expanded into several
        // flows, the one corresponding to this port will be removed
        deletePort(port2OnHost1, host1)
        val msg = fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        //assert(msg.tag.equals(FlowTagger.invalidateDPPort(localPortNumber2.asShort())))
        flowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        flowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
    }
}
