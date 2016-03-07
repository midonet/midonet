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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, Port => PortType, Subnet => SubnetType}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil.toProto
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.MAC
import org.midonet.packets.util.AddressConversions._
import org.midonet.util.concurrent.toFutureOps

/**
 * Provides integration tests for PortTranslator.
 */
@RunWith(classOf[JUnitRunner])
class PortTranslatorIT extends C3POMinionTestBase with ChainManager {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

    "Port translator" should " handle VIF port CRUD" in {
        // Create a network with two VIF ports.
        val nw1Id = UUID.randomUUID()
        val nw1Json = networkJson(nw1Id, "tenant", "network1")
        val (nw1p1Id, nw1p2Id) = (UUID.randomUUID(), UUID.randomUUID())
        val nw1p1Json = portJson(nw1p1Id, nw1Id)
        val nw1p2Json = portJson(nw1p2Id, nw1Id)

        insertCreateTask(2, NetworkType, nw1Json, nw1Id)
        insertCreateTask(3, PortType, nw1p1Json, nw1p1Id)
        insertCreateTask(4, PortType, nw1p2Json, nw1p2Id)

        // Check jump chain references.
        eventually {
            val inChain =
                storage.get(classOf[Chain], inChainId(nw1p1Id)).await()

            // Rule 1 is accept return flow, and rule 2 is drop non-ARP
            // traffic. We want rule 0, the jump to the anti-spoof chain.
            val antiSpoofJumpRule =
                storage.get(classOf[Rule], inChain.getRuleIds(0)).await()

            antiSpoofJumpRule.hasJumpRuleData shouldBe true

            // Get the anti-spoof chain and verify that it has the jump rule's
            // ID in its jumpRuleIds list.
            val ascId = antiSpoofJumpRule.getJumpRuleData.getJumpChainId
            val antiSpoofChain = storage.get(classOf[Chain], ascId).await()
            antiSpoofChain.getJumpRuleIdsList.asScala should
                contain only antiSpoofJumpRule.getId
        }

        // Create the host.
        val h1Id = UUID.randomUUID()
        createHost(h1Id)

        // Simulate mm-ctl binding the first port.
        eventually(bindVifPort(nw1p1Id, h1Id, "eth0"))
        eventually {
            val h = storage.get(classOf[Host], h1Id).await()
            h.getPortIdsList should contain only toProto(nw1p1Id)
        }

        // Simulate mm-ctl binding the second port.
        eventually(bindVifPort(nw1p2Id, h1Id, "eth1"))
        eventually {
            val h = storage.get(classOf[Host], h1Id).await()
            h.getPortIdsList should contain
                only(toProto(nw1p1Id), toProto(nw1p2Id))
        }

        // Update the first port. This should preserve the binding.
        val nw1p1DownJson = portJson(nw1p1Id, nw1Id, adminStateUp = false)
        insertUpdateTask(5, PortType, nw1p1DownJson, nw1p1Id)
        eventually {
            val hf = storage.get(classOf[Host], h1Id)
            val p = storage.get(classOf[Port], nw1p1Id).await()
            p.getHostId shouldBe toProto(h1Id)
            p.getInterfaceName shouldBe "eth0"
            hf.await().getPortIdsCount shouldBe 2
        }

        // Delete the second port.
        insertDeleteTask(6, PortType, nw1p2Id)
        eventually {
            val hf = storage.get(classOf[Host], h1Id)
            storage.exists(classOf[Port], nw1p2Id).await() shouldBe false
            hf.await().getPortIdsList should contain only toProto(nw1p1Id)
        }

        // Add allowed address pairs to the port. Use one with CIDR and one
        // without.
        val nw1p1WithAddrPairsJson = portJson(
            nw1p1Id, nw1Id, allowedAddrPairs = List(
                AddrPair("10.0.1.0/24", "ab:cd:ef:ab:cd:ef"),
                AddrPair("10.0.2.1", "12:34:56:12:34:56")))
        insertUpdateTask(7, PortType, nw1p1WithAddrPairsJson, nw1p1Id)
        eventually {
            val asc = storage.get(classOf[Chain],
                                  antiSpoofChainId(nw1p1Id)).await()
            asc.getRuleIdsCount shouldBe 6

            // The first rule (don't drop DHCP) and the last
            // one (drop everything) are fixed.
            val ruleIds = asc.getRuleIdsList.asScala.slice(1, 5)
            val rules = storage.getAll(classOf[Rule], ruleIds).await()
            rules(0).getAction shouldBe Action.RETURN
            rules(0).getCondition.getNwSrcIp.getAddress shouldBe "10.0.1.0"
            rules(0).getCondition.getNwSrcIp.getPrefixLength shouldBe 24
            rules(1).getAction shouldBe Action.RETURN
            rules(1).getCondition.getNwSrcIp.getAddress shouldBe "10.0.1.0"
            rules(1).getCondition.getNwSrcIp.getPrefixLength shouldBe 24
            rules(2).getAction shouldBe Action.RETURN
            rules(2).getCondition.getNwSrcIp.getAddress shouldBe "10.0.2.1"
            rules(2).getCondition.getNwSrcIp.getPrefixLength shouldBe 32
            rules(3).getAction shouldBe Action.RETURN
            rules(3).getCondition.getNwSrcIp.getAddress shouldBe "10.0.2.1"
            rules(3).getCondition.getNwSrcIp.getPrefixLength shouldBe 32
        }

        // Unbind the first port.
        unbindVifPort(nw1p1Id)
        eventually {
            val hf = storage.get(classOf[Host], h1Id)
            val p = storage.get(classOf[Port], nw1p1Id).await()
            p.hasHostId shouldBe false
            p.hasInterfaceName shouldBe false
            hf.await().getPortIdsCount shouldBe 0
        }
    }

