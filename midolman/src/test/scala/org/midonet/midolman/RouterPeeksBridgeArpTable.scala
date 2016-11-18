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
import scala.concurrent.duration._

import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, NoOp}
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.topology._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._

@RunWith(classOf[JUnitRunner])
class RouterPeeksBridgeArpTable extends MidolmanSpec {
    implicit val askTimeout: Timeout = 1 second

    var bridge: UUID = _
    var edgeBridgePort: UUID = _
    var tenantBridgePort: UUID = _

    var edgeRouter: UUID = _
    var interiorEdgeRouterPort: UUID = _
    var exteriorEdgeRouterPort: UUID = _

    var tenantRouter: UUID = _
    var interiorTenantRouterPort: UUID = _
    var exteriorTenantRouterPort: UUID = _

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

        val tenantRouterExteriorPortIp = new IPv4Subnet("10.0.3.254", 24)
        exteriorTenantRouterPort = newRouterPort(
            tenantRouter,
            MAC.random(),
            tenantRouterExteriorPortIp.toUnicastString,
            tenantRouterExteriorPortIp.toNetworkAddress.toString,
            tenantRouterExteriorPortIp.getPrefixLen)

        linkPorts(tenantBridgePort, interiorTenantRouterPort)
        materializePort(exteriorTenantRouterPort, hostId, "tenant-port")

        fetchPorts(edgeBridgePort,
                   tenantBridgePort,
                   interiorEdgeRouterPort,
                   exteriorEdgeRouterPort,
                   interiorTenantRouterPort,
                   exteriorTenantRouterPort)
        fetchRouters(edgeRouter, tenantRouter)
        fetchBridges(bridge)

        VirtualToPhysicalMapper.setPortActive(exteriorTenantRouterPort,
                                              portNumber = -1, active = true,
                                              tunnelKey = 0L)
    }

    feature ("ARP requests are suppressed") {
        scenario ("Pinging a router's interior port") {
            newRoute(edgeRouter,
                 "0.0.0.0", 0,
                 "0.0.0.0", 0,
                 Route.NextHop.PORT, interiorEdgeRouterPort,
                 new IPv4Addr(Route.NO_GATEWAY).toString, 10)

            val pkt =
                { eth src MAC.random dst fetchDevice[RouterPort](exteriorEdgeRouterPort).portMac } <<
                { ip4 src "10.0.0.64" dst fetchDevice[RouterPort](interiorTenantRouterPort).portAddress4.getAddress } <<
                { icmp.echo.request id 203 seq 25 data "data" }

            val (simRes, _) = sendPacket (exteriorEdgeRouterPort, pkt)
            simRes should be (NoOp)
        }

        scenario ("The next hop port is an interior port") {
            val gwMac = MAC.random
            val gwIp = IPv4Addr("10.0.3.128")
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
                Route.NextHop.PORT, exteriorTenantRouterPort,
                gwIp.toString, 10)
            feedArpTable(fetchDevice(tenantRouter), gwIp, gwMac)

            val pkt =
                { eth src MAC.random dst fetchDevice[RouterPort](exteriorEdgeRouterPort).portMac } <<
                { ip4 src "10.0.0.64" dst "10.0.3.64" } <<
                { icmp.echo.request id 203 seq 25 data "data" }

            val (simRes, pktCtx) = sendPacket (exteriorEdgeRouterPort, pkt)
            simRes should be (AddVirtualWildcardFlow)
            pktCtx.outPortId should be (exteriorTenantRouterPort)
            pktCtx.wcmatch.getEthDst should be (gwMac)
        }

        scenario ("The next hop gateway ip has been pre-seeded") {
            val gwMac = MAC.random
            val gwIp = IPv4Addr("10.0.3.128")
            val nextHopIp = IPv4Addr("10.0.2.64")
            newRoute(edgeRouter,
                "0.0.0.0", 0,
                "0.0.0.0", 0,
                Route.NextHop.PORT, interiorEdgeRouterPort,
                nextHopIp.toString, 10)
            newRoute(tenantRouter,
                "0.0.0.0", 0,
                "0.0.0.0", 0,
                Route.NextHop.PORT, exteriorTenantRouterPort,
                gwIp.toString, 10)
            feedArpTable(fetchDevice(tenantRouter), gwIp, gwMac)
            feedArpCache(
                fetchDevice(bridge),
                nextHopIp,
                fetchDevice[RouterPort](interiorTenantRouterPort).portMac)

            val pkt =
                { eth src MAC.random dst fetchDevice[RouterPort](exteriorEdgeRouterPort).portMac } <<
                { ip4 src "10.0.0.64" dst "10.0.6.64" } <<
                { icmp.echo.request id 203 seq 25 data "data" }

            val (simRes, pktCtx) = sendPacket (exteriorEdgeRouterPort, pkt)
            simRes should be (AddVirtualWildcardFlow)
            pktCtx.outPortId should be (exteriorTenantRouterPort)
            pktCtx.wcmatch.getEthDst should be (gwMac)
        }

        scenario ("The destination ip has been pre-seeded") {
            val gwMac = MAC.random
            val gwIp = IPv4Addr("10.0.3.128")
            val dstIp = IPv4Addr("10.0.6.64")
            newRoute(edgeRouter,
                "0.0.0.0", 0,
                "0.0.0.0", 0,
                Route.NextHop.PORT, interiorEdgeRouterPort,
                new IPv4Addr(Route.NO_GATEWAY).toString, 10)
            newRoute(tenantRouter,
                "0.0.0.0", 0,
                "0.0.0.0", 0,
                Route.NextHop.PORT, exteriorTenantRouterPort,
                gwIp.toString, 10)
            feedArpTable(fetchDevice(tenantRouter), gwIp, gwMac)
            feedArpCache(
                fetchDevice(bridge),
                dstIp,
                fetchDevice[RouterPort](interiorTenantRouterPort).portMac)

            val pkt =
                { eth src MAC.random dst fetchDevice[RouterPort](exteriorEdgeRouterPort).portMac } <<
                { ip4 src "10.0.0.64" dst dstIp } <<
                { icmp.echo.request id 203 seq 25 data "data" }

            val (simRes, pktCtx) = sendPacket (exteriorEdgeRouterPort, pkt)
            simRes should be (AddVirtualWildcardFlow)
            pktCtx.outPortId should be (exteriorTenantRouterPort)
            pktCtx.wcmatch.getEthDst should be (gwMac)
        }
    }
}
