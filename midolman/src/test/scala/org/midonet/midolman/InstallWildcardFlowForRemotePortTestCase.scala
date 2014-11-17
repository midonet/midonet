/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
            tunnelZone.getId, new TunnelZone.HostConfig(host1.getId).setIp(srcIp))

        clusterDataClient().tunnelZonesAddMembership(
            tunnelZone.getId, new TunnelZone.HostConfig(host2.getId).setIp(dstIp))

        initializeDatapath() should not be (null)

        val datapath = datapathEventsProbe
            .expectMsgType[DatapathController.DatapathReady].datapath
        datapath should not be null

        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val inputPortNo = getPortNumber("port1")

        fishForRequestOfType[AddWildcardFlow](flowProbe())
        drainProbes()

        addVirtualWildcardFlow(portOnHost1.getId,
                               FlowActionOutputToVrnPort(portOnHost2.getId))

        val addFlowMsg = fishForRequestOfType[WildcardFlowAdded](
            wflowAddedProbe, Duration(3, TimeUnit.SECONDS))

        addFlowMsg should not be null
        addFlowMsg.f should not be null
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
