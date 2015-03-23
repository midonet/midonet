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

package org.midonet.midolman.logging

import java.util.{List, UUID}
import com.google.common.base.Joiner

import org.slf4j.MDC

import org.midonet.midolman.state.TraceState.TraceKey

object FlowTracingContext {
    val TraceRequestIdKey = "traceRequestId"
    val FlowTraceIdKey = "flowTraceId"
    val EthSrcKey = "ethSrc"
    val EthDstKey = "ethDst"
    val EtherTypeKey = "etherType"
    val NetworkSrcKey = "networkSrc"
    val NetworkDstKey = "networkDst"
    val NetworkProtoKey = "networkProto"
    val SrcPortKey = "srcPort"
    val DstPortKey = "dstPort"

    def updateContext(traceRequestIds: List[UUID], flowTraceId: UUID,
                      key: TraceKey): Unit = {
        // HACK ALERT: MDC can only store strings. A better solution would
        // be to use a logging facade for flow tracing, and not rely on MDC
        // for the context, but that'd a larger change, so just passing
        // a string for now.
        MDC.put(TraceRequestIdKey, Joiner.on(",").join(traceRequestIds))
        if (flowTraceId != null) {
            MDC.put(FlowTraceIdKey, flowTraceId.toString)
        }
        if (key.ethSrc != null) {
            MDC.put(EthSrcKey, key.ethSrc.toString)
        }
        if (key.ethDst != null) {
            MDC.put(EthDstKey, key.ethDst.toString)
        }
        MDC.put(EtherTypeKey, key.etherType.toString)
        if (key.networkSrc != null) {
            MDC.put(NetworkSrcKey, key.networkSrc.toString)
        }
        if (key.networkDst != null) {
            MDC.put(NetworkDstKey, key.networkDst.toString)
        }
        MDC.put(NetworkProtoKey, key.networkProto.toString)
        MDC.put(SrcPortKey, key.srcPort.toString)
        MDC.put(DstPortKey, key.dstPort.toString)
    }

    def clearContext(): Unit = {
        MDC.remove(TraceRequestIdKey)
        MDC.remove(FlowTraceIdKey)
        MDC.remove(EthSrcKey)
        MDC.remove(EthDstKey)
        MDC.remove(EtherTypeKey)
        MDC.remove(NetworkSrcKey)
        MDC.remove(NetworkDstKey)
        MDC.remove(NetworkProtoKey)
        MDC.remove(SrcPortKey)
        MDC.remove(DstPortKey)
    }
}
