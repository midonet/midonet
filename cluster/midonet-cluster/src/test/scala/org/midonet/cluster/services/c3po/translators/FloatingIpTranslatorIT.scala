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

package org.midonet.cluster.services.c3po.translators

import java.util.UUID

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind.JsonNode

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{FloatingIp => FloatingIpType, Network => NetworkType, Port => PortType, PortBinding => PortBindingType, Router => RouterType, Subnet => SubnetType}
import org.midonet.cluster.data.neutron.TaskType.{Create, Delete}
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
    private def floatingIpJson(id: UUID,
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
        // Create a private Network
        val privateNetworkId = UUID.randomUUID()
        val privateNetworkJson =
            networkJson(privateNetworkId, "tenant1", "private net").toString
        executeSqlStmts(insertTaskSql(
                id = 2, Create, NetworkType, privateNetworkJson,
                privateNetworkId, "tx2"))

        // Attach a subnet to the Network
        val privateSubnetId = UUID.randomUUID()
        val privateSubnetCidr = "10.0.0.0/24"
        val gatewayIp = "10.0.0.1"
        val snetJson = subnetJson(
                privateSubnetId, privateNetworkId,
                name = "private subnet", cidr = privateSubnetCidr,
                gatewayIp = gatewayIp).toString
        executeSqlStmts(insertTaskSql(
                id = 3, Create, SubnetType, snetJson, privateSubnetId, "tx3"))
        eventually {
            val network = storage.get(classOf[Network], privateNetworkId)
                                 .await()
            val subnetIdProto = toProto(privateSubnetId)
            network.getDhcpIdsList contains subnetIdProto shouldBe true
        }
        val dhcp = storage.get(classOf[Dhcp], privateSubnetId).await()
        dhcp.getNetworkId shouldBe toProto(privateNetworkId)

        // Set up a host. Needs to do this directly via Zoom as the Host info
        // is to be created by the Agent.
        val hostId = UUID.randomUUID()
        createHost(hostId)

        // Creates a VIF port.
        val vifPortId = UUID.randomUUID()
        val fixedIp = "10.0.0.9"
        val vifPortMac = "fa:16:3e:bf:d4:56"
        val vifPortJson = portJson(name = "port1", id = vifPortId,
                                   networkId = privateNetworkId,
                                   macAddr = vifPortMac,
                                   fixedIps = List(IPAlloc(
                                           fixedIp, privateSubnetId.toString)),
                                   deviceOwner = DeviceOwner.NOVA).toString
        executeSqlStmts(insertTaskSql(
                id = 4, Create, PortType, vifPortJson, vifPortId, "tx4"))
        eventually {
            storage.exists(classOf[Port], vifPortId).await() shouldBe true
        }

        // Creates a Port Binding
        val bindingId = UUID.randomUUID()
        val interfaceName = "if1"
        val bindingJson = portBindingJson(
                bindingId, hostId, interfaceName, vifPortId).toString
        executeSqlStmts(insertTaskSql(
                id = 5, Create, PortBindingType, bindingJson, bindingId, "tx5"))
        eventually(checkPortBinding(hostId, vifPortId, interfaceName))

        // Create an external Network
        val extNetworkId = UUID.randomUUID()
        val extNetworkJson = networkJson(
                extNetworkId, "admin", "public", external = true).toString
        executeSqlStmts(insertTaskSql(
                id = 6, Create, NetworkType, extNetworkJson, extNetworkId,
                "tx6"))
        eventually {
            storage.exists(classOf[Network], extNetworkId).await() shouldBe true
        }

        // Attach a subnet to the external Network
        val extSubnetId = UUID.randomUUID()
        val extSubnet = IPv4Subnet.fromCidr("172.24.4.0/24")
        val extSubnetCidr = extSubnet.toString
        val extSubnetGwIp = "172.24.4.1"
        val extSubnetJson = subnetJson(extSubnetId, extNetworkId,
                                       name = "subnet", cidr = extSubnetCidr,
                                       gatewayIp = extSubnetGwIp).toString
        executeSqlStmts(insertTaskSql(
                id = 7, Create, SubnetType, extSubnetJson, extSubnetId,
                "tx7"))
        eventually {
            storage.exists(classOf[Dhcp], extSubnetId).await() shouldBe true
        }

        // #8 Create a Router GW Port
        val rgwPortId = UUID.randomUUID()
        val rgwMac = "fa:16:3e:62:0d:2b"
        val rgwIp = "172.24.4.4"
        val rgwPortJson = portJson(name = "router_gateway",
                                   id = rgwPortId,
                                   networkId = extNetworkId,
                                   macAddr = rgwMac,
                                   fixedIps = List(IPAlloc(
                                           rgwIp, extSubnetId.toString)),
                                   deviceOwner = DeviceOwner.ROUTER_GATEWAY)
                                   .toString
        executeSqlStmts(insertTaskSql(
                id = 8, Create, PortType, rgwPortJson, rgwPortId, "tx8"))

        // #9 Create a tenant Router.
        val tRouterId = UUID.randomUUID()
        val tRouterJson = routerJson(tRouterId, gwPortId = rgwPortId).toString
        executeSqlStmts(insertTaskSql(
                id = 9, Create, RouterType, tRouterJson, tRouterId, "tx9"))

        // Tests that the tenant Router has been hooked up with Provider Router
        // via the above-created Router Gateway port.
        eventually {
            storage.exists(classOf[Router], tRouterId).await() shouldBe true
        }
        var rgwPort = storage.get(classOf[Port], rgwPortId).await()
        rgwPort.hasPeerId shouldBe true
        val rgwPortPeer = storage.get(classOf[Port], rgwPort.getPeerId).await()
        rgwPortPeer.hasRouterId shouldBe true
        rgwPortPeer.getRouterId shouldBe toProto(tRouterId)

        // #10 Create a Router Interface Port.
        val rifPortUuid = UUID.randomUUID()
        val rifMac = "fa:16:3e:7d:c3:0e"
        val rifIp = "10.0.0.1"
        val rifPortJson = portJson(name = "router_interface",
                                   id = rifPortUuid,
                                   networkId = privateNetworkId,
                                   macAddr = rifMac,
                                   fixedIps = List(IPAlloc(
                                           rifIp, privateSubnetId.toString)),
                                   deviceOwner = DeviceOwner.ROUTER_INTERFACE,
                                   deviceId = tRouterId).toString
        executeSqlStmts(insertTaskSql(
                id = 10, Create, PortType, rifPortJson, rifPortUuid, "tx10"))
        eventually {
            storage.exists(classOf[Port], rifPortUuid).await() shouldBe true
        }

        // #11 & 12 Create a Floating IP Port and a FloatingIP in a same
        // transaction where the floating IP port is created first.
        val fipId = UUID.randomUUID()
        val fipPortId = UUID.randomUUID()
        val fipMac = "fa:16:3e:0e:27:1c"
        val fipIp = "172.24.4.3"
        val fipPortJson = portJson(name = "fip_port",
                                   id = fipPortId,
                                   networkId = extNetworkId,
                                   macAddr = fipMac,
                                   fixedIps = List(IPAlloc(
                                           fipIp, extSubnetId.toString)),
                                   deviceOwner = DeviceOwner.ROUTER_GATEWAY,
                                   // Neutron sets FIP ID as device ID.
                                   deviceId = fipId).toString
        val fipJson = floatingIpJson(id = fipId,
                                     floatingNetworkId = extNetworkId,
                                     floatingIpAddress = fipIp).toString
        // Floating IP is not assigned to any port yet.
        executeSqlStmts(insertTaskSql(
                id = 11, Create, PortType, fipPortJson, fipPortId, "tx11"),
                        insertTaskSql(
                id = 12, Create, FloatingIpType, fipJson, fipId, "tx11"))

        val fip = eventually(storage.get(classOf[FloatingIp], fipId).await())
        fip.getFloatingIpAddress shouldBe IPAddressUtil.toProto(fipIp)

        // #13 Update the Floating IP with a port to assign to.
        val assignedFipJson = floatingIpJson(id = fipId,
                                             floatingNetworkId = extNetworkId,
                                             floatingIpAddress = fipIp,
                                             routerId = tRouterId,
                                             portId = vifPortId,
                                             fixedIpAddress = fixedIp).toString
        insertUpdateTask(13, FloatingIpType, assignedFipJson, fipId)

        // Tests that a route for the floating IP port is set on the Provider
        // Router.
        val fipRouteId = RouteManager.fipGatewayRouteId(toProto(fipId))
        val fipRoute = eventually(storage.get(classOf[Route],
                                              fipRouteId).await())
        fipRoute.getSrcSubnet shouldBe univSubnet4
        fipRoute.getDstSubnet shouldBe IPSubnetUtil.fromAddr(
                IPAddressUtil.toProto(fipIp))
        fipRoute.getNextHopPortId shouldBe toProto(rgwPortId)

        // Provider Router should have routes for the Floating IP set up now.
        eventually {
            rgwPort = storage.get(classOf[Port], rgwPortId).await()
            rgwPort.getRouteIdsList contains fipRouteId  shouldBe true
        }

        // Tests that SNAT / DNAT rules for the floating IP have been set.
        val tRouter = storage.get(classOf[Router], tRouterId).await()
        val inboundChainId = ChainManager.inChainId(tRouterId)
        val outboundChainId = ChainManager.outChainId(tRouterId)
        tRouter.getInboundFilterId shouldBe inboundChainId
        tRouter.getOutboundFilterId shouldBe outboundChainId
        val inChain = storage.get(classOf[Chain], inboundChainId).await()
        val outChain = storage.get(classOf[Chain], outboundChainId).await()
        val snatRuleId = RouteManager.fipSnatRuleId(fipId)
        val dnatRuleId = RouteManager.fipDnatRuleId(fipId)
        inChain.getRuleIdsList.asScala should contain(dnatRuleId)
        outChain.getRuleIdsList.asScala should contain(snatRuleId)
        // SNAT
        val snat = storage.get(classOf[Rule], snatRuleId).await()
        snat.getChainId shouldBe outboundChainId
        snat.getAction shouldBe Rule.Action.ACCEPT
        snat.getOutPortIdsCount shouldBe 1
        snat.getOutPortIds(0) shouldBe toProto(rgwPortId)
        snat.getNwSrcIp.getAddress shouldBe fixedIp
        val snatRule = snat.getNatRuleData
        snatRule.getDnat shouldBe false
        snatRule.getNatTargetsCount shouldBe 1
        val snatTarget = snatRule.getNatTargets(0)
        snatTarget.getNwStart.getAddress shouldBe fipIp
        snatTarget.getNwEnd.getAddress shouldBe fipIp
        snatTarget.getTpStart shouldBe 1
        snatTarget.getTpEnd shouldBe 65535
        // DNAT
        val dnat = storage.get(classOf[Rule], dnatRuleId).await()
        dnat.getChainId shouldBe inboundChainId
        dnat.getAction shouldBe Rule.Action.ACCEPT
        dnat.getInPortIdsCount shouldBe 1
        dnat.getInPortIds(0) shouldBe toProto(rgwPortId)
        dnat.getNwDstIp.getAddress shouldBe fipIp
        val dnatRule = dnat.getNatRuleData
        dnatRule.getDnat shouldBe true
        dnatRule.getNatTargetsCount shouldBe 1
        val dnatTarget = dnatRule.getNatTargets(0)
        dnatTarget.getNwStart.getAddress shouldBe fixedIp
        dnatTarget.getNwEnd.getAddress shouldBe fixedIp
        dnatTarget.getTpStart shouldBe 1
        dnatTarget.getTpEnd shouldBe 65535

        // #14 Deleting a Floating IP should clear the GW route.
        executeSqlStmts(insertTaskSql(
                id = 14, Delete, FloatingIpType, json = "", fipId, "tx14"))
        // Provider Router should have routes cleared.
        eventually {
            storage.exists(classOf[FloatingIp], fipId).await() shouldBe false
        }
        storage.exists(classOf[Route], fipRouteId).await() shouldBe false
        rgwPort = storage.get(classOf[Port], rgwPortId).await()
        rgwPort.getRouteIdsList contains fipRouteId  shouldBe false
        storage.exists(classOf[Rule], snatRuleId).await() shouldBe false
        storage.exists(classOf[Rule], dnatRuleId).await() shouldBe false
        val inChainNoNat = storage.get(classOf[Chain], inboundChainId).await()
        val outChainNoNat = storage.get(classOf[Chain], outboundChainId).await()
        inChainNoNat.getRuleIdsList.contains(dnatRuleId) shouldBe false
        outChainNoNat.getRuleIdsList.contains(snatRuleId) shouldBe false
    }
}