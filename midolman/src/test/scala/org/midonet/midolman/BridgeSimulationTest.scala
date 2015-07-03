/*
 * Copyright 2014-2015 Midokura SARL
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

import java.lang.{Short => JShort}
import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.immutable

import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.{Bridge => ClusterBridge, TunnelZone}
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.PacketWorkflow.{Drop, SimulationResult}
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.simulation.Bridge
import org.midonet.midolman.simulation.Coordinator.{FloodBridgeAction, ToPortAction}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows.FlowActions.output
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._


@RunWith(classOf[JUnitRunner])
class BridgeSimulationTest extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    private var port1OnHost1: BridgePort = _
    private var port2OnHost1: BridgePort = _
    private var port3OnHost1: BridgePort = _
    private var portOnHost2: BridgePort = _
    private var portOnHost3: BridgePort = _

    private var bridge: ClusterBridge = _
    private var bridgeDevice: Bridge = _


    val host1Ip = IPv4Addr("192.168.100.1")
    val host2Ip = IPv4Addr("192.168.125.1")
    val host3Ip = IPv4Addr("192.168.150.1")

    val port2OnHost1Mac = "0a:fe:88:70:33:ab"

    override def beforeTest(): Unit ={
        val tunnelZone = greTunnelZone("default")

        val host1 = newHost("host1", hostId)
        val host2 = newHost("host2")
        val host3 = newHost("host3")

        bridge = newBridge("bridge")

        port1OnHost1 = newBridgePort(bridge)
        port2OnHost1 = newBridgePort(bridge)
        port3OnHost1 = newBridgePort(bridge)
        portOnHost2 = newBridgePort(bridge)
        portOnHost3 = newBridgePort(bridge)

        materializePort(port1OnHost1, host1, "port1")
        materializePort(portOnHost2, host2, "port2")
        materializePort(portOnHost3, host3, "port3")
        materializePort(port2OnHost1, host1, "port4")
        materializePort(port3OnHost1, host1, "port5")

        List(host1, host2, host3).zip(List(host1Ip, host2Ip, host3Ip)).foreach{
            case (host, ip) =>
                clusterDataClient.tunnelZonesAddMembership(tunnelZone.getId,
                    new TunnelZone.HostConfig(host).setIp(ip))
        }

        fetchTopology(bridge, port1OnHost1, portOnHost2, portOnHost3,
                      port2OnHost1, port3OnHost1)

        bridgeDevice = fetchDevice(bridge)
    }

    /**
      * All frames generated from this test will get the vlan tags set in this
      * list. The bridge (in this case it's a VUB) should simply let them pass
      * and apply mac-learning based on the default vlan id (0).
      *
      * Here the list is simply empty, but just extending this test and
      * overriding the method with a different list you get all the test cases
      * but with vlan-tagged traffic.
      *
      * At the bottom there are a couple of classes that add vlan ids to the
      * same test cases.
      *
      * See MN-200
      *
      */
    def networkVlans: List[JShort] = List()

    scenario("Malformed L3 packet") {
        val malformed = eth mac "02:11:22:33:44:10" -> "02:11:22:33:44:20"
        malformed << payload("00:00")
        malformed ether_type IPv4.ETHERTYPE vlans networkVlans

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              malformed, port1OnHost1.getId)
        verifyFloodAction(action)
    }

    scenario("bridge simulation for normal packet") {
        val srcMac = "02:11:22:33:44:10"
        val ethPkt = { eth src srcMac dst "02:11:22:33:44:11" } <<
            { ip4 src "10.0.1.10" dst "10.0.1.11" } <<
            { udp src 10 dst 11 } << payload("My UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1.getId)
        verifyFloodAction(action)

        verifyMacLearned(srcMac, port1OnHost1.getId)
    }

    scenario("inbound chains not applied to vlan traffic") {
        // setup chains that drop all UDP traffic both at pre and post
        val udpCond = new Condition()
        udpCond.nwProto = Byte.box(UDP.PROTOCOL_NUMBER)

        val preChain = newInboundChainOnBridge("brFilter-in", bridge)
        newLiteralRuleOnChain(preChain, 1,udpCond, RuleResult.Action.DROP)

        // refetch device to pick up chain
        bridgeDevice = fetchDevice(bridge)
        checkTrafficWithDropChains()
    }

    scenario("outbound chains not applied to vlan traffic") {
        // setup chains that drop all UDP traffic both at pre and post
        val udpCond = new Condition()
        udpCond.nwProto = Byte.box(UDP.PROTOCOL_NUMBER)

        val postChain = newOutboundChainOnBridge("brFilter-out", bridge)
        newLiteralRuleOnChain(postChain, 1, udpCond, RuleResult.Action.DROP)

        // refetch device to pick up chain
        bridgeDevice = fetchDevice(bridge)
        checkTrafficWithDropChains()
    }

    /**
     * Use after setting chains that drop UDP traffic on a bridge. The method
     * will send traffic through the bridge and expect it dropped (by matching
     * on the chains) if it is vlan-tagged, but not dropped otherwise.
     */
    private def checkTrafficWithDropChains() {

        val ethPkt = { eth src "0a:fe:88:70:44:55" dst "ff:ff:ff:ff:ff:ff" } <<
            { ip4 src "10.10.10.10" dst "10.11.11.11" } <<
            { udp src 10 dst 12 } << payload("Test UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        log.info("Testing traffic with vlans: {}", networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1.getId)

        if (networkVlans.isEmpty) {
            action should be (Drop)
        } else {
            verifyFloodAction(action)
        }
    }

    scenario("ethernet broadcast on bridge") {
        val srcMac = "0a:fe:88:70:44:55"
        val ethPkt = { eth src srcMac dst "ff:ff:ff:ff:ff:ff" } <<
            { ip4 src "10.10.10.10" dst "10.11.11.11" } <<
            { udp src 10 dst 12 } << payload("Test UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1.getId)
        verifyFloodAction(action)

        //Our source MAC should also be learned
        verifyMacLearned(srcMac, port1OnHost1.getId)
    }

    scenario("broadcast arp on bridge") {
        val srcMac = "0a:fe:88:90:22:33"
        val ethPkt = { eth src srcMac dst eth_bcast } <<
            { arp.req.mac(srcMac -> eth_bcast)
                 .ip("10.10.10.11" --> "10.11.11.10") }
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1.getId)
        verifyFloodAction(action)

        //Our source MAC should also be learned
        verifyMacLearned(srcMac, port1OnHost1.getId)
    }

    scenario("multicast destination ethernet on bridge") {
        val srcMac = "0a:fe:88:90:22:33"
        val ethPkt = { eth src srcMac dst "01:00:cc:cc:dd:dd" } <<
            { ip4 src "10.10.10.11" dst "10.11.11.10" } <<
            { udp src 10 dst 12 } << payload("Test UDP Packet")
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1.getId)
        verifyFloodAction(action)

        //Our source MAC should also be learned
        verifyMacLearned(srcMac, port1OnHost1.getId)
    }

    scenario("multicast src ethernet on bridge") {
        val ethPkt = { eth src "ff:54:ce:50:44:ce" dst "0a:de:57:16:a3:06" } <<
            { ip4 src "10.10.10.12" dst "10.11.11.12" } <<
            { udp src 10 dst 12 } << payload("Test UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1.getId)
        action should be (Drop)
    }

    scenario("mac migration on bridge") {
        val srcMac = "02:13:66:77:88:99"
        var ethPkt = { eth src srcMac dst "02:11:22:33:44:55" } <<
            { ip4 src "10.0.1.10" dst "10.0.1.11" } <<
            { udp src 10 dst 11 } << payload("My UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val bridgeDevice: Bridge = fetchDevice(bridge)
        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port1OnHost1.getId)
        verifyFloodAction(action)

        verifyMacLearned(srcMac, port1OnHost1.getId)

        /*
         * MAC moved from port1OnHost1 to port3OnHost1
         * frame is going toward port2OnHost1 (the learned MAC from
         * verifyMacLearned)
         */
        ethPkt = { eth src srcMac dst port2OnHost1Mac } <<
            { ip4 src "10.0.1.10" dst "10.0.1.11" } <<
            { udp src 10 dst 11 } << payload("My UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val (pktCtx2, action2) = simulateDevice(bridgeDevice,
                                                ethPkt, port3OnHost1.getId)
        action2 match {
            case ToPortAction(outPortUUID) =>
                outPortUUID should equal (port2OnHost1.getId)
            case _ => fail("Not ToPortAction, instead: " + action.toString)
        }

        verifyMacLearned(srcMac, port3OnHost1.getId)
    }

    /*
     * In this test, always assume input port is port2OnHost1 (keep src mac
     * consistent), dst mac is variable
     */
    private def verifyMacLearned(learnedMac: String, expectedPort: UUID) : Unit = {
        val ethPkt = { eth src port2OnHost1Mac dst learnedMac } <<
            { ip4 src "10.10.10.10" dst "10.11.11.11" } <<
            { udp src 10 dst 12 } << payload("Test UDP packet")
        ethPkt.setVlanIDs(networkVlans)

        val bridgeDevice: Bridge = fetchDevice(bridge)
        val (pktCtx, action) = simulateDevice(bridgeDevice,
                                              ethPkt, port2OnHost1.getId)
        action match {
            case ToPortAction(outPortUUID) =>
                outPortUUID should equal (expectedPort)
            case _ => fail("Not ToPortAction, instead: " + action.toString)
        }
    }

    private def verifyFloodAction(action: SimulationResult) : Unit =
        action match {
            case FloodBridgeAction(brId, ports) =>
                assert(brId === bridge.getId)
                assert(ports.contains(port1OnHost1.getId))
                assert(ports.contains(port2OnHost1.getId))
                assert(ports.contains(port3OnHost1.getId))
                assert(ports.contains(portOnHost2.getId))
                assert(ports.contains(portOnHost3.getId))
            case _ => fail("Not FloodBridgeAction, instead: " +
                               action.toString)
        }
}

/**
  * The same tests as the parent
  * [[org.midonet.midolman.BridgeSimulationTestCase]], but transmitting frames
  * that have one vlan id.
  *
  * The tests are expected to work in exactly the same way, since here we're
  * adding a Vlan Unaware Bridge (all its interior ports are not vlan-tagged)
  * and therefore it should behave with vlan traffic in the same way as if it
  * was not tagged.
  */
class BridgeSimulationTestWithOneVlan extends BridgeSimulationTest {
    override def networkVlans: List[JShort] = List(2.toShort)
}

/**
  * The same tests [[org.midonet.midolman.BridgeSimulationTestCase]], but
  * transmitting frames that have one vlan id.
  */
class BridgeSimulationTestWithManyVlans extends BridgeSimulationTest {
    override def networkVlans: List[JShort] = List(3,4,5,6) map {
        x => short2Short(x.toShort)
    }
}
