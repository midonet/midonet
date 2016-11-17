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
import org.midonet.cluster.data.neutron.NeutronResourceType.{Pool => PoolType, Port => PortType, Router => RouterType, Subnet => SubnetType}
import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.{NeutronRoute, NeutronRouter}
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.RouterTranslator.tenantGwPortId
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.toFutureOps
import PortManager._

@RunWith(classOf[JUnitRunner])
class RouterTranslatorIT extends C3POMinionTestBase with ChainManager {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

    "The RouterTranslator" should "handle router CRUD" in {
        val r1Id = UUID.randomUUID()
        val r1Json = routerJson(r1Id, name = "router1")
        insertCreateTask(2, RouterType, r1Json, r1Id)

        val r1 = eventually(storage.get(classOf[Router], r1Id).await())
        UUIDUtil.fromProto(r1.getId) shouldBe r1Id
        r1.getName shouldBe "router1"
        r1.getAdminStateUp shouldBe true
        r1.getInboundFilterId should not be null
        r1.getOutboundFilterId should not be null
        r1.getForwardChainId should not be null

        val r1Chains = getChains(r1.getInboundFilterId, r1.getOutboundFilterId)
        r1Chains.inChain.getRuleIdsCount shouldBe 0
        r1Chains.outChain.getRuleIdsCount shouldBe 3  // three jumps
        val r1FwdChain = storage.get(classOf[Chain],
                                     r1.getForwardChainId).await()
        r1FwdChain.getRuleIdsCount shouldBe 0
        val r1SnatExactChain = storage.get(classOf[Chain],
                                           floatSnatExactChainId(r1Id)).await()
        r1SnatExactChain.getRuleIdsCount shouldBe 0
        val r1SnatChain = storage.get(classOf[Chain],
                                      floatSnatChainId(r1Id)).await()
        r1SnatChain.getRuleIdsCount shouldBe 0
        val r1SkipSnatChain = storage.get(classOf[Chain],
                                          skipSnatChainId(r1Id)).await()
        r1SkipSnatChain.getRuleIdsCount shouldBe 0

        val pgId = PortManager.portGroupId(r1.getId)
        val pg = eventually(storage.get(classOf[PortGroup], pgId).await())
        pg.getName shouldBe RouterTranslator.portGroupName(pgId)
        pg.getTenantId shouldBe r1.getTenantId
        pg.getStateful shouldBe true

        val riPgId = PortManager.routerInterfacePortGroupId(r1.getId)
        val riPg = eventually(storage.get(classOf[PortGroup], riPgId).await())
        riPg.getName shouldBe
            PortManager.routerInterfacePortGroupName(r1.getId)
        riPg.getTenantId shouldBe r1.getTenantId
        riPg.getStateful shouldBe false

        val r2Id = UUID.randomUUID()
        val r2Json = routerJson(r2Id, name = "router2", adminStateUp = false)
        val r1JsonV2 = routerJson(r1Id, tenantId = "new-tenant")
        insertCreateTask(3, RouterType, r2Json, r2Id)
        insertUpdateTask(4, RouterType, r1JsonV2, r1Id)

        val r2 = eventually(storage.get(classOf[Router], r2Id).await())
        r2.getName shouldBe "router2"

        eventually {
            val r1v2 = storage.get(classOf[Router], r1Id).await()
            r1v2.getTenantId shouldBe "new-tenant"

            // Chains should be preserved.
            r1v2.getInboundFilterId shouldBe r1.getInboundFilterId
            r1v2.getOutboundFilterId shouldBe r1.getOutboundFilterId
            r1v2.getForwardChainId shouldBe r1.getForwardChainId
        }

        insertDeleteTask(5, RouterType, r1Id)
        insertDeleteTask(6, RouterType, r2Id)

        eventually {
            storage.getAll(classOf[Router]).await().size shouldBe 0
        }
    }

