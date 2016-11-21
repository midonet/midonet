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
package org.midonet.midolman.monitoring

import java.util.{LinkedList, UUID}
import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.{HandlePackets, SimulationResult}
import org.midonet.midolman.simulation.{Bridge, PacketContext}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.odp.flows._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class FlowRecorderDataTest extends MidolmanSpec {

    feature("simulation devices") {
        scenario("expected tags, and only expected tag, show up") {
            val tunnelZone = greTunnelZone("tzone0")
            val host1 = hostId
            val host2 = newHost("host2")

            val bridge = newBridge("bridge0")
            val port1 = newBridgePort(bridge)
            val port1mac = MAC.random()
            val port2 = newBridgePort(bridge)
            val port2mac = MAC.random()

            val chain = newOutboundChainOnPort("chain1", port2)
            // cause flow state to be generated
            newTraceRuleOnChain(chain, 1, newCondition(), UUID.randomUUID)

            materializePort(port1, host1, "port1")
            materializePort(port2, host2, "port2")

            addTunnelZoneMember(tunnelZone, host1, IPv4Addr("10.25.25.1"))
            addTunnelZoneMember(tunnelZone, host2, IPv4Addr("10.25.25.2"))

            fetchPorts(port1, port2)
            fetchChains(chain)
            val bridgeDevice = fetchDevice[Bridge](bridge)
            feedMacTable(bridgeDevice, port1mac, port1)
            feedMacTable(bridgeDevice, port2mac, port2)

            val ethPkt = { eth src port1mac dst port2mac } <<
                { ip4 src "10.0.1.10" dst "10.0.1.11" } <<
            { udp src 10 dst 11 } << payload("My UDP packet")

            val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(ethPkt))
            fmatch.setInputPortNumber(1)
            val packet = new Packet(ethPkt, fmatch)

            val recorder = new RecordingFlowRecorder()
            val workflow = packetWorkflow(
                dpPortToVport = Map(1 -> port1, 2 -> port2),
                flowRecorder = recorder)
            workflow.handlePackets(packet)
            val pktCtx = recorder.next().ctx

            val tags = pktCtx.flowTags.asScala

            def matchDevice(t: FlowTagger.FlowTag, dev: UUID): Boolean = {
                t match {
                    case d: FlowTagger.DeviceTag => d.device == dev
                    case _ => false
                }
            }
            tags.count(matchDevice(_, port1)) shouldBe 1
            tags.count(matchDevice(_, port2)) shouldBe 1
            tags.count(matchDevice(_, bridge)) shouldBe 1
            tags.count(matchDevice(_, chain)) shouldBe 1
        }
    }

    class RecordingFlowRecorder extends FlowRecorder {
        case class Record(ctx: PacketContext, result: SimulationResult)
        val queue = new LinkedList[Record]()

        override def doStart(): Unit = notifyStarted()

        override def doStop(): Unit = notifyStopped()

        def record(pktContext: PacketContext, simRes: SimulationResult): Unit = {
            queue.add(Record(pktContext, simRes))
        }

        def next(): Record = {
            queue.remove()
        }
    }


}
