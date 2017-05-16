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
import org.midonet.util.collection.IPv4InvalidationArray
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.Drop
import org.midonet.midolman.layer3.Route._
import org.midonet.midolman.simulation.{Router => SimRouter, RouterPort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Subnet, Packets, IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class RouterFlowInvalidationTest extends MidolmanSpec {

    var router: UUID = null
    var outPort: UUID = null
    var inPort: UUID = null
    var simRouter: SimRouter = null

    val macInPort = "02:11:22:33:44:10"
    val macOutPort = "02:11:22:33:46:10"

    val ipInPort = "10.10.0.1"
    val ipOutPort = "11.11.0.10"
    val ipSource = "20.20.0.20"
    val macSource = "02:11:22:33:44:11"
    val networkToReach = "11.11.0.0"
    val networkToReachLength = 16

    override def beforeTest(): Unit = {
        router = newRouter("router")
        inPort = newRouterPort(router, MAC.fromString(macInPort),
                               ipInPort, ipInPort, 32)
        outPort = newRouterPort(router, MAC.fromString(macOutPort),
                                ipOutPort, networkToReach, networkToReachLength)

        materializePort(inPort, hostId, "inport")
        materializePort(outPort, hostId, "outport")

        fetchPorts(inPort, outPort)
        simRouter = fetchDevice[SimRouter](router)
    }

    scenario("Routes are referenced by flows") {
        val ipToReach = "11.11.0.2"
        val macToReach = "02:11:22:33:48:10"
        // add a route from ipSource to ipToReach/31, next hop is outPort
        newRoute(router, ipSource, 32, ipToReach, 31, NextHop.PORT,
                 outPort, new IPv4Addr(NO_GATEWAY).toString, 2)

        feedArpTable(simRouter, IPv4Addr.fromString(ipToReach),
                     MAC.fromString(macToReach))

        val portDevice = fetchDevice[RouterPort](inPort)
        val eth = createUdpPacket(macSource, ipSource,
                                  portDevice.portMac.toString, ipToReach)

        val (simRes, pktCtx) = simulate(packetContextFor(eth, inPort))
        simRes should not be Drop

        val dst = IPv4Addr.fromString(ipToReach).toInt
        IPv4InvalidationArray.current.countRefs(dst) should be (1)

        cbRegistry.runAndClear(pktCtx.flowRemovedCallbacks)
        IPv4InvalidationArray.current.countRefs(dst) should be (0)
    }

    scenario("Flows are invalidated when adding a route") {
        val ipVm1 = "11.11.1.2"
        val macVm1 = "11:22:33:44:55:02"
        val ipVm2 = "11.11.1.22"
        val macVm2 = "11:22:33:44:55:03"

        // this vm is on a different sub network
        val ipVm3 = "11.11.2.4"
        val macVm3 = "11:22:33:44:55:04"

        newRoute(router, ipSource, 32, networkToReach, networkToReachLength,
            NextHop.PORT, outPort, new IPv4Addr(NO_GATEWAY).toString,
            2)

        feedArpTable(simRouter, IPv4Addr.fromString(ipVm1),
                     MAC.fromString(macVm1))

        feedArpTable(simRouter, IPv4Addr.fromString(ipVm2),
                     MAC.fromString(macVm2))

        feedArpTable(simRouter, IPv4Addr.fromString(ipVm3),
                     MAC.fromString(macVm3))

        var eth = createUdpPacket(macSource, ipSource, macInPort, ipVm1)
        var simRes = simulate(packetContextFor(eth, inPort))._2
        simRes should not be Drop

        IPv4InvalidationArray.current.countRefs(
            IPv4Addr.fromString(ipVm1).toInt) should be (1)

        eth = createUdpPacket(macSource, ipSource, macInPort, ipVm2)
        simRes = simulate(packetContextFor(eth, inPort))._2
        simRes should not be Drop

        IPv4InvalidationArray.current.countRefs(
            IPv4Addr.fromString(ipVm2).toInt) should be (1)

        eth = createUdpPacket(macSource, ipSource, macInPort, ipVm3)
        simRes = simulate(packetContextFor(eth, inPort))._2
        simRes should not be Drop

        IPv4InvalidationArray.current.countRefs(
            IPv4Addr.fromString(ipVm3).toInt) should be (1)

        newRoute(router, ipSource, 32, "11.11.1.0", networkToReachLength+8,
            NextHop.PORT, outPort, new IPv4Addr(NO_GATEWAY).toString,
            2)

        simBackChannel should invalidateForNewRoutes(
            new IPv4Subnet("11.11.1.0", networkToReachLength+8))
    }

    scenario("Clean up invalidation trie") {
        val ipVm1 = "11.11.1.2"
        val macVm1 = "11:22:33:44:55:02"
        val ipVm2 = "11.11.1.23"
        val macVm2 = "11:22:33:44:55:03"

        newRoute(router, "0.0.0.0", 0, networkToReach, networkToReachLength,
                 NextHop.PORT, outPort, new IPv4Addr(NO_GATEWAY).toString,
                 2)

        feedArpTable(simRouter, IPv4Addr.fromString(ipVm1),
                     MAC.fromString(macVm1))

        var eth = createUdpPacket(macSource, ipSource, macInPort, ipVm1)
        val (simRes1, pktCtx1) = simulate(packetContextFor(eth, inPort))
        simRes1 should not be Drop

        IPv4InvalidationArray.current.countRefs(
            IPv4Addr.fromString(ipVm1).toInt) should be (1)

        feedArpTable(simRouter, IPv4Addr.fromString(ipVm2),
                     MAC.fromString(macVm2))

        eth = createUdpPacket(macSource, ipSource, macInPort, ipVm2)
        val (simRes2, pktCtx2) = simulate(packetContextFor(eth, inPort))
        simRes2 should not be Drop

        IPv4InvalidationArray.current.countRefs(
            IPv4Addr.fromString(ipVm2).toInt) should be (1)

        cbRegistry.runAndClear(pktCtx1.flowRemovedCallbacks)
        IPv4InvalidationArray.current.countRefs(
            IPv4Addr.fromString(ipVm1).toInt) should be (0)

        val ipSource2 = "20.20.0.40"

        eth = createUdpPacket(macSource, ipSource2, macInPort, ipVm1)
        val (simRes3, _) = simulate(packetContextFor(eth, inPort))
        simRes3 should not be Drop

        IPv4InvalidationArray.current.countRefs(
            IPv4Addr.fromString(ipVm1).toInt) should be (1)

        eth = createUdpPacket(macSource, ipSource2, macInPort, ipVm2)
        val (simRes4, pktCtx4) = simulate(packetContextFor(eth, inPort))
        simRes4 should not be Drop

        IPv4InvalidationArray.current.countRefs(
            IPv4Addr.fromString(ipVm2).toInt) should be (2)

        cbRegistry.runAndClear(pktCtx2.flowRemovedCallbacks)
        IPv4InvalidationArray.current.countRefs(
            IPv4Addr.fromString(ipVm2).toInt) should be (1)

        cbRegistry.runAndClear(pktCtx4.flowRemovedCallbacks)
        IPv4InvalidationArray.current.countRefs(
            IPv4Addr.fromString(ipVm2).toInt) should be (0)
    }

    private def createUdpPacket(srcMac: String, srcIp: String,
                                dstMac: String, dstIp: String) =
        Packets.udp(
            MAC.fromString(srcMac),
            MAC.fromString(dstMac),
            IPv4Addr.fromString(srcIp),
            IPv4Addr.fromString(dstIp),
            10, 11, "My UDP packet".getBytes)
}