    it should "handle extra routes CRUD" in {

        // Create external network.
        val extNwId = createTenantNetwork(2, external = true)
        val extNwSubnetId = createSubnet(3, extNwId, "200.0.0.0/24",
                                         gatewayIp = "200.0.0.1")

        // Create a tenant network
        val nwId = createTenantNetwork(4, external = false)
        val subnetId = createSubnet(5, nwId, "192.168.1.0/24",
                                    gatewayIp = "192.168.1.1")

        // Create a router with gateway
        val extNwGwPortId =
            createRouterGatewayPort(6, extNwId, "200.0.0.2",
                                    "ab:cd:ef:00:00:03", extNwSubnetId)
        val routerId = createRouter(7, gwPortId = extNwGwPortId,
                                    enableSnat = true)

        // Create a router interface
        val rtrIfId = createRouterInterfacePort(
            8, nwId, subnetId, routerId, "192.168.1.2", "02:02:02:02:02:02")
        createRouterInterface(9, routerId, rtrIfId, subnetId)

        // Update with extra routes
        val route1 = NeutronRoute.newBuilder()
            .setDestination(IPSubnetUtil.toProto("10.0.0.0/24"))
            .setNexthop(IPAddressUtil.toProto("200.0.0.3")).build
        val route2 = NeutronRoute.newBuilder()
            .setDestination(IPSubnetUtil.toProto("10.0.1.0/24"))
            .setNexthop(IPAddressUtil.toProto("192.168.1.3")).build
        var rtrWithRoutes = routerJson(routerId, gwPortId = extNwGwPortId,
                                       enableSnat = true,
                                       routes = List(route1, route2))
        insertUpdateTask(10, RouterType, rtrWithRoutes, routerId)

        eventually {
            validateExtraRoute(routerId, route1,
                               RouterTranslator.tenantGwPortId(extNwGwPortId))
            validateExtraRoute(routerId, route2,
                               PortManager.routerInterfacePortPeerId(rtrIfId))
        }

        // Replace a route (add/remove)
        val route3 = NeutronRoute.newBuilder()
            .setDestination(IPSubnetUtil.toProto("10.0.2.0/24"))
            .setNexthop(IPAddressUtil.toProto("200.0.0.4")).build
        rtrWithRoutes = routerJson(routerId, gwPortId = extNwGwPortId,
                                   enableSnat = true,
                                   routes = List(route1, route3))
        insertUpdateTask(11, RouterType, rtrWithRoutes, routerId)

        eventually {
            validateExtraRoute(routerId, route1,
                               RouterTranslator.tenantGwPortId(extNwGwPortId))
            validateExtraRoute(routerId, route3,
                               RouterTranslator.tenantGwPortId(extNwGwPortId))

            // Validate that route2 is gone
            val rtId = RouteManager.extraRouteId(routerId, route2)
            storage.exists(classOf[Route], rtId).await() shouldBe false
        }
    }

    it should "handle router IPv4 gateway CRUD" in {

        val hostId = UUID.randomUUID()

        // Create uplink network.
        val uplNetworkId = createUplinkNetwork(2)
        val uplNwSubnetId = createSubnet(3, uplNetworkId, "10.0.0.0/16")
        createDhcpPort(4, uplNetworkId, uplNwSubnetId, "10.0.0.1")

        createHost(hostId)

        // Create edge router.
        val edgeRtrId = createRouter(5)
        val edgeRtrUplNwIfPortId = createRouterInterfacePort(
            6, uplNetworkId, uplNwSubnetId, edgeRtrId, "10.0.0.2",
            "02:02:02:02:02:02", hostId = hostId, ifName = "eth0")
        createRouterInterface(7, edgeRtrId, edgeRtrUplNwIfPortId, uplNwSubnetId)

        // Create external network.
        val extNwId = createTenantNetwork(8, external = true)
        val extNwSubnetId = createSubnet(9, extNwId, "10.0.1.0/24",
                                         gatewayIp = "10.0.1.2")
        val extNwDhcpPortId = createDhcpPort(10, extNwId, extNwSubnetId,
                                             "10.0.1.0")

        val edgeRtrExtNwIfPortId = createRouterInterfacePort(
            11, extNwId, extNwSubnetId, edgeRtrId,
            "10.0.1.2", "03:03:03:03:03:03")
        createRouterInterface(12, edgeRtrId,
                              edgeRtrExtNwIfPortId, extNwSubnetId)
        val extNwArpTable = stateTableStorage.bridgeArpTable(extNwId)

        // Sanity check for external network's connection to edge router. This
        // is just a normal router interface, so RouterInterfaceTranslatorIT
        // checks the details.
        eventually {
            val nwPort = storage.get(classOf[Port],
                                     edgeRtrExtNwIfPortId).await()
            val rPortF = storage.get(classOf[Port], nwPort.getPeerId)
            val rtrF = storage.get(classOf[Router], edgeRtrId)
            val (rPort, rtr) = (rPortF.await(), rtrF.await())
            rPort.getRouterId shouldBe rtr.getId
            rPort.getPortAddress.getAddress shouldBe "10.0.1.2"
            rPort.getPortMac shouldBe "03:03:03:03:03:03"
            rtr.getPortIdsCount shouldBe 2
            rtr.getPortIdsList.asScala should contain(rPort.getId)
            // Start the replicated map here.
            extNwArpTable.start()
        }

        // Create tenant router and check gateway setup.
        val extNwGwPortId = createRouterGatewayPort(
            13, extNwId, "10.0.1.3", "ab:cd:ef:00:00:03", extNwSubnetId)
        val tntRtrId = createRouter(14, gwPortId = extNwGwPortId,
                                    enableSnat = true)
        validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24", "10.0.1.3",
                        "ab:cd:ef:00:00:03", "10.0.1.2", snatEnabled = true,
                        extNwArpTable)

