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
import org.midonet.cluster.models.Neutron.NeutronPort.ExtraDhcpOpts
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil.toProto
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

    it should "revert DHCP's serverAddress to its gatewayIp when deleting the DhcpPort" in {

        val gwIp = "10.0.2.7"
        val dhcpIp = "10.0.3.7"

        val nwId = createTenantNetwork(10)
        val snId = createSubnet(20, nwId, "10.0.2.0/24", gatewayIp = gwIp)

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            dhcp.getServerAddress.getAddress shouldBe gwIp
        }

        val pId = createDhcpPort(30, nwId, snId, dhcpIp)

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            dhcp.getServerAddress.getAddress shouldBe dhcpIp
        }

        insertDeleteTask(40, PortType, pId)

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            dhcp.getServerAddress.getAddress shouldBe gwIp
        }
    }

    it should "clear DHCP's serverAddress when deleting the DhcpPort if subnet has no gateway IP" in {

        val dhcpIp = "10.0.3.7"

        val nwId = createTenantNetwork(10)
        val snId = createSubnet(20, nwId, "10.0.2.0/24")
        val pId = createDhcpPort(30, nwId, snId, dhcpIp)

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            dhcp.getServerAddress.getAddress shouldBe dhcpIp
        }

        insertDeleteTask(40, PortType, pId)

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            dhcp.hasServerAddress shouldBe false
        }
    }

    it should "update ip addr groups" in {
        val ip1 = "10.0.0.3"
        val ip2 = "10.0.0.4"

        val nwId = createTenantNetwork(10)
        val snId = createSubnet(20, nwId, "10.0.0.0/24")

        val sgId = createSecurityGroup(30)
        val sg2Id = createSecurityGroup(31)
        val sg3Id = createSecurityGroup(32)
        def checkAddrGrpAddrCounts(counts: Seq[Int]): Unit = {
            val iags = storage.getAll(classOf[IPAddrGroup],
                                      Seq(sgId, sg2Id, sg3Id)).await()
            iags.map(_.getIpAddrPortsCount) shouldBe counts
        }

        // Create one port in groups 1-3 and one in only 1-2.
        val pId = createVifPort(50, nwId, fixedIps = Seq(IPAlloc(ip1, snId)),
                                sgs = Seq(sgId, sg2Id, sg3Id))
        createVifPort(51, nwId, sgs = Seq(sgId, sg2Id),
                      fixedIps = Seq(IPAlloc(ip2, snId)))
        eventually(checkAddrGrpAddrCounts(Seq(2, 2, 1)))

        // Remove the first port from groups 1 and 3.
        var pJson = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip1, snId)),
                         securityGroups = Seq(sg2Id))
        insertUpdateTask(60, PortType, pJson, pId)
        eventually(checkAddrGrpAddrCounts(Seq(1, 2, 0)))

        // Re-add the first port to groups 1 and 3.
        pJson = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip1, snId)),
                         securityGroups = Seq(sgId, sg2Id, sg3Id))
        insertUpdateTask(70, PortType, pJson, pId)
        eventually(checkAddrGrpAddrCounts(Seq(2, 2, 1)))

        // Remove the first port from all groups.
        pJson = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip1, snId)),
                         securityGroups = Seq())
        insertUpdateTask(80, PortType, pJson, pId)
        eventually(checkAddrGrpAddrCounts(Seq(1, 1, 0)))
    }

    it should "update ip addr groups correctly when port security and SGs" +
              "are updated simultaneously" in  {

        val ip = "10.0.0.3"
        val nwId = createTenantNetwork(10)
        val snId = createSubnet(20, nwId, "10.0.0.0/24")
        val sgId = createSecurityGroup(30)

        def checkAddrGrpAddrCounts(counts: Int): Unit = {
            val iag = storage.get(classOf[IPAddrGroup], sgId).await()
            iag.getIpAddrPortsCount shouldBe counts
        }

        // Create a VIF port with port security enabled, and verify that there
        // is one entry in IP address group.
        val pId = createVifPort(50, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                                sgs = Seq(sgId), securityEnabled = true)
        eventually(checkAddrGrpAddrCounts(1))

        // Update the port with port security disabled and SGs removed, and
        // verify that there is no item in the ip address group.
        val pJson1 = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                              securityGroups = Seq(),
                              portSecurityEnabled = false)
        insertUpdateTask(60, PortType, pJson1, pId)
        eventually(checkAddrGrpAddrCounts(0))

        // Update the port with port security enabled and SGs added, and verify
        // that IP address group has an entry again.
        val pJson2 = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                              securityGroups = Seq(sgId),
                              portSecurityEnabled = true)
        insertUpdateTask(70, PortType, pJson2, pId)
        eventually(checkAddrGrpAddrCounts(1))
    }

    it should "update ip addr groups with same IP" in {
        val ip = "10.0.0.3"
        val nwId = createTenantNetwork(10)
        createTenantNetwork(11)

        val snId = createSubnet(20, nwId, "10.0.0.0/24")
        createSubnet(21, nwId, "10.0.0.0/24")

        val sgId = createSecurityGroup(30)
        val sg2Id = createSecurityGroup(31)
        val sg3Id = createSecurityGroup(32)

        // counts is, for sg, sg2, and sg3 respectively, the number of ports
        // for each address in the group.
        def checkAddrGroupAddrPortCounts(counts: Seq[Seq[Int]]): Unit = {
            val iags = storage.getAll(classOf[IPAddrGroup],
                                      Seq(sgId, sg2Id, sg3Id)).await()
            val actualCounts = for (iag <- iags) yield
                for (apl <- iag.getIpAddrPortsList.asScala.toSeq) yield
                    apl.getPortIdsCount
            actualCounts shouldBe counts
        }

        val pId = createVifPort(50, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                                sgs = Seq(sgId, sg2Id, sg3Id))
        createVifPort(51, nwId, sgs = Seq(sgId, sg2Id),
                      fixedIps = Seq(IPAlloc(ip, snId)))
        eventually(checkAddrGroupAddrPortCounts(Seq(Seq(2), Seq(2), Seq(1))))

        var pJson = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                             securityGroups = Seq(sg2Id))
        insertUpdateTask(60, PortType, pJson, pId)
        eventually(checkAddrGroupAddrPortCounts(Seq(Seq(1), Seq(2), Seq())))

        pJson = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                         securityGroups = Seq(sgId, sg2Id, sg3Id))
        insertUpdateTask(70, PortType, pJson, pId)
        eventually(checkAddrGroupAddrPortCounts(Seq(Seq(2), Seq(2), Seq(1))))

        pJson = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                         securityGroups = Seq())
        insertUpdateTask(80, PortType, pJson, pId)
        eventually(checkAddrGroupAddrPortCounts(Seq(Seq(1), Seq(1), Seq())))
    }

    it should "add dhcp extra opts" in {
        val ip = "10.0.0.3"
        val nwId = createTenantNetwork(10)
        createTenantNetwork(11)

        val snId = createSubnet(20, nwId, "10.0.0.0/24")
        createSubnet(21, nwId, "10.0.0.0/24")

        val opt1 = ExtraDhcpOpts.newBuilder.setOptName("12").setOptValue("A").build()
        val opt2 = ExtraDhcpOpts.newBuilder.setOptName("13").setOptValue("B").build()
        val pId = createVifPort(50, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                                extraDhcpOpts = List(opt1, opt2))

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            val eopts = dhcp.getHostsList.get(0).getExtraDhcpOptsList.asScala
            eopts map(_.getName) should contain only ("12", "13")
            eopts map(_.getValue) should contain only ("A", "B")
        }
    }

    it should "update dhcp extra opts" in {
        val ip = "10.0.0.3"
        val nwId = createTenantNetwork(10)
        createTenantNetwork(11)

        val snId = createSubnet(20, nwId, "10.0.0.0/24")
        createSubnet(21, nwId, "10.0.0.0/24")

        val opt1 = ExtraDhcpOpts.newBuilder.setOptName("12").setOptValue("A").build()
        val opt2 = ExtraDhcpOpts.newBuilder.setOptName("13").setOptValue("B").build()
        val pId = createVifPort(50, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
            extraDhcpOpts = List(opt1, opt2))

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            val eopts = dhcp.getHostsList.get(0).getExtraDhcpOptsList.asScala
            eopts map(_.getName) should contain only ("12", "13")
            eopts map(_.getValue) should contain only ("A", "B")
        }

        var pJson = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                             extraOpt = List())
        insertUpdateTask(60, PortType, pJson, pId)

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            val eopts = dhcp.getHostsList.get(0).getExtraDhcpOptsList.asScala
            eopts.size shouldBe 0
        }

        pJson = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                         extraOpt = List(opt1))
        insertUpdateTask(70, PortType, pJson, pId)

        eventually {
            val dhcp = storage.get(classOf[Dhcp], snId).await()
            val eopts = dhcp.getHostsList.get(0).getExtraDhcpOptsList.asScala
            eopts map(_.getName) should contain only ("12")
            eopts map(_.getValue) should contain only ("A")
        }
    }

    it should "update ip addr groups with updated IPs" in {
        val ip1 = "10.0.0.3"
        val ip2 = "10.0.0.4"

        val nwId = createTenantNetwork(10)
        val snId = createSubnet(20, nwId, "10.0.0.0/24")
        val sgId = createSecurityGroup(30)

        val pId = createVifPort(40, nwId, sgs = Seq(sgId),
                                fixedIps = Seq(IPAlloc(ip1, snId)))
        val p2Id = createVifPort(50, nwId, sgs = Seq(sgId),
                                 fixedIps = Seq(IPAlloc(ip2, snId)))

        eventually {
            val ipGrp = storage.get(classOf[IPAddrGroup], sgId).await()
            val ipAddrGroupList = ipGrp.getIpAddrPortsList.asScala
            val grpList = ipAddrGroupList map (_.getIpAddress.getAddress)

            grpList should contain theSameElementsAs Seq(ip1, ip2)

            val ip1Grp = ipAddrGroupList.find(_.getIpAddress.getAddress == ip1).get
            ip1Grp.getPortIdsList should contain theSameElementsAs Seq(toProto(pId))

            val ip2Grp = ipAddrGroupList.find(_.getIpAddress.getAddress == ip2).get
            ip2Grp.getPortIdsList should contain theSameElementsAs Seq(toProto(p2Id))
        }

        var pJson = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip2, snId)),
                             securityGroups = Seq(sgId))

        insertUpdateTask(60, PortType, pJson, pId)

        eventually {
            val ipGrp = storage.get(classOf[IPAddrGroup], sgId).await()
            val ipAddrGroupList = ipGrp.getIpAddrPortsList.asScala
            val grpList = ipAddrGroupList map (_.getIpAddress.getAddress)

            grpList should contain theSameElementsAs Seq(ip2)

            val ip2Grp = ipAddrGroupList.find(_.getIpAddress.getAddress == ip2).get
            ip2Grp.getPortIdsList should contain theSameElementsAs Seq(toProto(p2Id), toProto(pId))
        }

        pJson = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip1, snId)),
                         securityGroups = Seq(sgId))

        insertUpdateTask(70, PortType, pJson, pId)

        eventually {
            val ipGrp = storage.get(classOf[IPAddrGroup], sgId).await()
            val ipAddrGroupList = ipGrp.getIpAddrPortsList.asScala
            val grpList = ipAddrGroupList map (_.getIpAddress.getAddress)

            grpList should contain theSameElementsAs Seq(ip1, ip2)

            val ip1Grp = ipAddrGroupList.find(_.getIpAddress.getAddress == ip1).get
            ip1Grp.getPortIdsList should contain theSameElementsAs Seq(toProto(pId))

            val ip2Grp = ipAddrGroupList.find(_.getIpAddress.getAddress == ip2).get
            ip2Grp.getPortIdsList should contain theSameElementsAs Seq(toProto(p2Id))
        }
    }

    it should "update ip addr groups on device_owner changes" in {

        val ip = "10.0.0.3"
        val nwId = createTenantNetwork(10)
        val snId = createSubnet(20, nwId, "10.0.0.0/24")
        val sgId = createSecurityGroup(30)

        def checkAddrGrpAddrCounts(counts: Int): Unit = {
            val iag = storage.get(classOf[IPAddrGroup], sgId).await()
            iag.getIpAddrPortsCount shouldBe counts
        }

        // Create a VIF port with port security enabled, and verify that there
        // is one entry in IP address group.
        val pId = createVifPort(50, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                                sgs = Seq(sgId), securityEnabled = true)
        eventually(checkAddrGrpAddrCounts(1))

        // Update the port with device_owner ROUTER_INTERFACE,
        // which is considered "trusted", and verify that there is no item
        // in the ip address group.
        val pJson1 = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                              securityGroups = Seq(sgId),
                              portSecurityEnabled = true,
                              deviceOwner = DeviceOwner.ROUTER_INTERFACE)
        insertUpdateTask(60, PortType, pJson1, pId)
        eventually(checkAddrGrpAddrCounts(0))

        // Update the port with device_owner COMPUTE, and verify
        // that IP address group has an entry again.
        val pJson2 = portJson(pId, nwId, fixedIps = Seq(IPAlloc(ip, snId)),
                              securityGroups = Seq(sgId),
                              portSecurityEnabled = true,
                              deviceOwner = DeviceOwner.COMPUTE)
        insertUpdateTask(70, PortType, pJson2, pId)
        eventually(checkAddrGrpAddrCounts(1))
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
