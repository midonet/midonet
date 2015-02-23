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

import java.nio.ByteBuffer
import java.util.UUID

import scala.collection.immutable.HashMap

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.{ShardedFlowStateTable, FlowStateTransaction}
import org.midonet.util.collection.Reducer

@RunWith(classOf[JUnitRunner])
class NatStateTest extends MidolmanSpec {

    val deviceId = UUID.randomUUID()
    val ipTarget = IPv4Addr.random
    val portTarget = 42
    val targets = Array(new NatTarget(ipTarget, ipTarget, portTarget, portTarget))

    val tcpPacket: Ethernet =
        { eth src MAC.random() dst MAC.random() } <<
        { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
        { tcp src 10 dst 88 }

    val natStateTable = new ShardedFlowStateTable[NatKey, NatBinding]().addShard()
    val natTx = new FlowStateTransaction(natStateTable)

    def context(eth: Ethernet = tcpPacket) = {
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(eth))
        val ctx = new PacketContext(1, new Packet(eth, fmatch), fmatch)
        ctx.initialize(new FlowStateTransaction[ConnTrackKey, ConnTrackValue](null),
                       natTx,
                       HappyGoLuckyLeaser,
                       new FlowStateTransaction[TraceKey, TraceContext](null))
        ctx
    }

    feature("DNAT state is correctly managed") {
        scenario("New bindings are created") {
            val ctx = context()
            val forwardKey = NatKey(ctx.wcmatch, deviceId, NatState.FWD_DNAT)
            val binding = NatBinding(ipTarget, portTarget)
            val returnKey = forwardKey.returnKey(binding)
            val returnBinding = forwardKey.returnBinding

            ctx.applyDnat(deviceId, targets) should be (true)
            ctx should be (taggedWith (forwardKey, returnKey))

            natTx.size() should be (2)
            val values = transactionValues(natTx)

            values should contain theSameElementsAs Map(forwardKey -> binding,
                                                        returnKey -> returnBinding)
            natStateTable.getRefCount(forwardKey) should be (0)
            natTx.commit()
            natStateTable.getRefCount(forwardKey) should be (1)
            natStateTable.getRefCount(returnKey) should be (1)

            ctx.wcmatch.getNetworkDstIP should be (binding.networkAddress)
            ctx.wcmatch.getDstPort should be (binding.transportPort)
        }

        scenario("Existing bindings are recognized") {
            val ctx = context()
            val forwardKey = NatKey(ctx.wcmatch, deviceId, NatState.FWD_DNAT)
            val binding = NatBinding(ipTarget, portTarget)
            val returnKey = forwardKey.returnKey(binding)
            val returnBinding = forwardKey.returnBinding

            natStateTable.putAndRef(forwardKey, binding)
            natStateTable.putAndRef(returnKey, returnBinding)

            ctx.applyDnat(deviceId, targets) should be (true)
            ctx should be (taggedWith (forwardKey, returnKey))

            natTx.size() should be (2)
            val values = transactionValues(natTx)

            values should contain theSameElementsAs Map(forwardKey -> binding,
                                                        returnKey -> returnBinding)
            natStateTable.getRefCount(forwardKey) should be (1)
            natTx.commit()
            natStateTable.getRefCount(forwardKey) should be (2)
            natStateTable.getRefCount(returnKey) should be (2)

            ctx.wcmatch.getNetworkDstIP should be (binding.networkAddress)
            ctx.wcmatch.getDstPort should be (binding.transportPort)
        }

        scenario("DNAT is reversed when there are bindings") {
            val ctx = context()
            val key = NatKey(ctx.wcmatch, deviceId, NatState.REV_DNAT)
            val binding = NatBinding(ipTarget, portTarget)

            natStateTable.putAndRef(key, binding)

            ctx.reverseDnat(deviceId) should be (true)
            ctx should be (taggedWith (key))

            natTx.size() should be (0)

            ctx.wcmatch.getNetworkSrcIP should be (binding.networkAddress)
            ctx.wcmatch.getSrcPort should be (binding.transportPort)
        }

        scenario("Flow is tagged even when DNAT is not reversed") {
            val ctx = context()
            val key = NatKey(ctx.wcmatch, deviceId, NatState.REV_DNAT)
            val binding = NatBinding(ipTarget, portTarget)

            ctx.reverseDnat(deviceId) should be (false)
            ctx should be (taggedWith (key))

            natTx.size() should be (0)

            ctx.wcmatch.getNetworkSrcIP should not be binding.networkAddress
            ctx.wcmatch.getSrcPort should not be binding.transportPort
        }
    }

