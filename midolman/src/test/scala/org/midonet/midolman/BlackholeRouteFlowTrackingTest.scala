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
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, ErrorDrop}
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.simulation.Router
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class BlackholeRouteFlowTrackingTest extends MidolmanSpec
                                     with MidonetEventually {

    val leftRouterMac = "01:01:01:10:10:aa"
    val leftOtherMac = "01:01:01:10:10:bb"
    val leftRouterIp = "192.168.1.1"
    val leftOtherIp = "192.168.1.10"
    val leftNet = "192.168.1.0"

    val rightRouterMac = "02:02:02:20:20:aa"
    val rightOtherMac = "02:02:02:20:20:bb"
    val rightRouterIp = "192.168.2.1"
    val rightOtherIp = "192.168.2.10"
    val rightNet = "192.168.2.0"

    val blackholedDestination = "10.0.0.1"

    var leftPort: UUID = null
    var rightPort: UUID = null

    val netmask = 24

    var simRouter: Router = null
    var clusterRouter: UUID = null

    private def buildTopology() {
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        leftPort = newRouterPort(clusterRouter,
            MAC.fromString(leftRouterMac), leftRouterIp, leftNet, netmask)
        leftPort should not be null
        materializePort(leftPort, hostId, "eth0")

        rightPort = newRouterPort(clusterRouter,
            MAC.fromString(rightRouterMac), rightRouterIp, rightNet, netmask)
        rightPort should not be null
        materializePort(rightPort, hostId, "eth1")

        newRoute(clusterRouter, "0.0.0.0", 0, blackholedDestination, 30,
                 NextHop.BLACKHOLE, null, null, 1)
        newRoute(clusterRouter, "0.0.0.0", 0, leftNet, netmask, NextHop.PORT,
                 leftPort, new IPv4Addr(Route.NO_GATEWAY).toString, 1)
        newRoute(clusterRouter, "0.0.0.0", 0, rightNet, netmask, NextHop.PORT,
                 rightPort, new IPv4Addr(Route.NO_GATEWAY).toString, 1)

        simRouter = fetchDevice[Router](clusterRouter)
        simRouter should not be null
        feedArpTable(simRouter, IPv4Addr(leftOtherIp), MAC.fromString(leftOtherMac))
        feedArpTable(simRouter, IPv4Addr(rightOtherIp), MAC.fromString(rightOtherMac))
    }

    override def beforeTest() {
        buildTopology()
    }

    def frameThatWillBeDropped = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr leftOtherMac -> leftRouterMac } <<
        { ip4 addr leftOtherIp --> blackholedDestination } <<
        { udp ports 53 ---> 53 } <<
        payload(UUID.randomUUID().toString)
    }

    def frameThatWillBeForwarded = {
        import org.midonet.packets.util.PacketBuilder._

        { eth addr leftOtherMac -> leftRouterMac } <<
        { ip4 addr leftOtherIp --> rightOtherIp } <<
        { udp ports 53 ---> 53 } <<
        payload(UUID.randomUUID().toString)
    }

    feature("RouterManager tracks permanent flows but not temporary ones") {
        scenario("blackhole route") {
            When("a packet hits a blackhole route")
            val (_, action) = simulateDevice(simRouter, frameThatWillBeDropped, leftPort)
            action shouldEqual ErrorDrop

            And("the routing table changes")
            newRoute(clusterRouter, "0.0.0.0", 0, blackholedDestination, 32,
                NextHop.BLACKHOLE, null, null, 1)
            simBackChannel.clear()

            eventually { fetchDevice[Router](clusterRouter) should not be simRouter }

            Then("no invalidations are sent to the FlowController")
            simBackChannel.clear() should be ('empty)
        }

        scenario("to-port route") {
            When("a packet hits a forwarding route")
            val (ctx, action) = simulateDevice(simRouter,
                frameThatWillBeForwarded, leftPort)
            ctx should be (toPorts(rightPort))
            action should be (AddVirtualWildcardFlow)

            And("the routing table changes")
            simBackChannel.clear()
            newRoute(clusterRouter, "0.0.0.0", 0, rightOtherIp, 32,
                NextHop.REJECT, null, null, 1)

            eventually { fetchDevice[Router](clusterRouter) should not be simRouter }

            Then("an invalidation by destination IP is scheduled")
            val tag = FlowTagger.tagForDestinationIp(clusterRouter,
                                                     IPv4Addr(rightOtherIp))
            simBackChannel should invalidateForNewRoutes (new IPv4Subnet(rightOtherIp, 32))
        }
    }
}
