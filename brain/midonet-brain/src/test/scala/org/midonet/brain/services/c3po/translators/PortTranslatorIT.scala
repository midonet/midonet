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

package org.midonet.brain.services.c3po.translators

import java.util.UUID

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.brain.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, Port => PortType, Router => RouterType, Subnet => SubnetType}
import org.midonet.cluster.data.neutron.TaskType.{Create, Delete, Update}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.packets.IPv4Subnet
import org.midonet.util.concurrent.toFutureOps

/**
 * Provides integration tests for PortTranslator.
 */
@RunWith(classOf[JUnitRunner])
class PortTranslatorIT extends C3POMinionTestBase {
    "Router Interface Port CREATE" should "create ports on the router and " +
    "the network and link them" in {
        // #1 Create a private Network
        val network1Id = UUID.randomUUID()
        val network1Json =
            networkJson(network1Id, "tenant1", "private").toString
        executeSqlStmts(insertTaskSql(
                id = 2, Create, NetworkType, network1Json, network1Id, "tx2"))
        eventually {
            val network = storage.get(classOf[Network], network1Id).await()
            network.getDhcpIdsList shouldBe empty
        }

        // #2 Attach a subnet to the Network
        val subnet1Id = UUID.randomUUID()
        val subnet1Cidr = IPv4Subnet.fromCidr("10.0.0.0/24").toString
        val subnet1Json = subnetJson(
                subnet1Id, network1Id, "tenant", name = "privateSubnet",
                cidr = subnet1Cidr).toString
        executeSqlStmts(insertTaskSql(
                id = 3, Create, SubnetType, subnet1Json, subnet1Id, "tx3"))
        eventually {
            val network = storage.get(classOf[Network], network1Id).await()
            network.getDhcpIdsList contains toProto(subnet1Id)
            val subnet = storage.get(classOf[Dhcp], subnet1Id).await()
            subnet.getNetworkId shouldBe toProto(network1Id)
        }

        // #3 Create a DHCP port to verify the metadata opt121 route
        val portId = UUID.randomUUID()
        val dhcpPortIp = "10.0.0.7"
        val pJson = portJson(
                name = "dhcp_port", id = portId,
                networkId = network1Id, adminStateUp = true,
                deviceOwner = DeviceOwner.DHCP,
                fixedIps = List(IPAlloc(dhcpPortIp, subnet1Id.toString)))
                .toString
        executeSqlStmts(insertTaskSql(id = 4, Create, PortType, pJson,
                                      portId, "tx4"))

        // #4 Create a Router.
        val tRouterId = UUID.randomUUID()
        val tRouterJson = routerJson("router1", tRouterId).toString
        executeSqlStmts(insertTaskSql(
                id = 5, Create, RouterType, tRouterJson, tRouterId, "tx5"))
        eventually {
            val tRouter = storage.get(classOf[Router], tRouterId).await()
            tRouter.getName shouldBe "router1"
        }

        // #5 Create a Router Interface Port.
        val rifPortUuid = UUID.randomUUID()
        val rifMac = "fa:16:3e:7d:c3:0e"
        val rifIp = "10.0.0.1"
        val rifPortJson = portJson(name = "router_interface",
                                   id = rifPortUuid,
                                   networkId = network1Id,
                                   macAddr = rifMac,
                                   fixedIps = List(IPAlloc(
                                           rifIp, subnet1Id.toString)),
                                   deviceOwner = DeviceOwner.ROUTER_INTERFACE,
                                   deviceId = tRouterId).toString
        executeSqlStmts(insertTaskSql(
                id = 6, Create, PortType, rifPortJson, rifPortUuid, "tx6"))

        // Test that a Router Interface Port has been correctly created as a
        // Network port for network1
        val rifPort = eventually(storage.get(classOf[Port], rifPortUuid)
                                        .await())

        // Test that the Router Interface Port is linked to the tenant
        // Router via a peer port.
        rifPort.getNetworkId shouldBe toProto(network1Id)
        rifPort.hasPeerId shouldBe true
        val peerRtrPort =
            storage.get(classOf[Port], rifPort.getPeerId).await()
        peerRtrPort.hasPeerId shouldBe true
        peerRtrPort.getPeerId shouldBe toProto(rifPortUuid)
        peerRtrPort.getRouterId shouldBe toProto(tRouterId)
        peerRtrPort.getRouteIdsCount shouldBe 2

        val routes = peerRtrPort.getRouteIdsList.asScala.map(
                storage.get(classOf[Route], _).await())
        val rifRouteId =
            RouteManager.routerInterfaceRouteId(toProto(rifPortUuid))
        val rifRoute = routes.find(_.getId == rifRouteId)
        rifRoute match {
            case Some(route) =>
                route.getSrcSubnet.getAddress shouldBe "0.0.0.0"
                route.getDstSubnet.getAddress shouldBe "10.0.0.0"
                route.getNextHopPortId shouldBe rifPort.getPeerId
            case _ => fail("Router Interface route is not found.")
        }
        val mdsRouteId = RouteManager.metadataServiceRouteId(rifPort.getPeerId)
        val mdsRoute = routes.find(_.getId == mdsRouteId)
        mdsRoute match {
            case Some(route) =>
                route.getSrcSubnet.getAddress shouldBe "10.0.0.0"
                route.getDstSubnet shouldBe RouteManager.META_DATA_SRVC
                route.getNextHopGateway.getAddress shouldBe "10.0.0.7"
                route.getNextHopPortId shouldBe rifPort.getPeerId
            case _ => fail("Metadata Service route is not found.")
        }

        // #6 Delete the Router Interface Port.
        executeSqlStmts(insertTaskSql(
                id = 7, Delete, PortType, json = "", rifPortUuid, "tx7"))

        eventually{
            storage.exists(classOf[Port], rifPortUuid).await() shouldBe false
        }
        storage.exists(classOf[Port], rifPort.getPeerId).await() shouldBe false
        storage.exists(classOf[Route], rifRouteId).await() shouldBe false
        storage.exists(classOf[Route], mdsRouteId).await() shouldBe false
        val tRouter = storage.get(classOf[Router], tRouterId).await()
        tRouter.getRouteIdsCount shouldBe 0
    }

