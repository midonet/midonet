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
package org.midonet.midolman

import java.util.UUID

import org.junit.runner.RunWith
import org.midonet.midolman.PacketWorkflow.Drop
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.rules.{RuleResult, Condition}
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.{MAC, IPacket}
import org.midonet.sdn.state.{FlowStateTransaction, ShardedFlowStateTable}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.simulation.Coordinator.ToPortAction

@RunWith(classOf[JUnitRunner])
class ConntrackTest extends MidolmanSpec {
    val leftMac = "02:02:01:10:10:aa"
    val rightMac = "02:02:01:10:10:bb"
    val leftIp = "192.168.1.1"
    val rightIp = "192.168.1.10"

    var leftPort: UUID = null
    var rightPort: UUID = null

    var clusterBridge: UUID = null

    private def buildTopology() {
        val host = newHost("myself", hostId)
        host should not be null

        clusterBridge = newBridge("bridge")
        clusterBridge should not be null

        leftPort = newBridgePort(clusterBridge)
        stateStorage.setPortLocalAndActive(leftPort, host, true)
        rightPort = newBridgePort(clusterBridge)
        stateStorage.setPortLocalAndActive(rightPort, host, true)

        val brChain = newInboundChainOnBridge("brChain", clusterBridge)

        val fwdCond = new Condition()
        fwdCond.matchForwardFlow = true
        fwdCond.inPortIds = new java.util.HashSet[UUID]()
        fwdCond.inPortIds.add(leftPort)

        val retCond = new Condition()
        retCond.matchReturnFlow = true
        retCond.inPortIds = new java.util.HashSet[UUID]()
        retCond.inPortIds.add(rightPort)

        newLiteralRuleOnChain(brChain, 1, fwdCond, RuleResult.Action.ACCEPT)
        newLiteralRuleOnChain(brChain, 2, retCond, RuleResult.Action.ACCEPT)
        newLiteralRuleOnChain(brChain, 3, new Condition(), RuleResult.Action.DROP)

        fetchTopology(brChain)
        fetchPorts(leftPort, rightPort)

        val bridge: Bridge = fetchDevice[Bridge](clusterBridge)
        val macTable = bridge.vlanMacTableMap(0.toShort)
        macTable.add(MAC.fromString(leftMac), leftPort)
        macTable.add(MAC.fromString(rightMac), rightPort)
    }

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor()
                                                  with MessageAccumulator))

    override def beforeTest() {
        buildTopology()
    }

    def conntrackedPacketPairs = {
        import org.midonet.packets.util.PacketBuilder
        import org.midonet.packets.util.PacketBuilder._

        def baseFwdPacket = { eth addr leftMac -> rightMac } <<
                            { ip4 addr leftIp --> rightIp }
        def baseRetPacket = { eth addr rightMac -> leftMac } <<
                            { ip4 addr rightIp --> leftIp }

        val forwardPayloads = List[PacketBuilder[_ <: IPacket]](
           /* { udp ports 5003 ---> 53 } << payload("payload"),
            { tcp ports 8008 ---> 80 } << payload("payload"), */
            { icmp.echo.request id 203 seq 25 data "data" })
        val returnPayloads = List[PacketBuilder[_ <: IPacket]](
          /*  { udp ports 53 ---> 5003 },
            { tcp ports 80 ---> 8008 }, */
            { icmp.echo.reply id 203 seq 25 data "data" })

        forwardPayloads map { baseFwdPacket << _ } zip
            (returnPayloads map { baseRetPacket << _  })
    }

    feature("TCP, UDP and ICMP flows are conntracked") {
        scenario("return packets are detected as such") {
            val bridge: Bridge = fetchDevice[Bridge](clusterBridge)
            val conntrackTable = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue]()
                                            .addShard()
            implicit val conntrackTx = new FlowStateTransaction(conntrackTable)

            for ((fwdPkt, retPkt) <- conntrackedPacketPairs) {
                val (pktCtx, fwdAct) = simulateDevice(bridge, fwdPkt, leftPort)
                pktCtx.trackConnection(bridge.id)
                conntrackTx.size() should be (1)
                conntrackTx.commit()
                conntrackTx.flush()
                pktCtx.isForwardFlow should be (true)
                fwdAct should be (ToPortAction(rightPort))

                val (retContext, retAct) = simulateDevice(bridge, retPkt, rightPort)
                conntrackTx.size() should be (0)
                retContext.isForwardFlow should be (false)
                retAct should be (ToPortAction(leftPort))
            }
        }

        scenario("return packets are not detected if a conntrack key is not installed") {
            val bridge: Bridge = fetchDevice[Bridge](clusterBridge)
            val conntrackTable = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue]()
                    .addShard()
            implicit val conntrackTx = new FlowStateTransaction(conntrackTable)

            for ((fwdPkt, retPkt) <- conntrackedPacketPairs) {
                val (fwdContext, fwdAct) = simulateDevice(bridge, fwdPkt, leftPort)
                fwdContext.isForwardFlow should be (true)
                fwdAct should be (ToPortAction(rightPort))

                val (retContext, retAct) = simulateDevice(bridge, retPkt, rightPort)
                retContext.isForwardFlow should be (true)
                retAct should be (Drop)
            }
        }
    }
}
