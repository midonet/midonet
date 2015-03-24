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

import com.fasterxml.jackson.databind.JsonNode

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.brain.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{FloatingIp => FloatingIpType, Network => NetworkType, NoData, Port => PortType, PortBinding => PortBindingType, Router => RouterType, Subnet => SubnetType}
import org.midonet.cluster.data.neutron.TaskType.{Create, Delete, Update}
import org.midonet.cluster.models.Neutron.FloatingIp
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.IPSubnetUtil.univSubnet4
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.packets.IPv4Subnet
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorIT extends C3POMinionTestBase {
    protected def floatingIpJson(id: UUID,
                                 floatingNetworkId: UUID,
                                 floatingIpAddress: String,
                                 routerId: UUID = null,
                                 portId: UUID = null,
                                 fixedIpAddress: String = null,
                                 tenantId: String = null): JsonNode = {
        val fip = nodeFactory.objectNode
        fip.put("id", id.toString)
        fip.put("floating_ip_address", floatingIpAddress)
        fip.put("floating_network_id", floatingNetworkId.toString)
        if (routerId != null) fip.put("router_id", routerId.toString)
        if (portId != null) fip.put("port_id", portId.toString)
        if (fixedIpAddress != null) fip.put("fixed_ip_address", fixedIpAddress)
        fip.put("tenant_id", tenantId)
        fip
    }

    "C3PO" should "set a route to GW for the floating IP." in {
        // #1 Create a private Network
        val privateNetworkId = UUID.randomUUID()
        val privateNetworkJson =
            networkJson(privateNetworkId, "tenant1", "private").toString
        executeSqlStmts(insertTaskSql(
                id = 2, Create, NetworkType, privateNetworkJson,
                privateNetworkId, "tx2"))

        // #2 Attach a subnet to the Network
        val privateSubnetId = UUID.randomUUID()
        val privateSubnetCidr = IPv4Subnet.fromCidr("10.0.0.0/24").toString
        val gatewayIp = "10.0.0.1"
        val sJson = subnetJson(
                privateSubnetId, privateNetworkId, "tenant",
                name = "privateSubnet", cidr = privateSubnetCidr,
                gatewayIp = gatewayIp).toString
        executeSqlStmts(insertTaskSql(
                id = 3, Create, SubnetType, sJson, privateSubnetId, "tx3"))
        eventually {
            storage.exists(classOf[Network],
                           privateNetworkId).await() shouldBe true
            val dhcp = storage.get(classOf[Dhcp], privateSubnetId).await()
            dhcp.getNetworkId shouldBe toProto(privateNetworkId)
        }

        // #3 Sets up a host. Needs to do this directly via Zoom as the Host
        // info is to be created by the Agent.
        val hostId = UUID.randomUUID()
        val host = Host.newBuilder.setId(hostId).build()
        backend.ownershipStore.create(host, hostId)
        eventually {
            storage.exists(classOf[Host], hostId).await() shouldBe true
        }

        // #4 Creates a VIF port.
        val vifPortId = UUID.randomUUID()
        val fixedIp = "10.0.0.9"
        val portMac = "fa:16:3e:bf:d4:56"
        val vifPortJson = portJson(name = "port1", id = vifPortId,
                                   networkId = privateNetworkId,
                                   mac_address = portMac,
                                   fixedIps = List(IPAlloc(
                                           fixedIp, privateSubnetId.toString)),
                                   deviceOwner = DeviceOwner.NOVA).toString
        executeSqlStmts(insertTaskSql(
                id = 4, Create, PortType, vifPortJson, vifPortId, "tx4"))
        eventually {
            storage.exists(classOf[Port], vifPortId).await() shouldBe true
        }

        // #5 Creates a Port Binding
        val bindingId = UUID.randomUUID()
        val interfaceName = "if1"
        val bindingJson = portBindingJson(
                bindingId, hostId, interfaceName, vifPortId).toString
        executeSqlStmts(insertTaskSql(
                id = 5, Create, PortBindingType, bindingJson, bindingId, "tx5"))
        eventually {
            val boundHost = storage.get(classOf[Host], hostId).await()
            boundHost.getPortBindingsCount shouldBe 1
            val binding = boundHost.getPortBindings(0)
            binding.getInterfaceName shouldBe interfaceName
            binding.getPortId shouldBe toProto(vifPortId)
        }

        // #6 Create a Router.
        val tRouterId = UUID.randomUUID()
        val tRouterJson = routerJson("router1", tRouterId).toString
        executeSqlStmts(insertTaskSql(
                id = 6, Create, RouterType, tRouterJson, tRouterId,
                "tx6"))
        eventually {
            storage.exists(classOf[Router], tRouterId).await() shouldBe true
        }

        // #7 Create a Router Interface Port.
        val rifPortUuid = UUID.randomUUID()
        val rifMac = "fa:16:3e:7d:c3:0e"
        val rifIp = "10.0.0.1"
        val rifPortJson = portJson(name = "router_interface",
                                   id = rifPortUuid,
                                   networkId = privateNetworkId,
                                   mac_address = rifMac,
                                   fixedIps = List(IPAlloc(
                                           rifIp, privateSubnetId.toString)),
                                   deviceOwner = DeviceOwner.ROUTER_INTERFACE,
                                   deviceId = tRouterId).toString
        executeSqlStmts(insertTaskSql(
                id = 7, Create, PortType, rifPortJson, rifPortUuid, "tx7"))
        eventually {
            storage.exists(classOf[Port], rifPortUuid).await() shouldBe true
        }

        // #8 Create an external Network
        val extNetworkId = UUID.randomUUID()
        val extNetworkJson = networkJson(
                extNetworkId, "admin", "public", external = true).toString
        executeSqlStmts(insertTaskSql(
                id = 8, Create, NetworkType, extNetworkJson, extNetworkId,
                "tx8"))
        eventually {
            storage.exists(classOf[Network], extNetworkId).await() shouldBe true
        }

        // #9 Attach a subnet to the external Network
        val extSubnetId = UUID.randomUUID()
        val extSubnetCidr = IPv4Subnet.fromCidr("172.24.4.0/24").toString
        val extSubnetGwIp = "172.24.4.1"
        val extSubnetJson = subnetJson(extSubnetId, extNetworkId, "admin",
                                       name = "subnet",
                                       cidr = privateSubnetCidr,
                                       gatewayIp = extSubnetGwIp).toString
        executeSqlStmts(insertTaskSql(
                id = 9, Create, SubnetType, extSubnetJson, privateSubnetId,
                "tx9"))
        eventually {
            storage.exists(classOf[Dhcp], extSubnetId).await() shouldBe true
        }

        // #10 Create a Router GW Port
        val rgwPortId = UUID.randomUUID()
        val rgwMac = "fa:16:3e:62:0d:2b"
        val rgwIp = "172.24.4.4"
        val rgwPortJson = portJson(name = "router_gateway",
                                   id = rgwPortId,
                                   networkId = extNetworkId,
                                   mac_address = rifMac,
                                   fixedIps = List(IPAlloc(
                                           rgwIp, extSubnetId.toString)),
                                   deviceOwner = DeviceOwner.ROUTER_GATEWAY,
                                   deviceId = tRouterId).toString
        executeSqlStmts(insertTaskSql(
                id = 10, Create, PortType, rgwPortJson, rgwPortId, "tx10"))

        val rgwPort = eventually(storage.get(classOf[Port], rgwPortId).await())
        rgwPort.getRouterId shouldBe RouterTranslator.providerRouterId

        // #11 Create a Floating IP Port and a FloatingIP
        val fipId = UUID.randomUUID()
        val fipPortId = UUID.randomUUID()
        val fipMac = "fa:16:3e:0e:27:1c"
        val fipIp = "172.24.4.3"
        val fipPortJson = portJson(name = "fip_port",
                                   id = fipPortId,
                                   networkId = extNetworkId,
                                   mac_address = fipMac,
                                   fixedIps = List(IPAlloc(
                                           fipIp, extSubnetId.toString)),
                                   deviceOwner = DeviceOwner.ROUTER_GATEWAY,
                                   // Neutron sets FIP ID as device ID.
                                   deviceId = fipId).toString
        val fipJson = floatingIpJson(id = fipId,
                                     floatingNetworkId = extNetworkId,
                                     floatingIpAddress = fipIp).toString
        // Floating IP Port and Floating IP are created in a same transaction.
        // Floating IP is not assigned to any port yet.
        executeSqlStmts(insertTaskSql(
                id = 11, Create, PortType, fipPortJson, fipPortId, "tx11"),
                        insertTaskSql(
                id = 12, Create, FloatingIpType, fipJson, fipId, "tx11"))

        val fip = eventually(storage.get(classOf[FloatingIp], fipId).await())
        fip.getFloatingIpAddress shouldBe IPAddressUtil.toProto(fipIp)
        // Floating IP that's not assigned shouldn't add any routes.
        var prvRtr = storage.get(classOf[Router],
                                 RouterTranslator.providerRouterId).await()
        prvRtr.getRouteIdsList shouldBe empty

        // #12 Update the Floating IP with a port to assign to.
        val assignedFipJson = floatingIpJson(id = fipId,
                                             floatingNetworkId = extNetworkId,
                                             floatingIpAddress = fipIp,
                                             routerId = tRouterId,
                                             portId = vifPortId,
                                             fixedIpAddress = fixedIp).toString
        executeSqlStmts(insertTaskSql(
                id = 13, Update, FloatingIpType, assignedFipJson, fipId,
                "tx13"))
        // Provider Router should have routes for the Floating IP set up now.
        eventually {
            prvRtr = storage.get(
                classOf[Router], RouterTranslator.providerRouterId).await()
            prvRtr.getRouteIdsList.size() shouldBe 1
        }
        val gwRoute = storage.get(classOf[Route], prvRtr.getRouteIds(0)).await()
        gwRoute.getSrcSubnet shouldBe univSubnet4
        val fipSubnet = IPSubnetUtil.fromAddr(IPAddressUtil.toProto(fipIp))
        gwRoute.getDstSubnet shouldBe fipSubnet
        gwRoute.getNextHopPortId shouldBe toProto(rgwPortId)
        gwRoute.getRouterId shouldBe RouterTranslator.providerRouterId

        // #13 Deleting a Floating IP should clear the GW route.
        executeSqlStmts(insertTaskSql(
                id = 14, Delete, FloatingIpType, json = "", fipId, "tx14"))
        // Provider Router should have routes cleared.
        eventually {
            prvRtr = storage.get(
                classOf[Router], RouterTranslator.providerRouterId).await()
            prvRtr.getRouteIdsList.size() shouldBe 0
        }
    }
}