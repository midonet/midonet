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
import org.midonet.cluster.data.neutron.NeutronResourceType.{FloatingIp => FloatingIpType, Port => PortType, PortBinding => PortBindingType}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronPort}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.RouterTranslator.tenantGwPortId
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.state.Ip4ToMacReplicatedMap
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

    private def createFipPort(taskId: Int, nwId: UUID, snId: UUID,
                              fipId: UUID, ipAddr: String, mac: String,
                              id: UUID = UUID.randomUUID()): UUID = {
        val json = portJson(id, nwId, macAddr = mac,
                            fixedIps = Seq(IPAlloc(ipAddr, snId)),
                            deviceOwner = DeviceOwner.FLOATINGIP,
                            deviceId = fipId)
        insertCreateTask(taskId, PortType, json, id)
        id
    }

    protected def createFip(taskId: Int, nwId: UUID, floatingIp: String,
                            id: UUID = UUID.randomUUID(),
                            fixedIp: String = null,
                            rtrId: UUID = null, portId: UUID = null): UUID = {
        val json = floatingIpJson(id, nwId, floatingIp,
                                  fixedIpAddress = fixedIp,
                                  routerId = rtrId, portId = portId)
        insertCreateTask(taskId, FloatingIpType, json, id)
        id
    }

    "C3PO" should "add NAT rules and ARP entry for the floating IP." in {
        // Create a private Network
        val privateNwId = createTenantNetwork(10)

        // Attach a subnet to the Network
        val privateSubnetCidr = "10.0.0.0/24"
        val gatewayIp = "10.0.0.1"
        val privateSnId = createSubnet(
            20, privateNwId, privateSubnetCidr, gatewayIp = gatewayIp)

        // Set up a host. Needs to do this directly via Zoom as the Host info
        // is to be created by the Agent.
        val hostId = createHost().getId.asJava

        // Creates a VIF port.
        val fixedIp = "10.0.0.9"
        val vifPortMac = "fa:16:3e:bf:d4:56"
        val vifPortId = createVifPort(
            30, privateNwId, mac = vifPortMac,
            fixedIps = Seq(IPAlloc(fixedIp, privateSnId)))

        // Creates a Port Binding
        val bindingId = UUID.randomUUID()
        val interfaceName = "if1"
        val bindingJson = portBindingJson(bindingId, hostId,
                                          interfaceName, vifPortId)
        insertCreateTask(40, PortBindingType, bindingJson, bindingId)
        eventually(checkPortBinding(hostId, vifPortId, interfaceName))

        // Create an external Network
        val extNetworkId = createTenantNetwork(50, external = true)

        // Attach a subnet to the external Network
        val extSubnetCidr = "172.24.4.0/24"
        val extSubnetGwIp = "172.24.4.1"
        val extSubnetId = createSubnet(60, extNetworkId, extSubnetCidr,
                                       gatewayIp = extSubnetGwIp)

        // Create a Router GW Port
        val rgwMac = "fa:16:3e:62:0d:2b"
        val rgwIp = "172.24.4.4"
        val rgwPortId = createRouterGatewayPort(70, extNetworkId, rgwIp, rgwMac,
                                                extSubnetId)

        // Create a tenant Router.
        val tRouterId = createRouter(80, gwPortId = rgwPortId)

        // Tests that the tenant Router has been hooked up with Provider Router
        // via the above-created Router Gateway port.
        eventually {
            storage.exists(classOf[Router], tRouterId).await() shouldBe true
        }

        val rgwPort = storage.get(classOf[Port], rgwPortId).await()
        rgwPort.hasPeerId shouldBe true
        val rgwPortPeer =
            storage.get(classOf[Port], rgwPort.getPeerId).await()
        rgwPortPeer.hasRouterId shouldBe true
        rgwPortPeer.getRouterId shouldBe toProto(tRouterId)

        // Create a Router Interface Port.
        val rifMac = "fa:16:3e:7d:c3:0e"
        val rifIp = "10.0.0.1"
        createRouterInterfacePort(
            90, privateNwId, privateSnId, tRouterId, rifIp, rifMac)

        // Create a legacy ReplicatedMap for the external Network ARP table.
        val arpTable = dataClient.getIp4MacMap(extNetworkId)
        eventually {
            arpTable.start()
        }

        // Create a Floating IP Port and a FloatingIP.
        val fipMac = "fa:16:3e:0e:27:1c"
        val fipIp = "172.24.4.3"
        val fipId = createFip(100, extNetworkId, fipIp)
        createFipPort(110, extNetworkId, extSubnetId, fipId, fipIp, fipMac)

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
        insertUpdateTask(120, FloatingIpType, assignedFipJson, fipId)
        eventually {
            checkFipAssociated(arpTable, fipIp, fipId, fixedIp,
                               vifPortId, tRouterId, rgwMac,
                               tenantGwPortId(toProto(rgwPortId)))
        }

        // Update the VIF. Test that the back reference to Floating IP
        // survives.
        val vifPortUpdatedJson = portJson(
                name = "port1Updated", id = vifPortId,
                networkId = privateNwId, macAddr = vifPortMac,
                fixedIps = List(IPAlloc(fixedIp, privateSnId)),
                deviceOwner = DeviceOwner.COMPUTE)
        insertUpdateTask(130, PortType, vifPortUpdatedJson, vifPortId)
        eventually {
            val nVifPort = storage.get(classOf[NeutronPort], vifPortId).await()
            nVifPort.getName shouldBe "port1Updated"
            nVifPort.getFloatingIpIdsList should contain only toProto(fipId)
        }

        // Create a second VIF port.
        val vifPort2FixedIp = "10.0.0.19"
        val vifPort2Mac = "e0:05:2d:fd:16:0b"
        val vifPort2Id = createVifPort(
            140, privateNwId, mac = vifPort2Mac,
            fixedIps = Seq(IPAlloc(vifPort2FixedIp, extSubnetId)))

        // Reassign the FIP to the new VIP port.
        val reassignedFipJson = floatingIpJson(
            id = fipId, floatingNetworkId = extNetworkId,
            floatingIpAddress = fipIp, routerId = tRouterId,
            portId = vifPort2Id, fixedIpAddress = vifPort2FixedIp)
        insertUpdateTask(150, FloatingIpType, reassignedFipJson, fipId)
        eventually {
            checkFipAssociated(arpTable, fipIp, fipId, vifPort2FixedIp,
                               vifPort2Id, tRouterId, rgwMac,
                               tenantGwPortId(toProto(rgwPortId)))
            checkNeutronPortFipBackref(vifPortId, null)
        }

        // Deleting a Floating IP should clear the NAT rules and ARP entry.
        insertDeleteTask(160, FloatingIpType, fipId)
        eventually {
            checkFipDisassociated(arpTable, fipIp, fipId, tRouterId,
                                  deleted = true)
            checkNeutronPortFipBackref(vifPort2Id, null)
        }

        // Create a second Floating IP with the first VIF port specified.
        val fip2Ip = "172.24.4.10"
        val fip2Id = createFip(170, extNetworkId, fip2Ip, fixedIp = fixedIp,
                               rtrId = tRouterId, portId = vifPortId)
        eventually {
            checkFipAssociated(arpTable, fip2Ip, fip2Id, fixedIp,
                               vifPortId, tRouterId, rgwMac,
                               tenantGwPortId(toProto(rgwPortId)))
        }

        // Delete the first VIF port, which should disassociate the second FIP.
        insertDeleteTask(180, PortType, vifPortId)
        eventually {
            checkFipDisassociated(arpTable, fip2Ip, fip2Id, tRouterId,
                                  deleted = false)
        }

        arpTable.stop()
    }

    private def checkFipAssociated(arpTable: Ip4ToMacReplicatedMap,
                                   fipAddr: String, fipId: UUID,
                                   fixedIp: String, vifPortId: UUID,
                                   rtrId: UUID, rgwMac: String,
                                   rtrPortId: Commons.UUID): Unit = {
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
        snat.getCondition.getOutPortIdsCount shouldBe 1
        snat.getCondition.getOutPortIds(0) shouldBe rtrPortId
        snat.getCondition.getNwSrcIp.getAddress shouldBe fixedIp
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
        dnat.getCondition.getInPortIdsCount shouldBe 1
        dnat.getCondition.getInPortIds(0) shouldBe rtrPortId
        dnat.getCondition.getNwDstIp.getAddress shouldBe fipAddr
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
