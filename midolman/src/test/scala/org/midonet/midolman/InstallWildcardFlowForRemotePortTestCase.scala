/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.FlowController.{AddWildcardFlow, WildcardFlowAdded}
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.packets.IPv4Addr
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort
import org.midonet.sdn.flows.{WildcardMatch, WildcardFlow}
import org.midonet.odp.flows.FlowActions.output
import org.midonet.cluster.data.TunnelZone

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class InstallWildcardFlowForRemotePortTestCase extends MidolmanTestCase {
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
            tunnelZone.getId, new TunnelZone.HostConfig(host1.getId).setIp(srcIp.toIntIPv4))

        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId, new TunnelZone.HostConfig(host2.getId).setIp(dstIp.toIntIPv4))

        initializeDatapath() should not be (null)

        val datapath =
            flowProbe().expectMsgType[DatapathController.DatapathReady].datapath
        datapath should not be null

        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val inputPortNo = getPortNumber("port1")

        val wildcardFlow = WildcardFlow(
            wcmatch = new WildcardMatch().setInputPortUUID(portOnHost1.getId),
            actions = List(new FlowActionOutputToVrnPort(portOnHost2.getId)))

        fishForRequestOfType[AddWildcardFlow](flowProbe())
        drainProbes()

        dpProbe().testActor !
            AddVirtualWildcardFlow(wildcardFlow, Nil, Set.empty)

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
        outputs should contain(output(greTunnelId))

        tunnelKeys should have size(1)
        tunnelKeys.find(tunnelIsLike(srcIp.toInt, dstIp.toInt, portOnHost2.getTunnelKey)) should not be None

    }

}
