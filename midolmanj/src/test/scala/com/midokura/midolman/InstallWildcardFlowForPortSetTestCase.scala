/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman


import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.FlowController.{InvalidateFlowsByTag, AddWildcardFlow, WildcardFlowAdded}
import datapath.{FlowActionOutputToVrnPortSet, FlowActionOutputToVrnPort}
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge}
import com.midokura.sdn.flows.{WildcardMatch, WildcardFlow}
import com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost
import com.midokura.packets.IntIPv4
import com.midokura.midolman.DatapathController.TunnelChangeEvent
import com.midokura.sdn.dp.flows._
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.FlowController.WildcardFlowAdded
import com.midokura.midolman.FlowController.InvalidateFlowsByTag

@RunWith(classOf[JUnitRunner])
class InstallWildcardFlowForPortSetTestCase extends MidolmanTestCase
    with VirtualConfigurationBuilders {

    def testInstallFlowForPortSet() {

        val tunnelZone = greTunnelZone("default")

        val host1 = newHost("host1", hostId())
        val host2 = newHost("host2")
        val host3 = newHost("host3")

        val bridge = newBridge("bridge")

        val portOnHost1 = newPortOnBridge(bridge)
        val portOnHost2 = newPortOnBridge(bridge)
        val portOnHost3 = newPortOnBridge(bridge)

        materializePort(portOnHost1, host1, "port1")
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
        actors().eventStream.subscribe(tunnelEventsProbe.ref, classOf[TunnelChangeEvent])

        val flowEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        val tunnelId1 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get
        val tunnelId2 = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get

        val localPortNumber = dpController().underlyingActor.localPorts("port1").getPortNo

        // for the local exterior port
        fishForRequestOfType[AddWildcardFlow](flowProbe())

        val wildcardFlow = new WildcardFlow()
            .setMatch(new WildcardMatch().setInputPortUUID(portOnHost1.getId))
            .addAction(new FlowActionOutputToVrnPortSet(bridge.getId))

        dpProbe().testActor.tell(AddWildcardFlow(
            wildcardFlow, None, "My packet".getBytes(), null, null))

        requestOfType[InvalidateFlowsByTag](flowProbe())
        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())

        flowEventsProbe.expectMsgClass(classOf[WildcardFlowAdded])

        addFlowMsg should not be null
        addFlowMsg.pktBytes should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow.getMatch.getInputPortUUID should be(null)
        addFlowMsg.flow.getMatch.getInputPortNumber should be(localPortNumber)

        val flowActs = addFlowMsg.flow.getActions.toList

        flowActs should not be (null)
        // The ingress port should not be in the expanded port set
        flowActs should have size (3)

        val setKeyAction = as[FlowActionSetKey](flowActs.get(0))
        as[FlowKeyTunnelID](setKeyAction.getFlowKey).getTunnelID should be(bridge.getTunnelKey)

        flowActs.contains(FlowActions.output(tunnelId2)) should be (true)
        flowActs.contains(FlowActions.output(tunnelId1)) should be (true)
    }
}
