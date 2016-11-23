/*
 * Copyright 2014 Midokura SARL
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

import java.util.UUID

import scala.collection.immutable.HashMap

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.simulation.BridgePort
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.odp.flows.FlowKeys
import org.midonet.packets.NatState.NatBinding
import org.midonet.packets.{IPv4Addr, MAC, Ethernet}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.{ShardedFlowStateTable, FlowStateTransaction}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.collection.Reducer

@RunWith(classOf[JUnitRunner])
class ConntrackStateTest extends MidolmanSpec {

    val ping: Ethernet =
        { eth src MAC.random() dst MAC.random() } <<
        { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
        { icmp.echo id 42000.toShort }

    val portId = UUID.randomUUID()
    val ingressDevice = UUID.randomUUID()
    val egressDevice = UUID.randomUUID()

    val connTrackStateTable = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue]().addShard()
    val connTrackTx = new FlowStateTransaction(connTrackStateTable)

    override def beforeTest(): Unit = {
        val port = new BridgePort(id = portId, networkId = ingressDevice)
        VirtualTopology.add(portId, port)
    }

    def context(eth: Ethernet = ping, egressPort: UUID = null) = {
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(eth))
        val ctx = PacketContext.generated(1, new Packet(eth, fmatch), fmatch, egressPort)
        ctx.initialize(connTrackTx,
                       new FlowStateTransaction[NatKey, NatBinding](null),
                       HappyGoLuckyLeaser,
                       new FlowStateTransaction[TraceKey, TraceContext](null))
        ctx.inputPort = portId
        ctx
    }

    feature("Connections are tracked") {
        scenario("Non-ip packets are considered forward flows") {
            val ctx = context({ eth src MAC.random() dst MAC.random() })
            ctx.isForwardFlow should be (true)
            connTrackTx.size() should be (0)
            ctx should be (taggedWith ())
        }

        scenario("Generated packets are treated as return flows") {
            val ctx = context(ping, UUID.randomUUID())
            ctx.isForwardFlow should be (false)
            connTrackTx.size() should be (0)
            ctx should be (taggedWith ())
        }

        scenario("Forward flows are tracked") {
            val ctx = context()

            val ingressKey = ConnTrackKey(ctx.wcmatch, ingressDevice)
            val egressKey = ConnTrackState.EgressConnTrackKey(ctx.wcmatch, egressDevice)

            ctx.isForwardFlow should be (true)
            ctx should be (taggedWith (ingressKey))

            ctx.trackConnection(egressDevice)
            ctx should be (taggedWith (ingressKey))

            connTrackTx.size() should be (1)
            val values = transactionValues(connTrackTx)

            values should contain theSameElementsAs Map(egressKey -> false)
            connTrackStateTable.getRefCount(egressKey) should be (0)
            connTrackTx.commit()
            connTrackStateTable.getRefCount(ingressKey) should be (0)
            connTrackStateTable.getRefCount(egressKey) should be (1)
        }

        scenario("Forward flows are recognized") {
            val ctx = context()

            val ingressKey = ConnTrackKey(ctx.wcmatch, ingressDevice)
            val egressKey = ConnTrackState.EgressConnTrackKey(ctx.wcmatch, egressDevice)

            connTrackStateTable.putAndRef(egressKey, false)

            ctx.isForwardFlow should be (true)
            ctx should be (taggedWith (ingressKey))

            ctx.trackConnection(egressDevice)
            ctx should be (taggedWith (ingressKey))

            connTrackTx.size() should be (1)
            val values = transactionValues(connTrackTx)

            values should contain theSameElementsAs Map(egressKey -> false)
            connTrackStateTable.getRefCount(egressKey) should be (1)
            connTrackTx.commit()
            connTrackStateTable.getRefCount(ingressKey) should be (0)
            connTrackStateTable.getRefCount(egressKey) should be (2)
        }

        scenario("Return flows are recognized") {
            val ctx = context()

            val ingressKey = ConnTrackKey(ctx.wcmatch, ingressDevice)

            connTrackStateTable.putAndRef(ingressKey, false)

            ctx.isForwardFlow should be (false)
            ctx should be (taggedWith ())
            ctx.trackConnection(egressDevice)
            ctx should be (taggedWith ())

            connTrackTx.size() should be (0)
        }
    }

    feature("Regressions") {
        scenario("Clear after resetContext failure") {
            val ctx = context()
            ctx.resetContext()
            ctx.clear()
        }
    }

    def transactionValues[K, V](tx: FlowStateTransaction[K, V]): HashMap[K, V] =
       tx.fold(new HashMap[K, V](),
               new Reducer[K, V, HashMap[K, V]] {
                    override def apply(acc: HashMap[K, V], key: K, value: V) =
                        acc + (key -> value)
                })
}
