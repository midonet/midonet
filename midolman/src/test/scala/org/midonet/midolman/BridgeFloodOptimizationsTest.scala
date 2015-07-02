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

import java.util.{LinkedList, UUID}

import com.typesafe.config.{ConfigFactory, Config}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, NoOp}
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{ARP, Packets, IPv4Addr, MAC}
import org.midonet.sdn.flows.VirtualActions.{FlowActionOutputToVrnPort, FlowActionOutputToVrnBridge}

@RunWith(classOf[JUnitRunner])
class BridgeFloodOptimizationsTest extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var bridge: UUID = _
    var port1, port2, port3: UUID = _
    val mac1 = MAC.fromString("02:11:11:11:11:09")
    val ip1 = IPv4Addr.fromString("10.0.1.1")
    val mac2 = MAC.fromString("0a:fe:88:90:22:33")
    val ip2 = IPv4Addr.fromString("10.10.10.11")
    val mac3 = MAC.fromString("0a:fe:88:90:ee:ee")
    val ip3 = IPv4Addr.fromString("10.10.10.13")

    override protected def fillConfig(config: Config) = {
        super.fillConfig(ConfigFactory.parseString(
            "agent.midolman.enable_bridge_arp = true").withFallback(config))
    }

    override def beforeTest(): Unit = {
        bridge = newBridge("bridge")
        port1 = newBridgePort(bridge)
        port2 = newBridgePort(bridge)
        port3 = newBridgePort(bridge)

        materializePort(port1, hostId, "port1")
        materializePort(port2, hostId, "port2")
        materializePort(port3, hostId, "port3")

        // Seed the bridge with mac, ip, vport for port1.
        feedBridgeIp4Mac(bridge, ip1, mac1)

        val simBridge = fetchDevice[Bridge](bridge)
        fetchPorts(port1, port2, port3)

        feedMacTable(simBridge, mac1, port1)
    }

    feature("The bridge is not flooded") {
        scenario ("The bridge generates an ARP reply") {

            val ethPkt = Packets.arpRequest(mac2, ip2, ip1)
            val generatedPackets = new LinkedList[GeneratedPacket]()

            val (simRes, _) = simulate(packetContextFor(ethPkt, port2,
                                                        generatedPackets))

            simRes should be (NoOp)
            generatedPackets should have size 1

            val GeneratedPacket(egressPort, genEth) = generatedPackets.poll()

            egressPort should be (port2)
            genEth should be (ARP.makeArpReply(mac1, mac2,
                                               IPv4Addr.intToBytes(ip1.addr),
                                               IPv4Addr.intToBytes(ip2.addr)))
        }

        scenario ("The bridge forwards a packet to a known MAC") {
            val ethPkt = Packets.udp(mac2, mac1, ip2, ip1, 10, 12, "Test".getBytes)
            val (simRes, pktCtx) = simulate(packetContextFor(ethPkt, port2))

            simRes should be (AddVirtualWildcardFlow)
            pktCtx.virtualFlowActions should have size 1
            pktCtx.virtualFlowActions.get(0) should be (FlowActionOutputToVrnPort(port1))
        }
    }

    feature ("The bridge is flooded") {
        scenario ("When a MAC hasn't been learned") {
            val ethPkt = Packets.udp(mac2, mac3, ip2, ip3, 10, 12, "Test".getBytes)
            val (simRes, pktCtx) = simulate(packetContextFor(ethPkt, port2))

            simRes should be (AddVirtualWildcardFlow)
            pktCtx.virtualFlowActions should have size 1

            val FlowActionOutputToVrnBridge(bridgeId, outputPorts) = pktCtx.virtualFlowActions.get(0)

            bridgeId should be (bridge)
            outputPorts should contain theSameElementsAs List(port1, port3)
        }
    }
}
