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

import java.util.{UUID, Random, ArrayList}

import scala.collection.immutable.HashMap
import scala.collection.JavaConverters

import org.slf4j.LoggerFactory
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.devices.BridgePort
import org.midonet.odp.{FlowMatch, FlowMatches, Packet}
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowActions
import org.midonet.odp.flows.IpProtocol
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp.flows.IPFragmentType
import org.midonet.packets.{IPv4Addr, MAC, Ethernet, IPv4}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.{ShardedFlowStateTable, FlowStateTransaction}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.collection.Reducer

@RunWith(classOf[JUnitRunner])
class TraceStateTest extends MidolmanSpec {
    val log = LoggerFactory.getLogger(classOf[TraceStateTest])
    val rand = new Random

    scenario("TraceKey generated from FlowMatch unuse portnumbers and tunnnels") {
        val fm = FlowMatches.generateFlowMatch(rand)
            .setInputPortNumber(12000).setTunnelDst(31234)
            .setTunnelSrc(123423).setTunnelKey(2323)
        val fm2 = FlowMatches.generateFlowMatch(rand)
            .setInputPortNumber(12001).setTunnelDst(31232)
            .setTunnelSrc(123423).setTunnelKey(2323)
        val fm3 = new FlowMatch()
        fm3.reset(fm)
        fm3.setInputPortNumber(100)

        val key: TraceKey = new TraceKey(fm)
        key.flowMatch.isUsed(Field.InputPortNumber) should be (false)
        key.flowMatch.isUsed(Field.TunnelSrc) should be (false)
        key.flowMatch.isUsed(Field.TunnelDst) should be (false)
        key.flowMatch.isUsed(Field.TunnelKey) should be (false)

        val key2: TraceKey = new TraceKey(fm)
        key2.equals(key) should be (true)

        val key3: TraceKey = new TraceKey(fm2)
        key3.equals(key) should be (false)

        val key4: TraceKey = new TraceKey(fm3)
        key4.equals(key) should be (true)
    }
}
