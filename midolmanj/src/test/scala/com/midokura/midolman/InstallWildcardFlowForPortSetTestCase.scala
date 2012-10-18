/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman


import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.DatapathController.TunnelChangeEvent
import com.midokura.midolman.FlowController.{AddWildcardFlow,
        InvalidateFlowsByTag, WildcardFlowAdded}
import com.midokura.midolman.datapath.{FlowActionOutputToVrnPort,
        FlowActionOutputToVrnPortSet}
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge}
import com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost
import com.midokura.sdn.dp.flows._
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}
import com.midokura.packets.IntIPv4


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
        val port2OnHost1 = newExteriorBridgePort(bridge)
        val port3OnHost1 = newExteriorBridgePort(bridge)
        val portOnHost2 = newExteriorBridgePort(bridge)
        val portOnHost3 = newExteriorBridgePort(bridge)

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

        val flowEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref,
                                       classOf[WildcardFlowAdded])

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

        // flows installed for tunnel key = port1 when the port becomes active.
        // There are three ports on this host.
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[AddWildcardFlow](flowProbe())


        val wildcardFlow = new WildcardFlow()
            .setMatch(new WildcardMatch().setInputPortUUID(port1OnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        dpProbe().testActor.tell(AddWildcardFlow(
            wildcardFlow, None, "My packet".getBytes, null, null, null))
        // TODO(ross) finish flow invalidation
        // TODO(ross) shall we automatically install flows for the portSet? When
        // a port is included in the port set shall we install the flow from tunnel
        // with key portSetID to port?
        val addFlowMsg = fishForRequestOfType[AddWildcardFlow](flowProbe())


        addFlowMsg should not be null
        addFlowMsg.pktBytes should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow.getMatch.getInputPortUUID should be(null)
        addFlowMsg.flow.getMatch.getInputPortNumber should be(localPortNumber1)

        val flowActs = addFlowMsg.flow.getActions.toList

        flowActs should not be (null)
        // The ingress port should not be in the expanded port set
        flowActs should have size (5)

        val setKeyAction = as[FlowActionSetKey](flowActs.get(0))
        as[FlowKeyTunnelID](setKeyAction.getFlowKey).getTunnelID should be(bridge.getTunnelKey)

        // TODO: Why does the "should contain" syntax fail here?
        assert(flowActs.contains(FlowActions.output(tunnelId1)))
        assert(flowActs.contains(FlowActions.output(tunnelId2)))
        assert(flowActs.contains(FlowActions.output(localPortNumber2)))
        assert(flowActs.contains(FlowActions.output(localPortNumber3)))
    }
}
