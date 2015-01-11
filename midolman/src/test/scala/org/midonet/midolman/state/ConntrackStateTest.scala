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

import org.midonet.cluster.client.BridgePort
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.odp.flows.FlowKeys
import org.midonet.packets.{IPv4Addr, MAC, Ethernet}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.{ShardedFlowStateTable, FlowStateTransaction}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.collection.Reducer

@RunWith(classOf[JUnitRunner])
class ConntrackStateTest extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    val ping: Ethernet =
        { eth src MAC.random() dst MAC.random() } <<
        { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
        { icmp.echo id 42 }

    val portId = UUID.randomUUID()
    val ingressDevice = UUID.randomUUID()
    val egressDevice = UUID.randomUUID()

    val connTrackStateTable = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue]().addShard()
    val connTrackTx = new FlowStateTransaction(connTrackStateTable)

    override def beforeTest(): Unit = {
        val port = new BridgePort
        port.id = portId
        port.deviceID = ingressDevice
        VirtualTopologyActor ! PortRequest(portId)
        VirtualTopologyActor ! port
    }

    def context(cookieOrEgressPort: Either[Int, UUID],
                eth: Ethernet = ping) = {
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(eth))
        val ctx = new PacketContext(cookieOrEgressPort, new Packet(eth, fmatch),
                                    None, fmatch)
        ctx.initialize(connTrackTx,
                       new FlowStateTransaction[NatKey, NatBinding](null),
                       HappyGoLuckyLeaser)
        ctx.inputPort = portId
        ctx
    }

    feature("Connections are tracked") {
        scenario("Non-ip packets are considered forward flows") {
            val ctx = context(Left(1), { eth src MAC.random() dst MAC.random() })
            ctx.isForwardFlow should be (true)
            connTrackTx.size() should be (0)
            ctx should be (taggedWith ())
        }

        scenario("Generated packets are treated as return flows") {
            val ctx = context(Right(UUID.randomUUID()))
            ctx.isForwardFlow should be (false)
            connTrackTx.size() should be (0)
            ctx should be (taggedWith ())
        }

        scenario("Forward flows are tracked") {
            val ctx = context(Left(1))

            val ingressKey = ConnTrackKey(ctx.wcmatch, ingressDevice)
            val egressKey = ConnTrackState.EgressConnTrackKey(ctx.wcmatch, egressDevice)

            ctx.isForwardFlow should be (true)
            ctx should be (taggedWith (ingressKey))

            ctx.trackConnection(egressDevice)
            ctx should be (taggedWith (ingressKey, egressKey))

            connTrackTx.size() should be (2)
            val values = transactionValues(connTrackTx)

            values should contain theSameElementsAs Map(ingressKey -> true,
                                                        egressKey -> false)
            connTrackStateTable.getRefCount(ingressKey) should be (0)
            connTrackTx.commit()
            connTrackStateTable.getRefCount(ingressKey) should be (1)
            connTrackStateTable.getRefCount(egressKey) should be (1)
        }

        scenario("Forward flows are recognized") {
            val ctx = context(Left(1))

            val ingressKey = ConnTrackKey(ctx.wcmatch, ingressDevice)
            val egressKey = ConnTrackState.EgressConnTrackKey(ctx.wcmatch, egressDevice)

            connTrackStateTable.putAndRef(ingressKey, true)
            connTrackStateTable.putAndRef(egressKey, false)

            ctx.isForwardFlow should be (true)
            ctx should be (taggedWith (ingressKey))

            ctx.trackConnection(egressDevice)
            ctx should be (taggedWith (ingressKey, egressKey))

            connTrackTx.size() should be (2)
            val values = transactionValues(connTrackTx)

            values should contain theSameElementsAs Map(ingressKey -> true,
                                                        egressKey -> false)
            connTrackStateTable.getRefCount(ingressKey) should be (1)
            connTrackTx.commit()
            connTrackStateTable.getRefCount(ingressKey) should be (2)
            connTrackStateTable.getRefCount(egressKey) should be (2)
        }

        scenario("Return flows are recognized") {
            val ctx = context(Left(1))

            val ingressKey = ConnTrackKey(ctx.wcmatch, ingressDevice)

            connTrackStateTable.putAndRef(ingressKey, false)

            ctx.isForwardFlow should be (false)
            ctx should be (taggedWith (ingressKey))
            ctx.trackConnection(egressDevice)
            ctx should be (taggedWith (ingressKey))

            connTrackTx.size() should be (0)
        }
    }

    def transactionValues[K, V](tx: FlowStateTransaction[K, V]): HashMap[K, V] =
       tx.fold(new HashMap[K, V](),
               new Reducer[K, V, HashMap[K, V]] {
                    override def apply(acc: HashMap[K, V], key: K, value: V) =
                        acc + (key -> value)
                })
}