    "Port translator" should " handle port bindings" in {
        // Create a host, a network, and two ports, one bound to the host.
        val nw1Id = UUID.randomUUID()
        val nw1Json = networkJson(nw1Id, "tenant", "network1").toString
        val h1Id = UUID.randomUUID()
        val (nw1p1Id, nw1p2Id) = (UUID.randomUUID(), UUID.randomUUID())
        val nw1p1Json = portJson("nw1p1", nw1p1Id, nw1Id,
                                 hostId = h1Id, ifName = "eth0").toString
        val nw1p2Json = portJson("nw1p2", nw1p2Id, nw1Id).toString

        createHost(h1Id)
        executeSqlStmts(
            insertTaskSql(2, Create, NetworkType, nw1Json, nw1Id, "tx1"),
            insertTaskSql(3, Create, PortType, nw1p1Json, nw1p1Id, "tx2"),
            insertTaskSql(4, Create, PortType, nw1p2Json, nw1p2Id, "tx3"))

        val hosts = storage.getAll(classOf[Host]).await()
        eventually {
            val h1Ftr = storage.get(classOf[Host], h1Id)
            val p1Ftr = storage.get(classOf[Port], nw1p1Id)
            val (h1, p1) = (h1Ftr.await(), p1Ftr.await())
            h1.getPortIdsList.asScala should contain only p1.getId
            p1.getHostId shouldBe h1.getId
            p1.getInterfaceName shouldBe "eth0"
        }

        // Bind the second port.
        val nw1p2JsonV2 = portJson("nw1p2", nw1p2Id, nw1Id,
                                   hostId = h1Id, ifName = "eth1").toString
        executeSqlStmts(
            insertTaskSql(5, Update, PortType, nw1p2JsonV2, nw1p2Id, "tx4"))
        eventually {
            val h1Ftr = storage.get(classOf[Host], h1Id)
            val p2Ftr = storage.get(classOf[Port], nw1p2Id)
            val (h1, p2) = (h1Ftr.await(), p2Ftr.await())

            h1.getPortIdsList.asScala should
                contain only (p2.getId, toProto(nw1p1Id))
            p2.getHostId shouldBe h1.getId
            p2.getInterfaceName shouldBe "eth1"
        }

        // Unbind the first port.
        val nw1p1JsonV2 = portJson("nw1p1", nw1p1Id, nw1Id).toString
        executeSqlStmts(
            insertTaskSql(6, Update, PortType, nw1p1JsonV2, nw1p2Id, "tx5"))
        eventually {
            val h1Ftr = storage.get(classOf[Host], h1Id)
            val p1Ftr = storage.get(classOf[Port], nw1p1Id)
            val (h1, p1) = (h1Ftr.await(), p1Ftr.await())
            h1.getPortIdsList.asScala should contain only toProto(nw1p2Id)
            p1.hasHostId shouldBe false
            p1.hasInterfaceName shouldBe false
        }

        // Rebind the first port and delete the second port.
        executeSqlStmts(
            insertTaskSql(7, Update, PortType, nw1p1Json, nw1p1Id, "tx6"),
            insertTaskSql(8, Delete, PortType, "", nw1p2Id, "tx7"))
        eventually {
            val h1Ftr = storage.get(classOf[Host], h1Id)
            val p1Ftr = storage.get(classOf[Port], nw1p1Id)
            val (h1, p1) = (h1Ftr.await(), p1Ftr.await())
            h1.getPortIdsList.asScala should contain only toProto(nw1p1Id)
            p1.getHostId shouldBe h1.getId
            p1.getInterfaceName shouldBe "eth0"
        }

        // Delete the host.
        deleteHost(h1Id)
        eventually {
            val p1 = storage.get(classOf[Port], nw1p1Id).await()
            p1.hasHostId shouldBe false
            p1.getInterfaceName shouldBe "eth0"
        }

    }
}