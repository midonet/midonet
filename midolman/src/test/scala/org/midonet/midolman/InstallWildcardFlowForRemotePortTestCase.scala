/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.concurrent.TimeUnit
import akka.util.Duration

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.DatapathController.TunnelChangeEvent
import org.midonet.midolman.FlowController.{AddWildcardFlow, WildcardFlowAdded}
import org.midonet.midolman.PacketWorkflowActor.AddVirtualWildcardFlow
import org.midonet.midolman.datapath.FlowActionOutputToVrnPort
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.cluster.data.zones.GreTunnelZoneHost
import org.midonet.odp.flows.{FlowActionOutput, FlowActionSetKey,
    FlowKeyTunnelID}
import org.midonet.packets.IntIPv4
import org.midonet.sdn.flows.{WildcardMatch, WildcardFlow}


@RunWith(classOf[JUnitRunner])
class InstallWildcardFlowForRemotePortTestCase extends MidolmanTestCase
    with VirtualConfigurationBuilders {

    def testInstallFlowForRemoteSinglePort() {

        val tunnelZone = greTunnelZone("default")

        val host1 = newHost("host1", hostId())
        val host2 = newHost("host2")

        val bridge = newBridge("bridge")

        val portOnHost1 = newExteriorBridgePort(bridge)
        val portOnHost2 = newExteriorBridgePort(bridge)

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
        actors().eventStream.subscribe(tunnelEventsProbe.ref,
                                       classOf[TunnelChangeEvent])

        val flowEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref,
                                       classOf[WildcardFlowAdded])

        val portEventsProbe = newProbe()
        actors().eventStream.subscribe(portEventsProbe.ref,
                                       classOf[LocalPortActive])

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[
            DatapathController.DatapathReady].datapath should not be (null)
        portEventsProbe.expectMsgClass(classOf[LocalPortActive])

        val tunnelId = tunnelEventsProbe
            .expectMsgClass(classOf[TunnelChangeEvent]).portOption.get

        val inputPortNo = dpController().underlyingActor
            .ifaceNameToDpPort("port1").getPortNo

        val wildcardFlow = WildcardFlow(
            wcmatch = new WildcardMatch().setInputPortUUID(portOnHost1.getId),
            actions = List(new FlowActionOutputToVrnPort(portOnHost2.getId)))

        fishForRequestOfType[AddWildcardFlow](flowProbe())
        drainProbe(wflowAddedProbe)

        dpProbe().testActor.tell(
            AddVirtualWildcardFlow(wildcardFlow, Set.empty, Set.empty))

        val addFlowMsg = fishForRequestOfType[WildcardFlowAdded](
            wflowAddedProbe, Duration(3, TimeUnit.SECONDS))

        addFlowMsg should not be null
        addFlowMsg.f should not be null
        addFlowMsg.f.getMatch.getInputPortUUID should be(null)
        addFlowMsg.f.getMatch.getInputPortNumber should be(inputPortNo)

        val flowActs = addFlowMsg.f.actions
        flowActs should not be (null)
        flowActs should have size(2)

        val setKeyAction = as[FlowActionSetKey](flowActs(0))
        as[FlowKeyTunnelID](setKeyAction.getFlowKey)
                .getTunnelID should be (portOnHost2.getTunnelKey)

        val outputActions = as[FlowActionOutput](flowActs(1))
        outputActions.getPortNumber should be (tunnelId)
    }
}
