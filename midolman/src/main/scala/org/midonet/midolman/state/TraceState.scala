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

package org.midonet.midolman.state

import java.nio.ByteBuffer
import java.util.{Arrays,HashSet,List,Objects,Set,UUID,Collections}
import java.util.concurrent.ThreadLocalRandom

import org.slf4j.MDC
import org.slf4j.LoggerFactory
import scala.collection.JavaConverters._
import scala.concurrent.duration._

import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.FlowState.FlowStateKey
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.{FlowAction, FlowActionSetKey}
import org.midonet.packets.{Ethernet, IPv4Addr, IPv4, ICMP}
import org.midonet.packets.{TCP, UDP, BasePacket}
import org.midonet.sdn.state.FlowStateTransaction

object TraceState {
    val log = LoggerFactory.getLogger(classOf[TraceState])

    class TraceKey(origMatch: FlowMatch,
                   actions: List[FlowAction] = Collections.emptyList())
            extends FlowStateKey {
        val flowMatch: FlowMatch = new FlowMatch()
        flowMatch.reset(origMatch)
        flowMatch.fieldUnused(Field.InputPortNumber)
        flowMatch.fieldUnused(Field.TunnelDst)
        flowMatch.fieldUnused(Field.TunnelSrc)
        flowMatch.fieldUnused(Field.TunnelKey)

        var i = actions.size
        while (i > 0) {
            i -= 1
            actions.get(i) match {
                case k: FlowActionSetKey =>
                    flowMatch.addKey(k.getFlowKey)
            }
        }

        override def hashCode(): Int = Objects.hashCode(flowMatch)
        override def equals(other: Any): Boolean = other match {
            case that: TraceKey => flowMatch.equals(that.flowMatch)
            case _ => false
        }
        override def toString(): String =
            "TraceKey(" + flowMatch.toString + ")"
    }

    case class TraceContext(flowTraceId: UUID = UUID.randomUUID) {
        val requests: Set[UUID] = new HashSet[UUID]

        def addRequest(request: UUID): Boolean = requests.add(request)
        def containsRequest(request: UUID): Boolean = requests.contains(request)
    }
}

trait TraceState extends FlowState { this: PacketContext =>
    import org.midonet.midolman.state.TraceState._

    var traceTx: FlowStateTransaction[TraceKey, TraceContext] = _
}
