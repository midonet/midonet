/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman


import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.DatapathController.TunnelChangeEvent
import com.midokura.midolman.FlowController.AddWildcardFlow
import datapath.FlowActionOutputToVrnPortSet
import com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost
import com.midokura.sdn.dp.flows._
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}
import com.midokura.packets.IntIPv4
import topology.LocalPortActive


@RunWith(classOf[JUnitRunner])
class InstallWildcardFlowForPortSetTestCase extends MidolmanTestCase
    with VirtualConfigurationBuilders {

    def testInstallFlowForPortSet() {

        val tunnelZone = greTunnelZone("default")

        val host1 = newHost("host1", hostId())
        val host2 = newHost("host2")
        val host3 = newHost("host3")

        val bridge = newBridge("bridge")

        val port1OnHost1 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (2)
        val port2OnHost1 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (3)
        val port3OnHost1 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (4)
        val portOnHost2 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (5)
        val portOnHost3 = newExteriorBridgePort(bridge)
        //port1OnHost1.getTunnelKey should be (6)

        //val chain1 = newOutboundChainOnPort("chain1", port2OnHost1)
        //val rule1 = newLiteralRuleOnChain(chain1, 0, null, null)  //XXX

        materializePort(port1OnHost1, host1, "port1a")
        materializePort(port2OnHost1, host1, "port1b")
        materializePort(port3OnHost1, host1, "port1c")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")

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


        clusterDataClient().portSetsAddHost(bridge.getId, host2.getId)
        clusterDataClient().portSetsAddHost(bridge.getId, host3.getId)

        val tunnelEventsProbe = newProbe()
        actors().eventStream.subscribe(tunnelEventsProbe.ref,
                                       classOf[TunnelChangeEvent])

        val portEventsProbe = newProbe()
        actors().eventStream.subscribe(portEventsProbe.ref,
                                       classOf[LocalPortActive])

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        val tunnelId1 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get
        val tunnelId2 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get

        val localPortNumber1 = dpController().underlyingActor
                                             .localPorts("port1a").getPortNo
        val localPortNumber2 = dpController().underlyingActor
                                             .localPorts("port1b").getPortNo
        val localPortNumber3 = dpController().underlyingActor
                                             .localPorts("port1c").getPortNo

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

        val wildcardFlow = new WildcardFlow()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
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
        assert(flowActs.contains(FlowActions.output(localPortNumber2)))
        assert(flowActs.contains(FlowActions.output(localPortNumber3)))
    }
}
