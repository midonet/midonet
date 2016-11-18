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
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.ErrorDrop
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.simulation.{Simulator, RouterPort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.{MAC, IPv4Subnet}

@RunWith(classOf[JUnitRunner])
class TopologyWithLoop extends MidolmanSpec {
    var bridge: UUID = _
    var edgeBridgePort: UUID = _
    var tenantBridgePort: UUID = _

    var edgeRouter: UUID = _
    var interiorEdgeRouterPort: UUID = _
    var exteriorEdgeRouterPort: UUID = _

    var tenantRouter: UUID = _
    var interiorTenantRouterPort: UUID = _

    override def beforeTest() {
        bridge = newBridge("bridge")
        edgeRouter = newRouter("edge-router")
        tenantRouter = newRouter("tenant-router")

        edgeBridgePort = newBridgePort(bridge)
        tenantBridgePort = newBridgePort(bridge)

        val edgeRouterInteriorPortIp = new IPv4Subnet("10.0.0.254", 24)
        interiorEdgeRouterPort = newRouterPort(
            edgeRouter,
            MAC.random(),
            edgeRouterInteriorPortIp.toUnicastString,
            edgeRouterInteriorPortIp.toNetworkAddress.toString,
            edgeRouterInteriorPortIp.getPrefixLen)

        val edgeRouterExteriorPortIp = new IPv4Subnet("10.0.1.254", 24)
        exteriorEdgeRouterPort = newRouterPort(
            edgeRouter,
            MAC.random(),
            edgeRouterExteriorPortIp.toUnicastString,
            edgeRouterExteriorPortIp.toNetworkAddress.toString,
            edgeRouterExteriorPortIp.getPrefixLen)

        linkPorts(edgeBridgePort, interiorEdgeRouterPort)
        materializePort(exteriorEdgeRouterPort, hostId, "edge-port")

        val tenantRouterInteriorPortIp = new IPv4Subnet("10.0.2.254", 24)
        interiorTenantRouterPort = newRouterPort(
            tenantRouter,
            MAC.random(),
            tenantRouterInteriorPortIp.toUnicastString,
            tenantRouterInteriorPortIp.toNetworkAddress.toString,
            tenantRouterInteriorPortIp.getPrefixLen)

        linkPorts(tenantBridgePort, interiorTenantRouterPort)

        fetchPorts(edgeBridgePort,
                   tenantBridgePort,
                   interiorEdgeRouterPort,
                   exteriorEdgeRouterPort,
                   interiorTenantRouterPort)
        fetchRouters(edgeRouter, tenantRouter)
        fetchBridges(bridge)
    }

    feature("A topology with a loop") {
        scenario ("Packets are dropped") {
            newRoute(edgeRouter,
                     "0.0.0.0", 0,
                     "0.0.0.0", 0,
                     Route.NextHop.PORT, interiorEdgeRouterPort,
                     fetchDevice[RouterPort](interiorTenantRouterPort)
                         .portAddress4.getAddress.toString,
                     10)
            newRoute(tenantRouter,
                     "0.0.0.0", 0,
                     "0.0.0.0", 0,
                     Route.NextHop.PORT, interiorTenantRouterPort,
                     fetchDevice[RouterPort](interiorEdgeRouterPort)
                         .portAddress4.getAddress.toString,
                     10)

            val pkt =
                { eth src MAC.random dst fetchDevice[RouterPort](exteriorEdgeRouterPort).portMac } <<
                { ip4 src "10.0.0.64" dst "10.0.3.64" ttl Byte.MaxValue } <<
                { icmp.echo.request id 203 seq 25 data "data" }

            val (simRes, pktCtx) = sendPacket (exteriorEdgeRouterPort, pkt)
            simRes should be (ErrorDrop)
            pktCtx.devicesTraversed should be (Simulator.MaxDevicesTraversed)
        }
    }
}
