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
import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronPort}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.PortManager.routerInterfacePortPeerId
import org.midonet.cluster.services.c3po.translators.RouterTranslator.tenantGwPortId
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.{IPv4Addr, MAC}
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
        val arpTable = stateTableStorage.bridgeArpTable(extNetworkId)
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
        arpTable.containsLocal(fipIp) shouldBe false

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

    it should "select the router port whose subnet contains the FIP" in {
        // Create two external networks with subnets.
        val extNw1Id = createTenantNetwork(10, external = true)
        val extNw1SnId = createSubnet(20, extNw1Id, "10.0.0.0/24",
                                      gatewayIp = "10.0.0.1")
        val extNw2Id = createTenantNetwork(30, external = true)
        val extNw2SnId = createSubnet(40, extNw2Id, "20.0.0.0/24",
                                      gatewayIp = "20.0.0.1")

        // Create a private network with two VIF ports.
        val prvNwId = createTenantNetwork(50)
        val prvNwSnId = createSubnet(60, prvNwId, "30.0.0.0/24",
                                     gatewayIp = "30.0.0.1")
        val vifIp1 = "30.0.0.3"
        val vifIp2 = "30.0.0.4"
        val vifPort1Id = createVifPort(
            70, prvNwId, fixedIps = Seq(IPAlloc(vifIp1, prvNwSnId)))
        val vifPort2Id = createVifPort(
            80, prvNwId, fixedIps = Seq(IPAlloc(vifIp2, prvNwSnId)))

        // Create a router with a gateway to extNw1 and interfaces to the other
        // two networks.
        val rGwMac = "10:00:00:00:00:02"
        val rGwPortId = createRouterGatewayPort(90, extNw1Id, "10.0.0.2",
                                                rGwMac, extNw1SnId)
        val rtrId = createRouter(100, gwPortId = rGwPortId)
        eventually(storage.exists(classOf[Router], rtrId).await() shouldBe true)

        // Create a Midonet-only router port with the same CIDR as the interface
        // to extNw2Subnet. The translator should log a warning and ignore it
        // since it has no corresponding Neutron port.
        storage.create(Port.newBuilder
                           .setId(toProto(UUID.randomUUID()))
                           .setRouterId(rtrId)
                           .addPortSubnet(IPSubnetUtil.toProto("20.0.0.0/24"))
                           .build())

        val extNw2RifMac = "20:00:00:00:00:02"
        val extNw2RifPort = createRouterInterfacePort(
            110, extNw2Id, extNw2SnId, rtrId, "20.0.0.2", extNw2RifMac)
        createRouterInterface(120, rtrId, extNw2RifPort, extNw2SnId)

        val prvNwRifPortId = createRouterInterfacePort(
            130, prvNwId, prvNwSnId, rtrId, "30.0.0.2", "30:00:00:00:00:02")
        createRouterInterface(140, rtrId, prvNwRifPortId, prvNwSnId)

        // Wait for topology operations to finish.
        eventually {
            val prvNwRifPeerPortId =
                routerInterfacePortPeerId(toProto(prvNwRifPortId))
            storage.exists(classOf[Port], prvNwRifPeerPortId)
                .await() shouldBe true
        }

        // Create ARP tables to check ARP entries
        val extNw1ArpTable = stateTableStorage.bridgeArpTable(extNw1Id)
        val extNw2ArpTable = stateTableStorage.bridgeArpTable(extNw2Id)
        eventually(extNw1ArpTable.start())
        eventually(extNw2ArpTable.start())

        // Create a floating IP in extNw1's subnet.
        val extNw1FipIp = "10.0.0.3"
        val extNw1FipId = createFip(
            150, extNw1Id, extNw1FipIp, fixedIp = vifIp1,
            rtrId = rtrId, portId = vifPort1Id)
        eventually {
            checkFipAssociated(extNw1ArpTable, extNw1FipIp, extNw1FipId,
                               vifIp1, vifPort1Id, rtrId, rGwMac,
                               tenantGwPortId(toProto(rGwPortId)))
            checkNeutronPortFipBackref(vifPort1Id, extNw1FipId)
        }

        // Create a floating IP in extNw2's subnet.
        val extNw2FipIp = "20.0.0.3"
        val extNw2FipId = createFip(
            160, extNw2Id, extNw2FipIp, fixedIp = vifIp2,
            rtrId = rtrId, portId = vifPort2Id)
        eventually {
            val rtrPortId = routerInterfacePortPeerId(toProto(extNw2RifPort))
            checkFipAssociated(extNw2ArpTable, extNw2FipIp, extNw2FipId, vifIp2,
                               vifPort2Id, rtrId, extNw2RifMac, rtrPortId)
            checkNeutronPortFipBackref(vifPort2Id, extNw2FipId)
        }

        extNw1ArpTable.stop()
        extNw2ArpTable.stop()
    }

    it should "create a FIP on a second subnet in an ext net" in {
        // Create external network with 2 subnets
        val extNw1Id = createTenantNetwork(10, external = true)
        val extSnId1 = createSubnet(20, extNw1Id, "200.0.0.0/24",
            gatewayIp = "200.0.0.1")
        val extSnId2 = createSubnet(30, extNw1Id, "200.0.1.0/24",
            gatewayIp = "200.0.1.1")
        // Create the private network
        val prvNwId = createTenantNetwork(40)
        val prvNwSnId = createSubnet(50, prvNwId, "10.0.0.0/24",
            gatewayIp = "10.0.0.1")
        // Create the private port
        val vifIp1 = "10.0.0.3"
        val vifPort1Id = createVifPort(70, prvNwId,
            fixedIps = Seq(IPAlloc(vifIp1, prvNwSnId)))

        // Create the second private port
        val vifIp2 = "10.0.0.4"
        val vifPort2Id = createVifPort(71, prvNwId,
            fixedIps = Seq(IPAlloc(vifIp2, prvNwSnId)))

        // Hook router up to ext net
        val rGwMac = "10:00:00:00:00:02"
        val rGwPortId = createRouterGatewayPort(90, extNw1Id, "200.0.0.2",
            rGwMac, extSnId1)
        val rtrId = createRouter(100, gwPortId = rGwPortId)
        eventually(storage.exists(classOf[Router], rtrId).await() shouldBe true)
        // Hook private net up to router
        val prvNwRifPortId = createRouterInterfacePort(
            130, prvNwId, prvNwSnId, rtrId, "10.0.0.2", "30:00:00:00:00:02")
        createRouterInterface(140, rtrId, prvNwRifPortId, prvNwSnId)
        eventually {
            val prvNwRifPeerPortId =
                routerInterfacePortPeerId(toProto(prvNwRifPortId))
            storage.exists(classOf[Port], prvNwRifPeerPortId)
              .await() shouldBe true
        }

        val extNw1ArpTable = stateTableStorage.bridgeArpTable(extNw1Id)
        eventually(extNw1ArpTable.start())

        // First FIP
        val extNw1FipIp = "200.0.1.3"
        val extNw1FipId = createFip(
            160, extNw1Id, extNw1FipIp, fixedIp = vifIp1,
            rtrId = rtrId, portId = vifPort1Id)

        // Second FIP
        val extNw2FipIp = "200.0.0.3"
        val extNw2FipId = createFip(
            161, extNw1Id, extNw2FipIp, fixedIp = vifIp2,
            rtrId = rtrId, portId = vifPort2Id)

        eventually {
            checkFipAssociated(extNw1ArpTable, extNw1FipIp, extNw1FipId,
                vifIp1, vifPort1Id, rtrId, rGwMac,
                tenantGwPortId(toProto(rGwPortId)))
            checkNeutronPortFipBackref(vifPort1Id, extNw1FipId)

            checkFipAssociated(extNw1ArpTable, extNw2FipIp, extNw2FipId,
                vifIp2, vifPort2Id, rtrId, rGwMac,
                tenantGwPortId(toProto(rGwPortId)))
            checkNeutronPortFipBackref(vifPort2Id, extNw2FipId)
        }
        extNw1ArpTable.stop()
    }

    private def checkFipAssociated(arpTable: StateTable[IPv4Addr, MAC],
                                   fipAddr: String, fipId: UUID,
                                   fixedIp: String, vifPortId: UUID,
                                   rtrId: UUID, rgwMac: String,
                                   rtrPortId: Commons.UUID): Unit = {
        // External network's ARP table should contain an ARP entry
        arpTable.getLocal(fipAddr) shouldBe MAC.fromString(rgwMac)

        val snatExactRuleId = RouteManager.fipSnatExactRuleId(fipId)
        val snatRuleId = RouteManager.fipSnatRuleId(fipId)
        val dnatRuleId = RouteManager.fipDnatRuleId(fipId)
        val iChainId = inChainId(rtrId)
        val fSnatExactChainId = floatSnatExactChainId(rtrId)
        val fSnatChainId = floatSnatChainId(rtrId)

        // Tests that SNAT / DNAT rules for the floating IP have been set.
        val List(inChain, fSnatExactChain, fSnatChain) =
            storage.getAll(classOf[Chain],
            List(iChainId, fSnatExactChainId, fSnatChainId)).await()
        inChain.getRuleIdsList.asScala should contain(dnatRuleId)
        fSnatExactChain.getRuleIdsList.asScala should contain(snatExactRuleId)
        fSnatChain.getRuleIdsList.asScala should contain(snatRuleId)

        // SNAT EXACT
        val snatExact = storage.get(classOf[Rule], snatExactRuleId).await()
        snatExact.getChainId shouldBe fSnatExactChainId
        snatExact.getAction shouldBe Rule.Action.ACCEPT
        snatExact.getCondition.getOutPortIdsCount shouldBe 1
        snatExact.getCondition.getOutPortIds(0) shouldBe rtrPortId
        snatExact.getCondition.getNwSrcIp.getAddress shouldBe fixedIp
        val snatExactRule = snatExact.getNatRuleData
        snatExactRule.getDnat shouldBe false
        snatExactRule.getNatTargetsCount shouldBe 1
        val snatExactTarget = snatExactRule.getNatTargets(0)
        snatExactTarget.getNwStart.getAddress shouldBe fipAddr
        snatExactTarget.getNwEnd.getAddress shouldBe fipAddr
        snatExactTarget.getTpStart shouldBe 0
        snatExactTarget.getTpEnd shouldBe 0

        // SNAT
        val snat = storage.get(classOf[Rule], snatRuleId).await()
        snat.getChainId shouldBe fSnatChainId
        snat.getAction shouldBe Rule.Action.ACCEPT
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

    private def checkFipDisassociated(arpTable: StateTable[IPv4Addr, MAC],
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

        arpTable.containsLocal(fipAddr) shouldBe false
    }
}
