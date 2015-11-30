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

import java.util.UUID

import org.junit.runner.RunWith
import org.midonet.packets.util.PacketBuilder._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state.{TraceState, NatState, ConnTrackState}
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.FlowActions
import org.midonet.packets.{MAC, IPv4Addr, Ethernet}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.functors.Callback0

@RunWith(classOf[JUnitRunner])
class PacketContextTest extends MidolmanSpec {

    feature("PacketContext linearization") {
        scenario("Clear works across different traits") {
            val packet = { { eth addr MAC.random() -> MAC.random() } <<
                           { ip4 addr IPv4Addr.random --> IPv4Addr.random } <<
                           { udp ports 5003 ---> 53 } << payload("payload") }

            val context = packetContextFor(packet)
            context.addFlowRemovedCallback(new Callback0 {
                override def call(): Unit = { }
            })
            context.addVirtualAction(FlowActions.output(1))
            context.flowActions.add(FlowActions.output(1))
            context.packetActions.add(FlowActions.output(1))
            context.stateActions.add(FlowActions.output(1))
            context.addFlowTag(FlowTagger.tagForDpPort(1))
            val connTrackKey = ConnTrackKey(context.origMatch, UUID.randomUUID())
            context.conntrackTx.putAndRef(connTrackKey, ConnTrackState.RETURN_FLOW)
            val natKey = NatKey(context.origMatch, UUID.randomUUID(), NatState.FWD_DNAT)
            context.natTx.putAndRef(natKey, NatBinding(IPv4Addr.random, 2))
            context.clear()

            context.flowRemovedCallbacks should be (empty)
            context.virtualFlowActions should be (empty)
            context.flowActions should be (empty)
            context.packetActions should be (empty)
            context.stateActions should be (empty)
            context.flowTags should be (empty)
            context.conntrackTx.size() should be (0)
            context.natTx.size() should be (0)
        }
    }
}
