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

import java.util.{ArrayList, Collections, List, UUID}

import org.slf4j.LoggerFactory
import scala.collection.JavaConverters._

import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.FlowState.FlowStateKey
import org.midonet.odp.FlowMatch
import org.midonet.packets.{MAC, IPAddr}
import org.midonet.sdn.state.FlowStateTransaction

object TraceState {
    val log = LoggerFactory.getLogger(classOf[TraceState])

    object TraceKey {
        def fromFlowMatch(flowMatch: FlowMatch): TraceKey = {
            flowMatch.doNotTrackSeenFields
            val key = TraceKey(flowMatch.getEthSrc, flowMatch.getEthDst,
                               flowMatch.getEtherType,
                               flowMatch.getNetworkSrcIP,
                               flowMatch.getNetworkDstIP,
                               flowMatch.getNetworkProto,
                               flowMatch.getSrcPort,
                               flowMatch.getDstPort)
            flowMatch.doTrackSeenFields
            key
        }
    }

    case class TraceKey(ethSrc: MAC, ethDst: MAC, etherType: Short,
                        networkSrc: IPAddr, networkDst: IPAddr,
                        networkProto: Byte,
                        srcPort: Int, dstPort: Int)
            extends FlowStateKey {
    }

    case class TraceContext(flowTraceId: UUID = UUID.randomUUID) {
        val requests: List[UUID] = new ArrayList[UUID](1)

        def addRequest(request: UUID): Boolean = requests.add(request)
        def containsRequest(request: UUID): Boolean = requests.contains(request)
    }
}

trait TraceState extends FlowState { this: PacketContext =>
    import org.midonet.midolman.state.TraceState._

    var tracing: List[UUID] = new ArrayList[UUID]
    var traceTx: FlowStateTransaction[TraceKey, TraceContext] = _
    private var clearEnabled = true

    def prepareForSimulationWithTracing(): Unit = {
        // clear non-trace info
        clearEnabled = false
        clear()
        clearEnabled = true
    }

    override def clear(): Unit = {
        super.clear()

        if (clearEnabled) {
            tracing.clear()
            traceTx.flush()
        }
    }
}
