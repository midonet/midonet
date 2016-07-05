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

import org.midonet.logging.rule.Result
import org.midonet.midolman.logging.rule.RuleLogEventChannel
import org.midonet.midolman.rules.Rule
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

case class RuleLogger(id: UUID,
                      logAcceptEvents: Boolean,
                      logDropEvents: Boolean,
                      eventChannel: RuleLogEventChannel) extends VirtualDevice {

    override def deviceTag: FlowTag = FlowTagger.tagForRuleLogger(id)

    def logAccept(pktCtx: PacketContext, chain: Chain, rule: Rule): Unit = {
        if (logAcceptEvents)
            logEvent(pktCtx, Result.ACCEPT, chain, rule)
    }

    def logDrop(pktCtx: PacketContext, chain: Chain, rule: Rule): Unit = {
        if (logDropEvents)
            logEvent(pktCtx, Result.DROP, chain, rule)
    }

    private def logEvent(pktCtx: PacketContext, result: Result,
                         chain: Chain, rule: Rule): Unit = {
        val m = pktCtx.wcmatch
        m.doNotTrackSeenFields()
        eventChannel.handoff(id, m.getNetworkProto, chain, rule,
                             m.getNetworkSrcIP, m.getNetworkDstIP,
                             m.getSrcPort, m.getDstPort, result)
        m.doTrackSeenFields()
    }
}