    feature("SNAT state is correctly managed") {
        scenario("New bindings are created") {
            val ctx = context()
            val forwardKey = NatKey(ctx.wcmatch, deviceId, NatState.FWD_SNAT)
            val binding = NatBinding(ipTarget, portTarget)
            val returnKey = forwardKey.returnKey(binding)
            val returnBinding = forwardKey.returnBinding

            ctx.applySnat(deviceId, targets) should be (true)
            ctx should be (taggedWith (forwardKey, returnKey))

            natTx.size() should be (2)
            val values = transactionValues(natTx)

            values should contain theSameElementsAs Map(forwardKey -> binding,
                                                        returnKey -> returnBinding)
            natStateTable.getRefCount(forwardKey) should be (0)
            natTx.commit()
            natStateTable.getRefCount(forwardKey) should be (1)
            natStateTable.getRefCount(returnKey) should be (1)

            ctx.wcmatch.getNetworkSrcIP should be (binding.networkAddress)
            ctx.wcmatch.getSrcPort should be (binding.transportPort)
        }

        scenario("Existing bindings are recognized") {
            val ctx = context()
            val forwardKey = NatKey(ctx.wcmatch, deviceId, NatState.FWD_SNAT)
            val binding = NatBinding(ipTarget, portTarget)
            val returnKey = forwardKey.returnKey(binding)
            val returnBinding = forwardKey.returnBinding

            natStateTable.putAndRef(forwardKey, binding)
            natStateTable.putAndRef(returnKey, returnBinding)

            ctx.applySnat(deviceId, targets) should be (true)
            ctx should be (taggedWith (forwardKey, returnKey))

            natTx.size() should be (2)
            val values = transactionValues(natTx)

            values should contain theSameElementsAs Map(forwardKey -> binding,
                                                        returnKey -> returnBinding)
            natStateTable.getRefCount(forwardKey) should be (1)
            natTx.commit()
            natStateTable.getRefCount(forwardKey) should be (2)
            natStateTable.getRefCount(returnKey) should be (2)

            ctx.wcmatch.getNetworkSrcIP should be (binding.networkAddress)
            ctx.wcmatch.getSrcPort should be (binding.transportPort)
        }

        scenario("SNAT is reversed when there are bindings") {
            val ctx = context()
            val key = NatKey(ctx.wcmatch, deviceId, NatState.REV_SNAT)
            val binding = NatBinding(ipTarget, portTarget)

            natStateTable.putAndRef(key, binding)

            ctx.reverseSnat(deviceId) should be (true)
            ctx should be (taggedWith (key))

            natTx.size() should be (0)

            ctx.wcmatch.getNetworkDstIP should be (binding.networkAddress)
            ctx.wcmatch.getDstPort should be (binding.transportPort)
        }

        scenario("Flow is tagged even when SNAT is not reversed") {
            val ctx = context()
            val key = NatKey(ctx.wcmatch, deviceId, NatState.REV_SNAT)
            val binding = NatBinding(ipTarget, portTarget)

            ctx.reverseSnat(deviceId) should be (false)
            ctx should be (taggedWith (key))

            natTx.size() should be (0)

            ctx.wcmatch.getNetworkDstIP should not be binding.networkAddress
            ctx.wcmatch.getDstPort should not be binding.transportPort
        }
    }

