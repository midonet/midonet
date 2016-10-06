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

import org.midonet.midolman.PacketWorkflow.{NoOp, SimulationResult}
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.midolman.topology.VirtualTopology.{VirtualDevice, tryGet}
import org.midonet.sdn.flows.FlowTagger

trait MirroringDevice extends SimDevice {
    def inboundMirrors: JList[UUID]
    def outboundMirrors: JList[UUID]

    private final def mirror(mirrors: JList[UUID], context: PacketContext,
                             next: SimulationResult): SimulationResult = {
        var result: SimulationResult = next

        var i = 0
        while (i < mirrors.size) {
            val mirror = tryGet(classOf[Mirror], mirrors.get(i))
            i += 1
            mirror process context match {
                case toPort: ToPortAction =>
                    context.log.debug(s"Mirroring packet out to port ${mirror.toPort}")
                    result = Simulator.Fork(toPort, result)
                case _ => // mirror did not match
            }
        }
        result
    }

    final def mirroringInbound(context: PacketContext, next: SimulationResult)
            : SimulationResult = {
        continue(context, mirror(inboundMirrors, context, next))
    }

    final def mirroringOutbound(context: PacketContext, next: SimulationResult)
            : SimulationResult = {
        continue(context, mirror(outboundMirrors, context, next))
    }
}

case class Mirror(id: UUID, conditions: JList[Condition], toPort: UUID) extends VirtualDevice {
    override val deviceTag = FlowTagger.tagForMirror(id)

    def process(context: PacketContext): SimulationResult = {
        context.log.debug(s"Processing mirror $id")
        context.addFlowTag(deviceTag)
        context.devicesTraversed += 1
        var i = 0
        while (i < conditions.size()) {
            if (conditions.get(i) matches context)
                return tryGet(classOf[Port], toPort).action
            i += 1
        }
        NoOp
    }
}
