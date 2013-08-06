/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.concurrent.TimeUnit
import akka.util.Duration

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.FlowController.{AddWildcardFlow, WildcardFlowAdded}
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.datapath.FlowActionOutputToVrnPort
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.cluster.data.zones.GreTunnelZoneHost
import org.midonet.odp.flows.FlowActions
import org.midonet.odp.flows.{FlowActionOutput, FlowActionSetKey, FlowKeyTunnel}
import org.midonet.packets.IPv4Addr
import org.midonet.sdn.flows.{WildcardMatch, WildcardFlow}


@RunWith(classOf[JUnitRunner])
class InstallWildcardFlowForRemotePortTestCase extends MidolmanTestCase
    with VirtualConfigurationBuilders {

    def testInstallFlowForRemoteSinglePort() {

        val srcIp = IPv4Addr("192.168.100.1")
        val dstIp = IPv4Addr("192.168.200.1")

        val tunnelZone = greTunnelZone("default")

        val host1 = newHost("host1", hostId())
        val host2 = newHost("host2")

        val bridge = newBridge("bridge")

        val portOnHost1_unMaterialized = newBridgePort(bridge)
        val portOnHost2_unMaterialized = newBridgePort(bridge)

        val portOnHost1 = materializePort(portOnHost1_unMaterialized, host1,
          "port1")
        val portOnHost2 = materializePort(portOnHost2_unMaterialized, host2,
          "port2")

        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId, new GreTunnelZoneHost(host1.getId).setIp(srcIp.toIntIPv4))

        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId, new GreTunnelZoneHost(host2.getId).setIp(dstIp.toIntIPv4))

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

        val inputPortNo = getPortNumber("port1")

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

        val (outputs, tunnelKeys) = parseTunnelActions(flowActs)

        outputs should have size(1)
        outputs should contain(FlowActions.output(greTunnelId))

        tunnelKeys should have size(1)
        tunnelKeys.find(tunnelIsLike(srcIp.toInt, dstIp.toInt, portOnHost2.getTunnelKey)) should not be None

    }

}
