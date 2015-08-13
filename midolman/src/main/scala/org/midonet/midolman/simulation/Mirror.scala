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
package org.midonet.midolman.simulation

import java.util.UUID
import java.util.{List => JList}

import akka.actor.ActorSystem

import org.midonet.midolman.PacketWorkflow.{NoOp, SimulationResult}
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.midolman.topology.VirtualTopologyActor.tryAsk
import org.midonet.sdn.flows.FlowTagger

trait MirroringDevice extends SimDevice {
    def inboundMirrors: List[UUID]
    def outboundMirrors: List[UUID]

    private def mirror(mirrors: List[UUID], context: PacketContext,
                       next: SimulationResult, as: ActorSystem): SimulationResult = {
        implicit val _as: ActorSystem = as
        var result: SimulationResult = next

        var list = mirrors
        while (list.nonEmpty) {
            val mirror = tryAsk[Mirror](mirrors.head)
            list = mirrors.tail
            mirror process context match {
                case toPort: ToPortAction =>
                    result = Simulator.Fork(toPort, result)
                case _ => // mirror did not match
            }
        }
        continue(context, result)
    }

    def mirroringInbound(context: PacketContext, next: SimulationResult,
                         as: ActorSystem): SimulationResult = {
        mirror(inboundMirrors, context, next, as)
    }

    def mirroringOutbound(context: PacketContext, next: SimulationResult,
                          as: ActorSystem): SimulationResult = {
        mirror(outboundMirrors, context, next, as)
    }
}

case class Mirror(id: UUID, conditions: JList[Condition], toPort: UUID) extends VirtualDevice {
    override val deviceTag = FlowTagger.tagForMirror(id)

    def process(context: PacketContext)(implicit as: ActorSystem): SimulationResult = {
        context.addFlowTag(deviceTag)
        var i = 0
        while (i < conditions.size()) {
            if (conditions.get(i) matches context)
                return tryAsk[Port](toPort).action
            i += 1
        }
        NoOp
    }
}
