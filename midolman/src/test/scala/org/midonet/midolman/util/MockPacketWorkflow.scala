/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.midolman.util

import java.util.{UUID, LinkedList => JLinkedList, List => JList, Queue => JQueue}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem

import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman._
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.monitoring.FlowRecorder
import org.midonet.midolman.simulation.{DhcpConfigFromNsdb, PacketContext, _}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state._
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.odp._
import org.midonet.packets.NatState.NatBinding
import org.midonet.sdn.state.FlowStateTable
import org.midonet.util.concurrent._
import org.midonet.util.concurrent.NanoClock

import org.midonet.midolman.SimulationBackChannel
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.state.PeerResolver
import org.midonet.midolman.datapath.{DatapathChannel, FlowProcessor}
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics

class MockPacketWorkflow(config: MidolmanConfig,
                         hostId: UUID,
                         dpState: DatapathState,
                         clock: NanoClock,
                         dpChannel: DatapathChannel,
                         vt: VirtualTopology,
                         simBackChannel: SimulationBackChannel,
                         flowProcessor: FlowProcessor,
                         connTrackStateTable: FlowStateTable[ConnTrackKey, ConnTrackValue],
                         natStateTable: FlowStateTable[NatKey, NatBinding],
                         traceStateTable: FlowStateTable[TraceKey, TraceContext],
                         peerResolver: PeerResolver,
                         metrics: PacketPipelineMetrics,
                         flowRecorder: FlowRecorder,
                         packetCtxTrap: JQueue[PacketContext],
                         workflowTrap: PacketContext => SimulationResult)
                        (implicit as: ActorSystem, ex: ExecutionContext)
        extends PacketWorkflow(1, 0, config,
                               hostId, dpState,
                               new CookieGenerator(0, 1),
                               clock, dpChannel,
                               new DhcpConfigFromNsdb(vt),
                               simBackChannel, flowProcessor,
                               connTrackStateTable, natStateTable,
                               traceStateTable, peerResolver,
                               HappyGoLuckyLeaser, metrics,
                               flowRecorder, vt,
                               _ => { }) {
    override def runWorkflow(pktCtx: PacketContext) = {
        packetCtxTrap.offer(pktCtx)
        super.runWorkflow(pktCtx)
    }

    override def start(pktCtx: PacketContext): SimulationResult =
        if (workflowTrap ne null) {
            pktCtx.prepareForSimulation()
            workflowTrap(pktCtx)
        } else {
            super.start(pktCtx)
        }

    def handlePackets(packets: Packet*): Unit = {
        packets foreach handlePacket

        while (shouldProcess) {
            process()
        }
    }
}
