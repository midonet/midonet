/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman


import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.FlowController.{AddWildcardFlow, WildcardFlowAdded}
import com.midokura.midolman.datapath.FlowActionOutputToVrnPort
import com.midokura.sdn.flows.{WildcardMatch, WildcardFlow}
import com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost
import com.midokura.packets.IntIPv4
import com.midokura.midolman.DatapathController.TunnelChangeEvent
import com.midokura.sdn.dp.flows.{FlowActionOutput, FlowKeyTunnelID, FlowActionSetKey}

@RunWith(classOf[JUnitRunner])
class InstallWildcardFlowForRemotePortTestCase extends MidolmanTestCase
    with VirtualConfigurationBuilders {

    def testInstallFlowForRemoteSinglePort() {

        val tunnelZone = greTunnelZone("default")

        val host1 = newHost("host1", hostId())
        val host2 = newHost("host2")

        val bridge = newBridge("bridge")

        val portOnHost1 = newPortOnBridge(bridge)
        val portOnHost2 = newPortOnBridge(bridge)

        materializePort(portOnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")

        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new GreTunnelZoneHost(host1.getId)
                .setIp(IntIPv4.fromString("192.168.100.1")))
        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId,
            new GreTunnelZoneHost(host2.getId)
                .setIp(IntIPv4.fromString("192.168.200.1")))


        val tunnelEventsProbe = newProbe()
        actors().eventStream.subscribe(tunnelEventsProbe.ref, classOf[TunnelChangeEvent])

        val flowEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        val tunnelId = tunnelEventsProbe.expectMsgClass(classOf[TunnelChangeEvent]).portOption.get

        val inputPortNo = dpController().underlyingActor.localPorts("port1").getPortNo

        val wildcardFlow = new WildcardFlow()
            .setMatch(new WildcardMatch().setInputPortUUID(portOnHost1.getId))
            .addAction(new FlowActionOutputToVrnPort(portOnHost2.getId))

        dpProbe().testActor.tell(AddWildcardFlow(wildcardFlow, None,
            "my packet".getBytes(), null, null))

        val addFlowMsg = requestOfType[AddWildcardFlow](flowProbe())

        addFlowMsg should not be null
        addFlowMsg.pktBytes should not be null
        addFlowMsg.flow should not be null
        addFlowMsg.flow.getMatch.getInputPortUUID should be(null)
        addFlowMsg.flow.getMatch.getInputPortNumber should be(inputPortNo)

        val flowActs = addFlowMsg.flow.getActions
        flowActs should not be (null)
        flowActs should have size(2)

        val setKeyAction = as[FlowActionSetKey](flowActs.get(0))
        as[FlowKeyTunnelID](setKeyAction.getFlowKey).getTunnelID should be (portOnHost2.getGreKey)

        val outputActions = as[FlowActionOutput](flowActs.get(1))
        outputActions.getPortNumber should be (tunnelId)
    }
}
