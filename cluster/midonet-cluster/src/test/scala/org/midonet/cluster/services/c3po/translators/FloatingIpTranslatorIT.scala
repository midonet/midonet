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
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronPort}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.state.Ip4ToMacReplicatedMap
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.{IPv4Subnet, MAC}
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
            networkJson(privateNetworkId, "tenant1", "private net")
        insertCreateTask(2, NetworkType, privateNetworkJson, privateNetworkId)

        // Attach a subnet to the Network
        val privateSubnetId = UUID.randomUUID()
        val privateSubnetCidr = "10.0.0.0/24"
        val gatewayIp = "10.0.0.1"
        val snetJson = subnetJson(
                privateSubnetId, privateNetworkId, name = "private subnet",
                cidr = privateSubnetCidr, gatewayIp = gatewayIp)
        insertCreateTask(3, SubnetType, snetJson, privateSubnetId)
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
                                   deviceOwner = DeviceOwner.COMPUTE)
        insertCreateTask(4, PortType, vifPortJson, vifPortId)
        eventually {
            storage.exists(classOf[Port], vifPortId).await() shouldBe true
        }

        // Creates a Port Binding
        val bindingId = UUID.randomUUID()
        val interfaceName = "if1"
        val bindingJson = portBindingJson(bindingId, hostId,
                                          interfaceName, vifPortId)
        insertCreateTask(5, PortBindingType, bindingJson, bindingId)
        eventually(checkPortBinding(hostId, vifPortId, interfaceName))

        // Create an external Network
        val extNetworkId = UUID.randomUUID()
        val extNetworkJson = networkJson(extNetworkId, "admin",
                                         "public", external = true)
        insertCreateTask(6, NetworkType, extNetworkJson, extNetworkId)
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
                                       gatewayIp = extSubnetGwIp)
        insertCreateTask(7, SubnetType, extSubnetJson, extSubnetId)
        eventually {
            storage.exists(classOf[Dhcp], extSubnetId).await() shouldBe true
        }

        // Create a Router GW Port
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
        insertCreateTask(8, PortType, rgwPortJson, rgwPortId)

        // Create a tenant Router.
        val tRouterId = UUID.randomUUID()
        val tRouterJson = routerJson(tRouterId, gwPortId = rgwPortId)
        insertCreateTask(9, RouterType, tRouterJson, tRouterId)

        // Tests that the tenant Router has been hooked up with Provider Router
        // via the above-created Router Gateway port.
        eventually {
            storage.exists(classOf[Router], tRouterId).await() shouldBe true
        }
        val rgwPort = storage.get(classOf[Port], rgwPortId).await()
        rgwPort.hasPeerId shouldBe true
        val rgwPortPeer = storage.get(classOf[Port], rgwPort.getPeerId).await()
        rgwPortPeer.hasRouterId shouldBe true
        rgwPortPeer.getRouterId shouldBe toProto(tRouterId)

        // Create a Router Interface Port.
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
                                   deviceId = tRouterId)
        insertCreateTask(10, PortType, rifPortJson, rifPortUuid)
        eventually {
            storage.exists(classOf[Port], rifPortUuid).await() shouldBe true
        }

        // Create a legacy ReplicatedMap for the external Network ARP table.
        val arpTable = dataClient.getIp4MacMap(extNetworkId)
        eventually {
            arpTable.start()
        }

        // Create a Floating IP Port and a FloatingIP in a same
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
                                   deviceId = fipId)
        val fipJson = floatingIpJson(id = fipId,
                                     floatingNetworkId = extNetworkId,
                                     floatingIpAddress = fipIp)
        // Floating IP is not assigned to any port yet.
        insertCreateTask(11, PortType, fipPortJson, fipPortId)
        insertCreateTask(12, FloatingIpType, fipJson, fipId)

        val fip = eventually(storage.get(classOf[FloatingIp], fipId).await())
        fip.getFloatingIpAddress shouldBe IPAddressUtil.toProto(fipIp)
        // The ARP table should NOT YET contain the ARP entry.
        arpTable.containsKey(fipIp) shouldBe false

        // Update the Floating IP with a port to assign to.
        val assignedFipJson = floatingIpJson(id = fipId,
                                             floatingNetworkId = extNetworkId,
                                             floatingIpAddress = fipIp,
                                             routerId = tRouterId,
                                             portId = vifPortId,
                                             fixedIpAddress = fixedIp)
        insertUpdateTask(13, FloatingIpType, assignedFipJson, fipId)
        eventually {
            checkFipAssociated(arpTable, fipIp, fipId, fixedIp,
                               vifPortId, tRouterId, rgwMac, rgwPort)
        }

        // Update the VIF. Test that the back reference to Floating IP
        // survives.
        val vifPortUpdatedJson = portJson(
                name = "port1Updated", id = vifPortId,
                networkId = privateNetworkId, macAddr = vifPortMac,
                fixedIps = List(IPAlloc(fixedIp, privateSubnetId.toString)),
                deviceOwner = DeviceOwner.COMPUTE)
        insertUpdateTask(14, PortType, vifPortUpdatedJson, vifPortId)
        eventually {
            val nVifPort = storage.get(classOf[NeutronPort], vifPortId).await()
            nVifPort.getName shouldBe "port1Updated"
            nVifPort.getFloatingIpIdsList should contain only toProto(fipId)
        }

        // Create a second VIF port.
        val vifPort2Id = UUID.randomUUID()
        val vifPort2FixedIp = "10.0.0.19"
        val vifPort2Mac = "e0:05:2d:fd:16:0b"
        val vifPort2Json = portJson(
            name = "vif_port2", id = vifPort2Id,
            networkId = privateNetworkId, macAddr = vifPort2Mac,
            fixedIps = List(IPAlloc(vifPort2FixedIp, extSubnetId.toString)),
            deviceOwner = DeviceOwner.COMPUTE)

        // Reassign the FIP to the new VIP port.
        val reassignedFipJson = floatingIpJson(
            id = fipId, floatingNetworkId = extNetworkId,
            floatingIpAddress = fipIp, routerId = tRouterId,
            portId = vifPort2Id, fixedIpAddress = vifPort2FixedIp)
        insertCreateTask(15, PortType, vifPort2Json, vifPort2Id)
        insertUpdateTask(16, FloatingIpType, reassignedFipJson, fipId)
        eventually {
            checkFipAssociated(arpTable, fipIp, fipId, vifPort2FixedIp,
                               vifPort2Id, tRouterId, rgwMac, rgwPort)
            checkNeutronPortFipBackref(vifPortId, null)
        }

        // Deleting a Floating IP should clear the NAT rules and ARP entry.
        insertDeleteTask(17, FloatingIpType, fipId)
        eventually {
            checkFipDisassociated(arpTable, fipIp, fipId, tRouterId,
                                  deleted = true)
            checkNeutronPortFipBackref(vifPort2Id, null)
        }

        // Create a second Floating IP with the first VIF port specified.
        val fip2Id = UUID.randomUUID()
        val fip2Address = "118.67.101.185"
        val fip2Json = floatingIpJson(
                id = fip2Id,
                floatingNetworkId = extNetworkId,
                floatingIpAddress = fip2Address,
                routerId = tRouterId,
                portId = vifPortId,
                fixedIpAddress = fixedIp)
        insertCreateTask(18, FloatingIpType, fip2Json, fip2Id)
        eventually {
            checkFipAssociated(arpTable, fip2Address, fip2Id, fixedIp,
                               vifPortId, tRouterId, rgwMac, rgwPort)
        }

        // Delete the first VIF port, which should disassociate the second FIP.
        insertDeleteTask(19, PortType, vifPortId)
        eventually {
            checkFipDisassociated(arpTable, fip2Address, fip2Id, tRouterId,
                                  deleted = false)
        }

        arpTable.stop()
    }

    private def checkFipAssociated(arpTable: Ip4ToMacReplicatedMap,
                                   fipAddr: String, fipId: UUID,
                                   fixedIp: String, vifPortId: UUID,
                                   rtrId: UUID, rgwMac: String, rgwPort: Port)
    : Unit = {
        import org.midonet.cluster.util.UUIDUtil.toProto
        // External network's ARP table should contain an ARP entry
        arpTable.get(fipAddr) shouldBe MAC.fromString(rgwMac)

        val snatRuleId = RouteManager.fipSnatRuleId(fipId)
        val dnatRuleId = RouteManager.fipDnatRuleId(fipId)
        val iChainId = inChainId(rtrId)
        val oChainId = outChainId(rtrId)

        // Tests that SNAT / DNAT rules for the floating IP have been set.
        val List(inChain, outChain) =
            storage.getAll(classOf[Chain], List(iChainId, oChainId)).await()
        inChain.getRuleIdsList.asScala should contain(dnatRuleId)
        outChain.getRuleIdsList.asScala should contain(snatRuleId)

        // SNAT
        val snat = storage.get(classOf[Rule], snatRuleId).await()
        snat.getChainId shouldBe oChainId
        snat.getAction shouldBe Rule.Action.ACCEPT
        snat.getOutPortIdsCount shouldBe 1
        snat.getOutPortIds(0) shouldBe toProto(rgwPort.getPeerId)
        snat.getNwSrcIp.getAddress shouldBe fixedIp
        val snatRule = snat.getNatRuleData
        snatRule.getDnat shouldBe false
        snatRule.getNatTargetsCount shouldBe 1
        val snatTarget = snatRule.getNatTargets(0)
        snatTarget.getNwStart.getAddress shouldBe fipAddr
        snatTarget.getNwEnd.getAddress shouldBe fipAddr
        snatTarget.getTpStart shouldBe 0
        snatTarget.getTpEnd shouldBe 0

        // DNAT
        val dnat = storage.get(classOf[Rule], dnatRuleId).await()
        dnat.getChainId shouldBe iChainId
        dnat.getAction shouldBe Rule.Action.ACCEPT
        dnat.getInPortIdsCount shouldBe 1
        dnat.getInPortIds(0) shouldBe toProto(rgwPort.getPeerId)
        dnat.getNwDstIp.getAddress shouldBe fipAddr
        val dnatRule = dnat.getNatRuleData
        dnatRule.getDnat shouldBe true
        dnatRule.getNatTargetsCount shouldBe 1
        val dnatTarget = dnatRule.getNatTargets(0)
        dnatTarget.getNwStart.getAddress shouldBe fixedIp
        dnatTarget.getNwEnd.getAddress shouldBe fixedIp
        dnatTarget.getTpStart shouldBe 0
        dnatTarget.getTpEnd shouldBe 0

        // Neutron VIF port should be updated with the backreference.
        checkNeutronPortFipBackref(vifPortId, fipId)
    }

    private def checkNeutronPortFipBackref(portId: UUID, fipId: UUID): Unit = {
        val nPort = storage.get(classOf[NeutronPort], portId).await()
        if (fipId == null) nPort.getFloatingIpIdsCount shouldBe 0
        else nPort.getFloatingIpIdsList should contain only toProto(fipId)
    }

    private def checkFipDisassociated(arpTable: Ip4ToMacReplicatedMap,
                                      fipAddr: String, fipId: UUID,
                                      rtrId: UUID, deleted: Boolean): Unit = {
        // NAT rules should be deleted, and FIP iff deleted == true
        val snatRuleId = RouteManager.fipSnatRuleId(fipId)
        val dnatRuleId = RouteManager.fipDnatRuleId(fipId)
        Seq(storage.exists(classOf[Rule], snatRuleId),
            storage.exists(classOf[Rule], dnatRuleId),
            storage.exists(classOf[FloatingIp], fipId))
            .map(_.await()) shouldBe Seq(false, false, !deleted)

        val iChainId = inChainId(rtrId)
        val oChainId = outChainId(rtrId)
        val List(inChain, outChain) =
            storage.getAll(classOf[Chain], List(iChainId, oChainId)).await()
        inChain.getRuleIdsList should not contain dnatRuleId
        outChain.getRuleIdsList should not contain snatRuleId

        arpTable.containsKey(fipAddr) shouldBe false
    }
}
