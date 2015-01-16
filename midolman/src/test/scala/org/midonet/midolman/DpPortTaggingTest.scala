/*
 * Copyright 2015 Midokura SARL
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

import java.util.{ArrayList, List, UUID}

import scala.collection.mutable

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{Bridge => ClusterBridge}
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.PacketWorkflow.Drop
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.FlowStateReplicator
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.rcu.ResolvedHost
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.{Packet, Datapath, Flow, DpPort}
import org.midonet.odp.flows.{FlowAction, FlowActionOutput}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.util.functors.Callback0

@RunWith(classOf[JUnitRunner])
class DpPortTaggingTest extends MidolmanSpec {
    var bridge: ClusterBridge = _
    var inPort: BridgePort = _
    val inPortNumber = 1
    var outPort: BridgePort = _
    val outPortNumber = 2

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    override def beforeTest(): Unit = {
        bridge = newBridge("bridge")
        inPort = newBridgePort(bridge)
        outPort = newBridgePort(bridge)

        materializePort(outPort, hostId, "outPort")

        fetchTopology(bridge, inPort, outPort)
    }

    scenario ("Flow is tagged with input and output DP ports") {
        val context = packetContextFor({ eth src MAC.random() dst MAC.random() },
                                       inPort.getId)
        context.origMatch.setInputPortNumber(inPortNumber)
        context.wcmatch.setInputPortNumber(inPortNumber)
        workflow.start(context) should not be Drop
        context should be (taggedWith(FlowTagger.tagForDpPort(inPortNumber),
                                      FlowTagger.tagForDpPort(outPortNumber)))
    }

    def workflow = new PacketWorkflow(
        new DatapathState {
            override def host: ResolvedHost = new ResolvedHost(hostId, true, "midonet", Map(), Map())
            override def peerTunnelInfo(peer: UUID): Option[Route] = None
            override def isVtepTunnellingPort(portNumber: Integer): Boolean = false
            override def isOverlayTunnellingPort(portNumber: Integer): Boolean = false
            override def vtepTunnellingOutputAction: FlowActionOutput = null
            override def getDescForInterface(itfName: String): Option[InterfaceDescription] = None
            override def getDpPortForInterface(itfName: String): Option[DpPort] = None
            override def getVportForDpPortNumber(portNum: Integer): Option[UUID] =
                Some(inPort.getId)
            override def dpPortNumberForTunnelKey(tunnelKey: Long): Option[DpPort] = None
            override def getDpPortNumberForVport(vportId: UUID): Option[Integer] =
                if (vportId == outPort.getId)
                    Some(outPortNumber)
                else
                    None
            override def getDpPortName(num: Integer): Option[String] =  None
        }, null, clusterDataClient, new DatapathChannel {
            override def executePacket(packet: Packet,
                                       actions: List[FlowAction]): Unit = { }
            override def createFlow(flow: Flow): Unit = { }
            override def start(datapath: Datapath): Unit = { }
            override def stop(): Unit = { }
        }, new FlowStateReplicator(null, null, null, new UnderlayResolver {
            override def host: ResolvedHost = new ResolvedHost(UUID.randomUUID(), true, "", Map(), Map())
            override def peerTunnelInfo(peer: UUID): Option[Route] = None
            override def vtepTunnellingOutputAction: FlowActionOutput = null
            override def isVtepTunnellingPort(portNumber: Integer): Boolean = false
            override def isOverlayTunnellingPort(portNumber: Integer): Boolean = false
        }, null, 0) {
            override def pushState(dpChannel: DatapathChannel): Unit = { }
            override def accumulateNewKeys(
                          conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue],
                          natTx: FlowStateTransaction[NatKey, NatBinding],
                          ingressPort: UUID, egressPorts: List[UUID],
                          tags: mutable.Set[FlowTag],
                          callbacks: ArrayList[Callback0]): Unit = { }
        })
}
