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

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.DatapathController.DatapathReady
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.util.TestHelpers
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.cluster.data.{Bridge => ClusterBridge, Ports}
import org.midonet.cluster.data.host.Host


@RunWith(classOf[JUnitRunner])
class PacketInWorkflowTestCase extends MidolmanTestCase {
    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    def testDatapathPacketIn() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        val vifPort = Ports.bridgePort(bridge)
        vifPort.setId(clusterDataClient().portsCreate(vifPort))

        materializePort(vifPort, host, "port")

        initializeDatapath() should not be (null)

        val datapath = requestOfType[DatapathReady](flowProbe()).datapath
        datapath should not be null

        portsProbe.expectMsgClass(classOf[LocalPortActive])

        val portNo = getPortNumber("port")
        triggerPacketIn("port", TestHelpers.createUdpPacket(
                "10:10:10:10:10:10", "192.168.100.1",
                "10:10:10:10:10:11", "192.168.200.1"))

        val packetInMsg = requestOfType[PacketIn](packetInProbe)

        packetInMsg.wMatch should not be null
        // We cannot check that the input port ID has been set correctly
        // because of a race condition: when the simulation finishes, the
        // DatapathController sets the input port ID back to null before
        // passing the wMatch to the FlowController in a InstallWildcardFlow
        // message.
        packetInMsg.wMatch.getInputPortNumber should be (getPortNumber("port"))
    }
}
