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

package org.midonet.brain.services.flowtracing

import java.util.{Date, List, UUID}
import org.midonet.cluster.flowtracing.FlowTracing.{FlowTrace => FlowTraceProto}
import org.midonet.packets.{IPAddr, MAC}

trait FlowTracingStorageBackend {
    def getFlowCount(traceRequestId: UUID,
                     maxTime: Date, limit: Int): Long
    def getFlowTraces(traceRequestId: UUID,
                      maxTime: Date,
                      limit: Int): List[FlowTrace]
    def getFlowTraceData(traceRequestId: UUID,
                         flowTraceId: UUID,
                         maxTime: Date,
                         limit: Int): (FlowTrace, List[FlowTraceData])
}

case class FlowTrace(id: UUID, ethSrc: MAC, ethDst: MAC, etherType: Short = 0,
                     networkSrc: IPAddr = null, networkDst: IPAddr = null,
                     networkProto: Byte = 0,
                     srcPort: Int = 0, dstPort: Int = 0)

case class FlowTraceData(host: UUID, timestamp: Long, data: String)

class FlowTracingStorageException extends Exception {
}

class TraceNotFoundException extends FlowTracingStorageException {}