    feature("Pings are NATed") {
        val ping: Ethernet =
            { eth src MAC.random() dst MAC.random() } <<
            { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
            { icmp.echo id 10 }

        scenario("New bindings are created") {
            val ctx = context(ping)
            val forwardKey = NatKey(ctx.wcmatch, deviceId, NatState.FWD_SNAT)
            val binding = NatBinding(ipTarget, 10 /* ICMP id */)
            val returnKey = forwardKey.returnKey(binding)
            val returnBinding = forwardKey.returnBinding

            val oldSrcPort = ctx.wcmatch.getSrcPort
            val oldDstPort = ctx.wcmatch.getDstPort

            ctx.applySnat(deviceId, targets) should be (true)
            ctx should be (taggedWith (forwardKey, returnKey))

            natTx.size() should be (2)
            val values = transactionValues(natTx)

            values should contain theSameElementsAs Map(forwardKey -> binding,
                                                        returnKey -> returnBinding)
            natStateTable.getRefCount(forwardKey) should be (0)
            natTx.commit()
            natStateTable.getRefCount(forwardKey) should be (1)
            natStateTable.getRefCount(returnKey) should be (1)

            ctx.wcmatch.getNetworkSrcIP should be (binding.networkAddress)
            ctx.wcmatch.getSrcPort should be (oldSrcPort)
            ctx.wcmatch.getDstPort should be (oldDstPort)
        }

        scenario("Existing bindings are recognized") {
            val ctx = context(ping)
            val forwardKey = NatKey(ctx.wcmatch, deviceId, NatState.FWD_SNAT)
            val binding = NatBinding(ipTarget, 10 /* ICMP id */)
            val returnKey = forwardKey.returnKey(binding)
            val returnBinding = forwardKey.returnBinding

            natStateTable.putAndRef(forwardKey, binding)
            natStateTable.putAndRef(returnKey, returnBinding)

            val oldSrcPort = ctx.wcmatch.getSrcPort
            val oldDstPort = ctx.wcmatch.getDstPort

            ctx.applySnat(deviceId, targets) should be (true)
            ctx should be (taggedWith (forwardKey, returnKey))

            natTx.size() should be (2)
            val values = transactionValues(natTx)

            values should contain theSameElementsAs Map(forwardKey -> binding,
                                                        returnKey -> returnBinding)
            natStateTable.getRefCount(forwardKey) should be (1)
            natTx.commit()
            natStateTable.getRefCount(forwardKey) should be (2)
            natStateTable.getRefCount(returnKey) should be (2)

            ctx.wcmatch.getNetworkSrcIP should be (binding.networkAddress)
            ctx.wcmatch.getSrcPort should be (oldSrcPort)
            ctx.wcmatch.getDstPort should be (oldDstPort)
        }

        scenario("SNAT is reversed when there are bindings") {
            val ctx = context(ping)
            val key = NatKey(ctx.wcmatch, deviceId, NatState.REV_SNAT)
            val binding = NatBinding(ipTarget, 10 /* ICMP id */)

            natStateTable.putAndRef(key, binding)

            val oldSrcPort = ctx.wcmatch.getSrcPort
            val oldDstPort = ctx.wcmatch.getDstPort

            ctx.reverseSnat(deviceId) should be (true)
            ctx should be (taggedWith (key))

            natTx.size() should be (0)

            ctx.wcmatch.getNetworkDstIP should be (binding.networkAddress)
            ctx.wcmatch.getSrcPort should be (oldSrcPort)
            ctx.wcmatch.getDstPort should be (oldDstPort)
        }

        scenario("Flow is tagged even when SNAT is not reversed") {
            val ctx = context()
            val key = NatKey(ctx.wcmatch, deviceId, NatState.REV_SNAT)
            val binding = NatBinding(ipTarget, 10 /* ICMP id */)

            val oldSrcPort = ctx.wcmatch.getSrcPort
            val oldDstPort = ctx.wcmatch.getDstPort

            ctx.reverseSnat(deviceId) should be (false)
            ctx should be (taggedWith (key))

            natTx.size() should be (0)

            ctx.wcmatch.getNetworkDstIP should not be binding.networkAddress
            ctx.wcmatch.getSrcPort should be (oldSrcPort)
            ctx.wcmatch.getDstPort should be (oldDstPort)
        }
    }

    feature("ICMP errors are NATed") {
        val underlying: IPv4 =
            { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
            { tcp src 10 dst 88 }
        val error: Ethernet =
            { eth src MAC.random() dst MAC.random() } <<
            { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
            { icmp.unreach culprit underlying port}

        scenario("New bindings are created") {
            val ctx = context(error)
            val forwardKey = NatKey(ctx.wcmatch, deviceId, NatState.FWD_SNAT)
            val binding = NatBinding(ipTarget, forwardKey.transportDst)
            val returnKey = forwardKey.returnKey(binding)
            val returnBinding = forwardKey.returnBinding

            val oldSrcPort = ctx.wcmatch.getSrcPort
            val oldDstPort = ctx.wcmatch.getDstPort

            ctx.applySnat(deviceId, targets) should be (true)
            ctx should be (taggedWith (forwardKey, returnKey))

            natTx.size() should be (2)
            val values = transactionValues(natTx)

            values should contain theSameElementsAs Map(forwardKey -> binding,
                                                        returnKey -> returnBinding)
            natStateTable.getRefCount(forwardKey) should be (0)
            natTx.commit()
            natStateTable.getRefCount(forwardKey) should be (1)
            natStateTable.getRefCount(returnKey) should be (1)

            ctx.wcmatch.getNetworkSrcIP should be (binding.networkAddress)
            ctx.wcmatch.getSrcPort should be (oldSrcPort)
            ctx.wcmatch.getDstPort should be (oldDstPort)
        }

        scenario("Existing bindings are recognized") {
            val ctx = context(error)
            val forwardKey = NatKey(ctx.wcmatch, deviceId, NatState.FWD_SNAT)
            val binding = NatBinding(ipTarget, forwardKey.transportDst)
            val returnKey = forwardKey.returnKey(binding)
            val returnBinding = forwardKey.returnBinding

            natStateTable.putAndRef(forwardKey, binding)
            natStateTable.putAndRef(returnKey, returnBinding)

            val oldSrcPort = ctx.wcmatch.getSrcPort
            val oldDstPort = ctx.wcmatch.getDstPort

            ctx.applySnat(deviceId, targets) should be (true)
            ctx should be (taggedWith (forwardKey, returnKey))

            natTx.size() should be (2)
            val values = transactionValues(natTx)

            values should contain theSameElementsAs Map(forwardKey -> binding,
                                                        returnKey -> returnBinding)
            natStateTable.getRefCount(forwardKey) should be (1)
            natTx.commit()
            natStateTable.getRefCount(forwardKey) should be (2)
            natStateTable.getRefCount(returnKey) should be (2)

            ctx.wcmatch.getNetworkSrcIP should be (binding.networkAddress)
            ctx.wcmatch.getSrcPort should be (oldSrcPort)
            ctx.wcmatch.getDstPort should be (oldDstPort)
        }

        scenario("The inner packet's SNAT is reversed when there are bindings") {
            val ctx = context(error)
            val key = NatKey(ctx.wcmatch, deviceId, NatState.REV_SNAT)
            val binding = NatBinding(ipTarget, portTarget)

            natStateTable.putAndRef(key, binding)

            val oldSrcPort = ctx.wcmatch.getSrcPort
            val oldDstPort = ctx.wcmatch.getDstPort

            ctx.reverseSnat(deviceId) should be (true)
            ctx should be (taggedWith (key))

            natTx.size() should be (0)

            ctx.wcmatch.getNetworkDstIP should be (binding.networkAddress)
            ctx.wcmatch.getSrcPort should be (oldSrcPort)
            ctx.wcmatch.getDstPort should be (oldDstPort)

            val data = ctx.wcmatch.getIcmpData
            val bb = ByteBuffer.wrap(data)
            val header = new IPv4
            header.deserializeHeader(bb)
            header.getSourceIPAddress should be (binding.networkAddress)
            TCP.getSourcePort(bb.slice).toShort should be (binding.transportPort)
        }

        scenario("Flow is tagged even when SNAT is not reversed") {
            val ctx = context()
            val key = NatKey(ctx.wcmatch, deviceId, NatState.REV_SNAT)
            val binding = NatBinding(ipTarget, 10 /* ICMP id */)

            val oldSrcPort = ctx.wcmatch.getSrcPort
            val oldDstPort = ctx.wcmatch.getDstPort

            ctx.reverseSnat(deviceId) should be (false)
            ctx should be (taggedWith (key))

            natTx.size() should be (0)

            ctx.wcmatch.getNetworkDstIP should not be binding.networkAddress
            ctx.wcmatch.getSrcPort should be (oldSrcPort)
            ctx.wcmatch.getDstPort should be (oldDstPort)
        }
    }

    feature("Other types of packets are not NATed") {
        val ctx = context({ eth src MAC.random() dst MAC.random() })
        ctx.applyDnat(deviceId, targets) should be (false)
        ctx should be (taggedWith ())
        natTx.size() should be (0)
    }

    def transactionValues[K, V](tx: FlowStateTransaction[K, V]): HashMap[K, V] =
       tx.fold(new HashMap[K, V](),
               new Reducer[K, V, HashMap[K, V]] {
                    override def apply(acc: HashMap[K, V], key: K, value: V) =
                        acc + (key -> value)
                    })
}
