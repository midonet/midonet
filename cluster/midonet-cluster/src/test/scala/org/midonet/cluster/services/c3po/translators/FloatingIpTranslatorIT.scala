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
import org.midonet.cluster.data.neutron.TaskType.{Create, Delete, Update}
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronPort}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.IPSubnetUtil.univSubnet4
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.packets.IPv4Subnet
import org.midonet.packets.MAC
import org.midonet.packets.util.AddressConversions._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorIT extends C3POMinionTestBase with ChainManager {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

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

    "C3PO" should "add NAT rules and ARP entry for the floating IP." in {
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

        // Create a legacy ReplicatedMap for the external Network ARP table.
        val arpTable = dataClient.getIp4MacMap(extNetworkId)
        eventually {
            arpTable.start()
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
        // The ARP table should NOT YET contain the ARP entry.
        arpTable.containsKey(fipIp) shouldBe false

        // #13 Update the Floating IP with a port to assign to.
        val assignedFipJson = floatingIpJson(id = fipId,
                                             floatingNetworkId = extNetworkId,
                                             floatingIpAddress = fipIp,
                                             routerId = tRouterId,
                                             portId = vifPortId,
                                             fixedIpAddress = fixedIp).toString
        insertUpdateTask(13, FloatingIpType, assignedFipJson, fipId)

        val snatRuleId = RouteManager.fipSnatRuleId(fipId)
        val dnatRuleId = RouteManager.fipDnatRuleId(fipId)
        val inboundChainId = inChainId(tRouterId)
        val outboundChainId = outChainId(tRouterId)
        eventually {
            // External network's ARP table should contain an ARP entry
            arpTable.get(fipIp) shouldBe MAC.fromString(rgwMac)

            // Tests that SNAT / DNAT rules for the floating IP have been set.
            val inChain = storage.get(classOf[Chain], inboundChainId).await()
            val outChain = storage.get(classOf[Chain], outboundChainId).await()
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
            // Test that the VIF port's been updated with the back reference.
            val vifPortWithFip = storage.get(classOf[NeutronPort],
                                             vifPortId).await()
            vifPortWithFip.getFloatingIpIdsCount shouldBe 1
            vifPortWithFip.getFloatingIpIdsList should
                    contain only toProto(fipId)
        }

        // #14 Update the VIF. Test that the back reference to Floating IP
        // survives.
        val vifPortUpdatedJson = portJson(
                name = "port1Updated", id = vifPortId,
                networkId = privateNetworkId, macAddr = vifPortMac,
                fixedIps = List(IPAlloc(fixedIp, privateSubnetId.toString)),
                deviceOwner = DeviceOwner.NOVA).toString
        executeSqlStmts(insertTaskSql(
                id = 14, Update, PortType, vifPortUpdatedJson, vifPortId,
                "tx14"))
        eventually {
            val updatedVifPort = storage.get(classOf[NeutronPort], vifPortId)
                                        .await()
            updatedVifPort.getName shouldBe "port1Updated"
            updatedVifPort.getFloatingIpIdsCount shouldBe 1
            updatedVifPort.getFloatingIpIdsList should
                    contain only toProto(fipId)
        }

        // #15 Deleting a Floating IP should clear the NAT rules and ARP entry.
        executeSqlStmts(insertTaskSql(
                id = 15, Delete, FloatingIpType, json = "", fipId, "tx15"))
        eventually {
            storage.exists(classOf[FloatingIp], fipId).await() shouldBe false
            storage.exists(classOf[Rule], snatRuleId).await() shouldBe false
            storage.exists(classOf[Rule], dnatRuleId).await() shouldBe false
            val inChainNoNat = storage.get(classOf[Chain], inboundChainId)
                                      .await()
            val outChainNoNat = storage.get(classOf[Chain], outboundChainId)
                                       .await()
            inChainNoNat.getRuleIdsList should not contain dnatRuleId
            outChainNoNat.getRuleIdsList should not contain snatRuleId

            // The ARP entry must be removed.
            arpTable.containsKey(fipIp) shouldBe false
            // The back reference to Floating IP must be removed.
            val vifPortWithFipRemoved = storage.get(classOf[NeutronPort],
                                                    vifPortId).await()
            vifPortWithFipRemoved.getFloatingIpIdsCount shouldBe 0
        }

        // Test deletion of a port with which Floating IP is associated.
        // #16 Creates a VIF port.
        val vifPort2Id = UUID.randomUUID()
        val vifPort2FixedIp = "172.24.4.4"
        val vifPort2Mac = "e0:05:2d:fd:16:0b"
        val vifPort2Json = portJson(
                name = "vif_port2", id = vifPort2Id, networkId = extNetworkId,
                macAddr = vifPort2Mac,
                fixedIps = List(IPAlloc(vifPort2FixedIp, extSubnetId.toString)),
                deviceOwner = DeviceOwner.NOVA).toString
        // #17 Create a Floating IP with the VIF port specified.
        val fip2Id = UUID.randomUUID()
        val fip2Address = "118.67.101.185"
        val fip2Json = floatingIpJson(
                id = fip2Id,
                floatingNetworkId = extNetworkId,
                floatingIpAddress = fip2Address,
                routerId = tRouterId,
                portId = vifPort2Id,
                fixedIpAddress = vifPort2FixedIp).toString
        executeSqlStmts(insertTaskSql(
                id = 16, Create, PortType, vifPort2Json, vifPort2Id, "tx15"),
                        insertTaskSql(
                id = 17, Create, FloatingIpType, fip2Json, fip2Id, "tx16"))

        eventually {
            arpTable.containsKey(fip2Address) shouldBe true
        }

        // #18 Delete the VIF port with which the Floating IP is associated.
        executeSqlStmts(insertTaskSql(
                id = 18, Delete, PortType, json = "", vifPort2Id, "tx17"))
        eventually {
            // The ARP table entry needs to be removed.
            arpTable.containsKey(fip2Address) shouldBe false
        }

        arpTable.stop()
    }
}