    // TODO: Fix issue 1533982 and enable this test. Should also
    // test adding more than one port to the security group.
    it should "handle upgrades to VIF's security groups" ignore {
        val nwId = createTenantNetwork(10)
        val snId = createSubnet(20, nwId, "10.0.0.0/24")
        val sgId = createSecurityGroup(30)

        val p1Id = UUID.randomUUID()
        val p1Json = portJson(p1Id, nwId,
                              fixedIps = Seq(IPAlloc("10.0.0.3", snId)))
        insertCreateTask(40, PortType, p1Json, p1Id)
        eventually {
            val ipGrpF = storage.get(classOf[IPAddrGroup], sgId)
            storage.exists(classOf[Port], p1Id).await() shouldBe true
            ipGrpF.await().getIpAddrPortsCount shouldBe 0
        }

        // Add a security group.
        val p1v2Json = portJson(p1Id, nwId,
                                fixedIps = Seq(IPAlloc("10.0.0.3", snId)),
                                securityGroups = Seq(sgId))
        insertUpdateTask(50, PortType, p1v2Json, p1Id)
        eventually {
            val ipGrp = storage.get(classOf[IPAddrGroup], sgId).await()
            ipGrp.getIpAddrPortsCount shouldBe 1
            ipGrp.getIpAddrPorts(0).getIpAddress.getAddress shouldBe "10.0.0.3"
            ipGrp.getIpAddrPorts(0).getPortIdsCount shouldBe 1
            ipGrp.getIpAddrPorts(0).getPortIds(0).asJava shouldBe p1Id
        }

        // Remove the security group.
        insertUpdateTask(60, PortType, p1Json, p1Id)
        eventually {
            val ipGrp = storage.get(classOf[IPAddrGroup], sgId).await()
            ipGrp.getIpAddrPortsCount shouldBe 0
        }
    }