        // Rename router to make sure update doesn't break anything.
        val trRenamedJson = routerJson(tntRtrId, name = "tr-renamed",
                                       gwPortId = extNwGwPortId,
                                       enableSnat = true)
        insertUpdateTask(15, RouterType, trRenamedJson, tntRtrId)
        eventually {
            val tr = storage.get(classOf[Router], tntRtrId).await()
            tr.getName shouldBe "tr-renamed"
            tr.getPortIdsCount shouldBe 1
            validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24", "10.0.1.3",
                            "ab:cd:ef:00:00:03", "10.0.1.2", snatEnabled = true,
                            extNwArpTable)
        }

        // Delete gateway.
        insertDeleteTask(16, PortType, extNwGwPortId)
        eventually {
            val tr = storage.get(classOf[Router], tntRtrId).await()
            val extNw = storage.get(classOf[Network], extNwId).await()
            val nRouter = storage.get(classOf[NeutronRouter], tntRtrId).await()
            tr.getPortIdsCount shouldBe 0
            extNw.getPortIdsList.asScala should contain only (
                UUIDUtil.toProto(edgeRtrExtNwIfPortId),
                UUIDUtil.toProto(extNwDhcpPortId))
            extNwArpTable.containsLocal(IPv4Addr("10.0.1.3")) shouldBe false
            validateNatRulesDeleted(tntRtrId)
            nRouter.hasGwPortId shouldBe false
        }

        // Re-add gateway.
        createRouterGatewayPort(17, extNwId, "10.0.1.4", "ab:cd:ef:00:00:04",
                                extNwSubnetId, id = extNwGwPortId)
        val trAddGwJson = routerJson(tntRtrId, name = "tr-add-gw",
                                     gwPortId = extNwGwPortId,
                                     enableSnat = true)
        insertUpdateTask(18, RouterType, trAddGwJson, tntRtrId)
        Thread.sleep(1000)
        validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04", "10.0.1.2",
                                   snatEnabled = true, extNwArpTable)

        // Disable SNAT.
        val trDisableSnatJson = routerJson(tntRtrId, name = "tr-disable-snat",
                                           gwPortId = extNwGwPortId,
                                           enableSnat = false)
        insertUpdateTask(19, RouterType, trDisableSnatJson, tntRtrId)
        eventually(validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04", "10.0.1.2",
                                   snatEnabled = false, extNwArpTable))

        // Re-enable SNAT.
        insertUpdateTask(20, RouterType, trAddGwJson, tntRtrId)
        eventually(validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04", "10.0.1.2",
                                   snatEnabled = true, extNwArpTable))

        // Clear the default gateway on the subnet.
        val extNwSubnetNoGwJson =
            subnetJson(extNwSubnetId, extNwId, cidr = "10.0.1.0/24")
        insertUpdateTask(21, SubnetType, extNwSubnetNoGwJson, extNwSubnetId)
        eventually(validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04", null,
                                   snatEnabled = true, extNwArpTable))

        // Read the default gateway on the subnet.
        val extNwSubnetNewGwJson =
            subnetJson(extNwSubnetId, extNwId, cidr = "10.0.1.0/24",
                       gatewayIp = "10.0.1.50")
        insertUpdateTask(22, SubnetType, extNwSubnetNewGwJson, extNwSubnetId)
        eventually(validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04",
                                   "10.0.1.50", snatEnabled = true,
                                   extNwArpTable))

        // Delete the subnet altogether.
        insertDeleteTask(23, SubnetType, extNwSubnetId)
        eventually(validateGateway(tntRtrId, extNwGwPortId, "10.0.1.0/24",
                                   "10.0.1.4", "ab:cd:ef:00:00:04", null,
                                   snatEnabled = true, extNwArpTable,
                                   gwRouteExists = false))

        // Delete gateway and router.
        insertDeleteTask(24, PortType, extNwGwPortId)
        insertDeleteTask(25, RouterType, tntRtrId)
        eventually {
            val extNwF = storage.get(classOf[Network], extNwId)
            List(storage.exists(classOf[Router], tntRtrId),
                 storage.exists(classOf[Port], extNwGwPortId),
                 storage.exists(classOf[Port], tenantGwPortId(extNwGwPortId)))
                .map(_.await()) shouldBe List(false, false, false)
            val extNw = extNwF.await()
            extNw.getPortIdsList.asScala should contain only (
                UUIDUtil.toProto(edgeRtrExtNwIfPortId),
                UUIDUtil.toProto(extNwDhcpPortId))
            extNwArpTable.containsLocal(IPv4Addr("10.0.1.4")) shouldBe false
        }
        extNwArpTable.stop()
    }

    it should "handle router IPv6 gateway CRUD" in {
        val hostId = UUID.randomUUID()

        // Create uplink network.
        val uplinkNetworkId = createUplinkNetwork(2)
        val uplinkSubnetId = createSubnet(3, uplinkNetworkId, "2001::/64",
                                          ipVersion = 6)

        createHost(hostId)

        // Create edge router.
        val edgeRouterId = createRouter(4)
        val uplinkPortId = createRouterInterfacePort(
            5, uplinkNetworkId, uplinkSubnetId, edgeRouterId, "2001::1",
            "02:02:02:02:02:02", hostId = hostId, ifName = "eth0")
        createRouterInterface(6, edgeRouterId, uplinkPortId, uplinkSubnetId)

        // Create external network.
        val extNetworkId = createTenantNetwork(7, external = true)
        val extSubnetId = createSubnet(
            8, extNetworkId, "2002::/64", gatewayIp = "2002::1",
            ipVersion = 6)

        val extPortId = createRouterInterfacePort(
            9, extNetworkId, extSubnetId, edgeRouterId,
            "2002::2", "03:03:03:03:03:03")
        createRouterInterface(10, edgeRouterId, extPortId, extSubnetId)

        // Create tenant router.
        val gwPortId = createRouterGatewayPort(
            11, extNetworkId, "2002::2", "04:04:04:04:04:04", extSubnetId)
        val tenantRouterId = createRouter(12, gwPortId = gwPortId)

        // Sanity check for external network's connection to edge router. This
        // is just a normal router interface, so RouterInterfaceTranslatorIT
        // checks the details.
        val mnUplinkPortId = routerInterfacePortPeerId(uplinkPortId).asJava
        val mnExtPortId = routerInterfacePortPeerId(extPortId).asJava
        val mnGwPortId = tenantGwPortId(gwPortId).asJava

        eventually {
            storage.exists(classOf[Port], mnUplinkPortId).await() shouldBe true
            storage.exists(classOf[Port], mnExtPortId).await() shouldBe true
            storage.exists(classOf[Port], mnGwPortId).await() shouldBe true
        }

        val uplinkPort = storage.get(classOf[Port], mnUplinkPortId).await()
        val uplinkPortRoutes =
            storage.getAll(classOf[Route], uplinkPort.getRouteIdsList).await()

        val extPort = storage.get(classOf[Port], mnExtPortId).await()
        val extPortRoutes =
            storage.getAll(classOf[Route], extPort.getRouteIdsList).await()

        var gwPort: Port = null
        var gwPortRoutes: Seq[Route] = null
        var gwPortFipRules: Seq[Rule] = null

        def verifyRouterPortCreated(): Unit = {
            eventually {
                storage.exists(classOf[Port], mnGwPortId).await() shouldBe true
            }

            gwPort = storage.get(classOf[Port], mnGwPortId).await()
            gwPortRoutes =
                storage.getAll(classOf[Route], gwPort.getRouteIdsList).await()
            gwPortFipRules =
                storage.getAll(classOf[Rule], gwPort.getFipNatRuleIdsList).await()

            gwPort.getRouterId.asJava shouldBe tenantRouterId
            gwPort.getPortMac shouldBe "04:04:04:04:04:04"
            gwPort.getPortAddress shouldBe IPAddressUtil.toProto("169.254.0.1")
            gwPort.getPortSubnet shouldBe IPSubnetUtil.toProto("169.254.0.1/30")

            gwPort.getRouteIdsCount shouldBe 2
            gwPort.getFipNatRuleIdsCount shouldBe 1

            gwPortRoutes should have size 2
            gwPortFipRules should have size 1
        }

        uplinkPort.getRouterId.asJava shouldBe edgeRouterId
        uplinkPort.getHostId.asJava shouldBe hostId
        uplinkPort.getInterfaceName shouldBe "eth0"
        uplinkPort.getPortMac shouldBe "02:02:02:02:02:02"
        uplinkPort.getPortAddress shouldBe IPAddressUtil.toProto("2001::1")
        uplinkPort.getPortSubnet shouldBe IPSubnetUtil.toProto("2001:0:0:0:0:0:0:0/64")

        // TODO: These routes should not be added on the IPv6 port
        uplinkPortRoutes should have size 2

        extPort.getRouterId.asJava shouldBe edgeRouterId
        extPort.getPortMac shouldBe "03:03:03:03:03:03"

        // TODO: This should be changed for ports that are not gateway ports
        extPort.getPortAddress shouldBe IPAddressUtil.toProto("169.254.0.1")
        extPort.getPortSubnet shouldBe IPSubnetUtil.toProto("169.254.0.1/30")
        extPort.getFipNatRuleIdsCount should not be 0

        // TODO: These routes should not be added on the IPv6 port
        extPortRoutes should have size 2

        verifyRouterPortCreated()

        gwPortRoutes.head.getSrcSubnet shouldBe IPSubnetUtil.toProto("0.0.0.0/0")
        gwPortRoutes.head.getDstSubnet shouldBe RouterInterfaceTranslator.Nat64Pool

        gwPortRoutes(1).getSrcSubnet shouldBe IPSubnetUtil.toProto("0.0.0.0/0")
        gwPortRoutes(1).getDstSubnet shouldBe IPSubnetUtil.toProto("169.254.0.1/32")

        gwPortFipRules.head.getNat64RuleData.getPortAddress shouldBe IPSubnetUtil
            .toProto("2002::2/128")
        gwPortFipRules.head.getNat64RuleData.getNatPool.getNwStart shouldBe IPAddressUtil
            .toProto("20.0.0.1")
        gwPortFipRules.head.getNat64RuleData.getNatPool.getNwEnd shouldBe IPAddressUtil
            .toProto("20.0.0.1")

        // Delete gateway port.
        insertDeleteTask(13, PortType, gwPortId)

        def verifyRouterPortDeleted(): Unit = {
            eventually {
                storage.exists(classOf[Port], mnGwPortId).await() shouldBe false
            }
            storage.exists(classOf[Rule], gwPortFipRules.head.getId).await() shouldBe false
        }
        verifyRouterPortDeleted()

        // Re-create the gateway port.
        createRouterGatewayPort(
            14, extNetworkId, "2002::2", "04:04:04:04:04:04", extSubnetId,
            id = gwPortId)
        insertUpdateTask(15, RouterType, routerJson(
            tenantRouterId, name = "tenant", gwPortId = gwPortId), tenantRouterId)

        verifyRouterPortCreated()

        // Remove the gateway port from the router without deleting the port.
        insertUpdateTask(16, RouterType, routerJson(
            tenantRouterId, name = "tenant"), tenantRouterId)

        verifyRouterPortDeleted()

        // Re-add the gateway port.
        insertUpdateTask(17, RouterType, routerJson(
            tenantRouterId, name = "tenant", gwPortId = gwPortId), tenantRouterId)

        verifyRouterPortCreated()

        // Delete the gateway port.
        insertDeleteTask(18, PortType, gwPortId)

        verifyRouterPortDeleted()

        // Re-create the gateway port.
        createRouterGatewayPort(
            19, extNetworkId, "2002::2", "04:04:04:04:04:04", extSubnetId,
            id = gwPortId)
        insertUpdateTask(20, RouterType, routerJson(
            tenantRouterId, name = "tenant", gwPortId = gwPortId), tenantRouterId)

        verifyRouterPortCreated()

        // TODO: Support IPv6 in SubnetTranslator.update
        // Clear the default gateway on the subnet.
        // var extSubnet =
        //    subnetJson(extSubnetId, extNetworkId, cidr = "2002::/64",
        //               ipVersion = 6)
        // insertUpdateTask(21, SubnetType, extSubnet, extSubnetId)

        // TODO: Support IPv6 in SubnetTranslator.update
        // Set the default gateway on the subnet
        // var extSubnet =
        //    subnetJson(extSubnetId, extNetworkId, cidr = "2002::/64",
        //               gatewayIp = "2002::5", ipVersion = 6)
        // insertUpdateTask(21, SubnetType, extSubnet, extSubnetId)


        // TODO: Support IPv6 in SubnetTranslator.update
        // Update the default gateway on the subnet
        // var extSubnet =
        //    subnetJson(extSubnetId, extNetworkId, cidr = "2002::/64",
        //               gatewayIp = "2002::5", ipVersion = 6)
        // insertUpdateTask(21, SubnetType, extSubnet, extSubnetId)

        // TODO: Support IPv6 in SubnetTranslator.delete
        // Delete the subnet
        // insertDeleteTask(23, SubnetType, extSubnetId)


        // Delete gateway and router.
        insertDeleteTask(24, PortType, gwPortId)
        insertDeleteTask(25, RouterType, tenantRouterId)
        verifyRouterPortDeleted()
    }


    it should "Preserve router properties on update" in {
        // Create external network and subnet.
        val nwId = createTenantNetwork(10, external = true)
        val snId = createSubnet(20, nwId, "10.0.1.0/24", gatewayIp = "10.0.1.1")

        // Create router with gateway port.
        val gwPortId = createRouterGatewayPort(
            30, nwId, "10.0.1.2", "ab:ab:ab:ab:ab:ab", snId)
        val rtrId = createRouter(40, gwPortId = gwPortId)

        // Create pool to give router load balancer ID.
        val lbPoolId = UUID.randomUUID()
        val lbPoolJson = poolJson(lbPoolId, rtrId)
        insertCreateTask(50, PoolType, lbPoolJson, lbPoolId)

        // Make sure everything was created.
        val rtr = eventually {
            val rtr = storage.get(classOf[Router], rtrId).await()
            rtr.hasLoadBalancerId shouldBe true
            rtr
        }

        // Add a route.
        val routeId = UUID.randomUUID()
        storage.create(Route.newBuilder
                           .setId(toProto(routeId))
                           .setRouterId(toProto(rtrId))
                           .build())

        // Add mirrors.
        val inMirrorId = UUID.randomUUID()
        storage.create(Mirror.newBuilder
                           .setId(toProto(inMirrorId))
                           .addRouterInboundIds(toProto(rtrId))
                           .build())
        val outMirrorId = UUID.randomUUID()
        storage.create(Mirror.newBuilder
                           .setId(toProto(outMirrorId))
                           .addRouterOutboundIds(toProto(rtrId))
                           .build())

        // Add BGP network and peer.
        val bgpNwId = UUID.randomUUID()
        storage.create(BgpNetwork.newBuilder
                           .setId(toProto(bgpNwId))
                           .setRouterId(toProto(rtrId))
                           .build())
        val bgpPeerId = UUID.randomUUID()
        storage.create(BgpPeer.newBuilder
                           .setId(toProto(bgpPeerId))
                           .setRouterId(toProto(rtrId))
                           .build())

        // Add trace request.
        val trqId = UUID.randomUUID()
        storage.create(TraceRequest.newBuilder
                           .setId(toProto(trqId))
                           .setRouterId(toProto(rtrId))
                           .build())

        // Now update the router and make sure the references are preserved.
        val rtrJsonV2 = routerJson(rtrId, name = "routerV2",
                                   gwPortId = gwPortId)
        insertUpdateTask(60, RouterType, rtrJsonV2, rtrId)
        eventually {
            val rtrV2 = storage.get(classOf[Router], rtrId).await()
            rtrV2.getId shouldBe toProto(rtrId)
            rtrV2.getName shouldBe "routerV2"
            rtrV2.getInboundFilterId shouldBe rtr.getInboundFilterId
            rtrV2.getOutboundFilterId shouldBe rtr.getOutboundFilterId
            rtrV2.getForwardChainId shouldBe rtr.getForwardChainId
            rtrV2.getLoadBalancerId shouldBe rtr.getLoadBalancerId
            rtrV2.getRouteIdsList should contain only toProto(routeId)
            rtrV2.getBgpNetworkIdsList should contain only toProto(bgpNwId)
            rtrV2.getBgpPeerIdsList should contain only toProto(bgpPeerId)
            rtrV2.getInboundMirrorIdsList should contain only toProto(inMirrorId)
            rtrV2.getOutboundMirrorIdsList should contain only toProto(outMirrorId)
            rtrV2.getPortIdsList shouldBe rtr.getPortIdsList
            rtrV2.getTraceRequestIdsList should contain only toProto(trqId)
        }

    }

    private def validateExtraRoute(rtrId: UUID,
                                   rt: NeutronRoute,
                                   nextHopPortId: UUID): Unit = {
        val rtId = RouteManager.extraRouteId(rtrId, rt)
        val route = storage.get(classOf[Route], rtId).await()
        route.getNextHopPortId shouldBe UUIDUtil.toProto(nextHopPortId)
        route.getDstSubnet shouldBe rt.getDestination
        route.getNextHopGateway shouldBe rt.getNexthop
    }

    private def validateGateway(rtrId: UUID, nwGwPortId: UUID,
                                extSubnetCidr: String, gatewayIp: String,
                                trPortMac: String, nextHopIp: String,
                                snatEnabled: Boolean,
                                extNwArpTable: StateTable[IPv4Addr, MAC],
                                gwRouteExists: Boolean=true)
    : Unit = {
        // Tenant router should have gateway port and no routes.
        val trGwPortId = tenantGwPortId(nwGwPortId)

        Thread.sleep(1000)
        //val tr = eventually {
            val tr = storage.get(classOf[Router], rtrId).await()
            tr.getPortIdsList.asScala should contain only trGwPortId
            tr.getRouteIdsCount shouldBe 0

            // The ARP entry should be added to the external network ARP table.
            extNwArpTable.getLocal(gatewayIp) shouldBe MAC.fromString(trPortMac)
        //    tr
        //}

        // Get the router gateway port and its peer on the network.
        val portFs = storage.getAll(classOf[Port], List(nwGwPortId, trGwPortId))

        // Get routes on router gateway port.
        val trRifRtId = RouteManager.routerInterfaceRouteId(trGwPortId)
        val trLocalRtId = RouteManager.localRouteId(trGwPortId)
        val trGwRtId = RouteManager.gatewayRouteId(trGwPortId)

        val List(nwGwPort, trGwPort) = portFs.await()

        val trLocalRt =
            storage.get(classOf[Route], trLocalRtId).await()

        // Check router port has correct router and route IDs.  This also
        // validates that there is no network route created for the gw port.
        trGwPort.getRouterId shouldBe UUIDUtil.toProto(rtrId)
        trGwPort.getRouteIdsList.asScala should
          contain only(trGwRtId, trRifRtId, trLocalRtId)

        // Network port has no routes.
        nwGwPort.getRouteIdsCount shouldBe 0

        // Ports should be linked.
        nwGwPort.getPeerId shouldBe trGwPortId
        trGwPort.getPeerId shouldBe nwGwPort.getId

        trGwPort.getPortAddress.getAddress shouldBe gatewayIp
        trGwPort.getPortMac shouldBe trPortMac

        validateLocalRoute(trLocalRt, trGwPort)

        if (gwRouteExists) {
            val trGwRt =
                storage.get(classOf[Route], trGwRtId).await()

            trGwRt.getNextHop shouldBe NextHop.PORT
            trGwRt.getNextHopPortId shouldBe trGwPort.getId
            trGwRt.getDstSubnet shouldBe IPSubnetUtil.AnyIPv4Subnet
            trGwRt.getSrcSubnet shouldBe IPSubnetUtil.AnyIPv4Subnet
            if (nextHopIp == null) trGwRt.hasNextHopGateway shouldBe false
            else trGwRt.getNextHopGateway.getAddress shouldBe nextHopIp

        } else {
            storage.exists(classOf[Route], trGwRtId).await() shouldBe false
        }

        if (snatEnabled)
            validateGatewayNatRules(tr, gatewayIp, trGwPortId)
    }

    private def validateLocalRoute(rt: Route, nextHopPort: Port): Unit = {
        rt.getNextHop shouldBe NextHop.LOCAL
        rt.getNextHopPortId shouldBe nextHopPort.getId
        rt.getDstSubnet shouldBe
        IPSubnetUtil.fromAddress(nextHopPort.getPortAddress)
        rt.getSrcSubnet shouldBe IPSubnetUtil.AnyIPv4Subnet
        rt.getWeight shouldBe RouteManager.DEFAULT_WEIGHT
    }

    private def validateGatewayNatRules(tr: Router, gatewayIp: String,
                                        trGwPortId: Commons.UUID): Unit = {
        val chainIds = List(tr.getInboundFilterId, tr.getOutboundFilterId)
        val List(inChain, outChain) =
            storage.getAll(classOf[Chain], chainIds).await()
        inChain.getRuleIdsCount shouldBe 2
        outChain.getRuleIdsCount shouldBe 6

        val ruleIds = inChain.getRuleIdsList.asScala.toList ++
                      outChain.getRuleIdsList.asScala
        val List(inRevSnatRule, sameSubnetRev,
                 jump1, jump2, jump3, outSnatRule, dstRewrittenSnatRule,
                 sameSubnet) =
            storage.getAll(classOf[Rule], ruleIds).await()

        val gwSubnet = IPSubnetUtil.fromAddress(gatewayIp)

        outSnatRule.getChainId shouldBe outChain.getId
        outSnatRule.getCondition.getOutPortIdsList.asScala should contain only trGwPortId
        outSnatRule.getCondition.getNwSrcIp shouldBe gwSubnet
        outSnatRule.getCondition.getNwSrcInv shouldBe true
        validateNatRule(outSnatRule, dnat = false, gatewayIp)

        dstRewrittenSnatRule.getChainId shouldBe outChain.getId
        dstRewrittenSnatRule.getCondition.getMatchNwDstRewritten shouldBe true
        validateNatRule(dstRewrittenSnatRule, dnat = false, gatewayIp)

        inRevSnatRule.getChainId shouldBe inChain.getId
        inRevSnatRule.getCondition.getNwDstIp shouldBe gwSubnet
        validateNatRule(inRevSnatRule, dnat = false, addr = null)
    }

    // addr == null for reverse NAT rule
    private def validateNatRule(r: Rule, dnat: Boolean, addr: String): Unit = {
        r.getType shouldBe Rule.Type.NAT_RULE
        r.getAction shouldBe Rule.Action.ACCEPT

        val data = r.getNatRuleData
        data.getDnat shouldBe dnat
        data.getReverse shouldBe (addr == null)

        if (addr != null) {
            data.getNatTargetsCount shouldBe 1
            data.getNatTargets(0).getTpStart shouldBe 1024
            data.getNatTargets(0).getTpEnd shouldBe 0xffff
            data.getNatTargets(0).getNwStart.getAddress shouldBe addr
            data.getNatTargets(0).getNwEnd.getAddress shouldBe addr
        }
    }

    private def validateNatRulesDeleted(rtrId: UUID): Unit = {
        import RouterTranslator._
        val r = storage.get(classOf[Router], rtrId).await()
        val Seq(inChain, outChain) = storage.getAll(
            classOf[Chain],
            Seq(r.getInboundFilterId, r.getOutboundFilterId)).await()
        inChain.getRuleIdsCount shouldBe 0
        outChain.getRuleIdsCount shouldBe 3  // three jumps
        Seq(outSnatRuleId(rtrId), dstRewrittenSnatRuleId(rtrId),
            skipSnatGwPortRuleId(rtrId), inReverseSnatRuleId(rtrId))
            .map(storage.exists(classOf[Rule], _).await()) shouldBe
            Seq(false, false, false, false)
    }
}
