/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.odp

import org.midonet.midolman.state.FlowStateAgentPackets
import org.midonet.packets.ICMP
import org.midonet.packets.TunnelKeys.TraceBit

object FlowMatchMessageType {

    def isICMPError(flowMatch: FlowMatch): Boolean = {
        flowMatch.getNetworkProto == ICMP.PROTOCOL_NUMBER &&
        ICMP.isError(flowMatch.getSrcPort.toByte) &&
        flowMatch.getIcmpData != null
    }

    def isFlowTracedOnEgress(flowMatch: FlowMatch): Boolean = {
        TraceBit.isSet(flowMatch.getTunnelKey.toInt) &&
        flowMatch.getTunnelKey != FlowStateAgentPackets.TUNNEL_KEY
    }

    def isFlowStateMessage(flowMatch: FlowMatch): Boolean = {
        flowMatch.getTunnelKey == FlowStateAgentPackets.TUNNEL_KEY
    }

}