    it should "seed Network's ARP table." in {
        val nw1Id = UUID.randomUUID()
        val nw1Json = networkJson(nw1Id, "tenant", "network1")
        val sn1Id = UUID.randomUUID()
        val sn1Json = subnetJson(id = sn1Id, nw1Id, cidr = "10.0.2.0/24")
        val vifPortId = UUID.randomUUID()
        val vifPortMac = "ad:be:cf:03:14:25"
        val vifPortIp = "10.0.2.5"
        insertCreateTask(2, NetworkType, nw1Json, nw1Id)
        insertCreateTask(3, SubnetType, sn1Json, sn1Id)

        // Create a legacy ReplicatedMap for the Network ARP table.
        val arpTable = stateTableStorage.bridgeArpTable(nw1Id)
        val nw1 = eventually(storage.get(classOf[Network], nw1Id).await())
        nw1.getTenantId shouldBe "tenant"
        eventually(arpTable.start())

        arpTable.containsLocal(vifPortIp) shouldBe false

        val vifPortJson = portJson(
                vifPortId, nw1Id, macAddr = vifPortMac,
                fixedIps = List(IPAlloc(vifPortIp, sn1Id)))
        insertCreateTask(4, PortType, vifPortJson, vifPortId)
        eventually {
            arpTable.containsLocal(vifPortIp) shouldBe true
            arpTable.getLocal(vifPortIp) shouldBe MAC.fromString(vifPortMac)
        }

        // Update the port with a new fixed IP.
        val vifPortIp2 = "10.0.2.6"
        val vifPortJsonNewIp = portJson(
            vifPortId, nw1Id, macAddr = vifPortMac,
            fixedIps = List(IPAlloc(vifPortIp2, sn1Id)))
        insertUpdateTask(5, PortType, vifPortJsonNewIp, vifPortId)
        eventually {
            arpTable.containsLocal(vifPortIp) shouldBe false
            arpTable.containsLocal(vifPortIp2) shouldBe true
            arpTable.getLocal(vifPortIp2) shouldBe MAC.fromString(vifPortMac)
        }

        // Update the IP and MAC in a single update.
        val vifPortMac2 = "ad:be:cf:03:14:26"
        val vifPortJsonNewMac = portJson(
            vifPortId, nw1Id, macAddr = vifPortMac2,
            fixedIps = List(IPAlloc(vifPortIp, sn1Id)))
        insertUpdateTask(6, PortType, vifPortJsonNewMac, vifPortId)
        eventually {
            arpTable.containsLocal(vifPortIp2) shouldBe false
            arpTable.containsLocal(vifPortIp) shouldBe true
            arpTable.getLocal(vifPortIp) shouldBe MAC.fromString(vifPortMac2)
        }

        // Delete the VIF port.
        insertDeleteTask(10, PortType, vifPortId)
        eventually{
            arpTable.containsLocal(vifPortIp) shouldBe false
        }
    }

    it should "not clear subnet if one exists" in {

        val gwIp = "10.0.2.7"
        val dhcpIp = "10.0.3.7"

        val nwId = UUID.randomUUID()
        val nwJson = networkJson(nwId, "tenant", "network1")
        val snId = UUID.randomUUID()
        val snJson = subnetJson(id = snId, nwId, cidr = "10.0.2.0/24", gatewayIp = gwIp)

        insertCreateTask(2, NetworkType, nwJson, nwId)
        insertCreateTask(3, SubnetType, snJson, snId)

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            dhcp.getServerAddress.getAddress shouldBe gwIp
        }

        val p1Id = UUID.randomUUID()
        val p1Json = portJson(p1Id, nwId, deviceOwner = DeviceOwner.DHCP,
                              fixedIps = Seq(IPAlloc(dhcpIp, snId)))
        insertCreateTask(40, PortType, p1Json, p1Id)

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            dhcp.getServerAddress.getAddress shouldBe dhcpIp
        }

        insertDeleteTask(50, PortType, p1Id)

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            dhcp.getServerAddress.getAddress shouldBe gwIp
        }
    }

    private def bindVifPort(portId: UUID, hostId: UUID, ifName: String)
    : Port = {
        val port = storage.get(classOf[Port], portId).await().toBuilder
            .setHostId(hostId)
            .setInterfaceName(ifName)
            .build()
        storage.update(port)
        port
    }

    private def unbindVifPort(portId: UUID): Port = {
        val port = storage.get(classOf[Port], portId).await().toBuilder
            .clearHostId()
            .clearInterfaceName()
            .build()
        storage.update(port)
        port
    }
}
