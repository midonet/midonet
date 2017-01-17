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
import org.midonet.cluster.data.neutron.NeutronResourceType.{Port => PortType}
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.PortManager.{routerInterfacePortGroupId, routerInterfacePortPeerId}
import org.midonet.cluster.services.c3po.translators.RouteManager.{localRouteId, metadataServiceRouteId, routerInterfaceRouteId}
import org.midonet.cluster.util.UUIDUtil.RichJavaUuid
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.packets.TunnelKeys
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class RouterInterfaceTranslatorIT extends C3POMinionTestBase with ChainManager {
    import RouterInterfaceTranslator._

    private val tenantNetworkId = UUID.randomUUID()
    private val uplinkNetworkId = UUID.randomUUID()
    private val subnetId = UUID.randomUUID()
    private val routerId = UUID.randomUUID()
    private val dhcpPortId = UUID.randomUUID()
    private val rifPortId = UUID.randomUUID()
    private val hostId = UUID.randomUUID()

    "RouterInterfaceTranslator" should "handle translation for interfaces on " +
                                       "non-edge routers." in {
        createTenantNetwork(2, tenantNetworkId)
        createSubnet(3, tenantNetworkId, "10.0.0.0/24", subnetId, "10.0.0.1")
        createDhcpPort(4, tenantNetworkId, subnetId, "10.0.0.2",
                       portId = dhcpPortId)
        createRouter(5, routerId)

        // Creating a router interface Port should result in a port being
        // created on the network.
        createRouterInterfacePort(6, tenantNetworkId,
                                  List(IPAlloc("10.0.0.1", subnetId)),
                                  routerId,
                                  "ab:cd:ef:01:02:03",
                                  id = rifPortId)
        eventually {
            val nwPort = storage.get(classOf[Port], rifPortId).await()
            nwPort.hasPeerId shouldBe false
        }

        // Add a Midonet-only port to verify fix for issue 1533629
        val mPort = Port.newBuilder
            .setId(UUIDUtil.randomUuidProto)
            .setNetworkId(UUIDUtil.toProto(tenantNetworkId))
            .build()
        storage.create(mPort)

        // Creating a RouterInterface should result on a port being created on
        // the router and linked to the network port.
        createRouterInterface(7, routerId, rifPortId, subnetId, Some(Seq(subnetId)))
        val rPort = checkRouterAndPeerPort(rifPortId, "10.0.0.1",
                                           "ab:cd:ef:01:02:03")

        rPort.getRouteIdsCount shouldBe 3
        val routes = storage.getAll(
            classOf[Route], rPort.getRouteIdsList.asScala).await()

        val rifRouteId = routerInterfaceRouteId(rPort.getId,
                                                IPAddressUtil.toProto("10.0.0.1"))
        val rifRoute = routes.find(_.getId == rifRouteId).get
        rifRoute.getSrcSubnet shouldBe IPSubnetUtil.AnyIPv4Subnet
        rifRoute.getDstSubnet.getAddress shouldBe "10.0.0.1"
        rifRoute.getDstSubnet.getPrefixLength shouldBe 24
        rifRoute.getNextHopPortId shouldBe rPort.getId

        val mdsRouteId = metadataServiceRouteId(rPort.getId,
                                                IPAddressUtil.toProto("10.0.0.1"))
        val mdsRoute = routes.find(_.getId == mdsRouteId).get
        mdsRoute.getSrcSubnet.getAddress shouldBe "10.0.0.1"
        mdsRoute.getSrcSubnet.getPrefixLength shouldBe 24
        mdsRoute.getDstSubnet shouldBe RouteManager.META_DATA_SRVC
        mdsRoute.getNextHopGateway.getAddress shouldBe "10.0.0.2"
        mdsRoute.getNextHopPortId shouldBe rPort.getId

        val localRtId = localRouteId(rPort.getId,
                                     IPAddressUtil.toProto("10.0.0.1"))
        val localRoute = routes.find(_.getId == localRtId).get
        localRoute.getSrcSubnet.getAddress shouldBe "0.0.0.0"
        localRoute.getSrcSubnet.getPrefixLength shouldBe 0
        localRoute.getDstSubnet.getAddress shouldBe
            rPort.getPortAddress.getAddress
        localRoute.getNextHop shouldBe NextHop.LOCAL
        localRoute.getNextHopPortId shouldBe rPort.getId


        // Deleting the router interface Port should delete both ports.
        insertDeleteTask(8, PortType, rifPortId)
        eventually {
            List(storage.exists(classOf[Port], rPort.getId),
                 storage.exists(classOf[Port], rPort.getPeerId),
                 storage.exists(classOf[Route], rifRouteId),
                 storage.exists(classOf[Route], mdsRouteId))
                .map(_.await()) shouldBe List(false, false, false, false)
        }
    }

    it should "handle translation for interfaces on edge routers" in {
        createUplinkNetwork(2, uplinkNetworkId)
        createSubnet(3, uplinkNetworkId, "10.0.0.0/24", subnetId)
        createDhcpPort(4, uplinkNetworkId, subnetId, "10.0.0.2",
                       portId = dhcpPortId)
        createRouter(5, routerId)

        // Creating a router interface Port on the uplink network should do
        // nothing.
        createRouterInterfacePort(
            6, uplinkNetworkId,
            List(IPAlloc("10.0.0.3", subnetId)), routerId,
            "ab:cd:ef:01:02:03",
            id = rifPortId, hostId = hostId, ifName = "eth0")
        eventually {
            storage.exists(classOf[NeutronPort], rifPortId)
                .await() shouldBe true
        }

        // Only the router should have a Midonet equivalent.
        List(storage.exists(classOf[Router], routerId),
             storage.exists(classOf[Network], uplinkNetworkId),
             storage.exists(classOf[Dhcp], subnetId),
             storage.exists(classOf[Port], dhcpPortId),
             storage.exists(classOf[Port], rifPortId))
            .map(_.await()) shouldBe List(true, false, false, false, false)

        createHost(hostId)
        createRouterInterface(7, routerId, rifPortId, subnetId)

        checkEdgeRouterInterface(rifPortId, hostId, deleteTaskId = 8)

    }

    it should "get the router correctly even if not set on the port" in {
        createTenantNetwork(2, tenantNetworkId)
        createSubnet(3, tenantNetworkId, "10.0.0.0/24", subnetId)
        createDhcpPort(4, tenantNetworkId, subnetId, "10.0.0.2",
                       portId = dhcpPortId)
        createRouter(5, routerId)

        val json = portJson(rifPortId, tenantNetworkId, null,
                            deviceOwner = DeviceOwner.ROUTER_INTERFACE,
                            macAddr = "ab:cd:ef:01:02:03",
                            fixedIps = List(IPAlloc("10.0.0.3", subnetId)),
                            hostId = hostId, ifName = null)
        insertCreateTask(6, PortType, json, rifPortId)

        // Creating a RouterInterface should result on a port being created on
        // the router and linked to the network port.
        createRouterInterface(7, routerId, rifPortId, subnetId)
        checkRouterAndPeerPort(rifPortId, "10.0.0.3", "ab:cd:ef:01:02:03")
    }

    it should "convert VIF port on tenant network to RIF port on create" in {
        createTenantNetwork(10, tenantNetworkId)
        createSubnet(20, tenantNetworkId, "10.0.0.0/24", subnetId)
        createDhcpPort(30, tenantNetworkId, subnetId, "10.0.0.2",
                       portId = dhcpPortId)
        createRouter(40, routerId)
        val sgId = createSecurityGroup(50)

        val rifMac = "ab:cd:ef:01:02:03"
        val rifIp = "10.0.0.3"

        // Create VIF port.
        val chainIds = Seq(inChainId(rifPortId.asProto),
                           outChainId(rifPortId.asProto),
                           antiSpoofChainId(rifPortId.asProto))
        val json = portJson(rifPortId, tenantNetworkId, name = null,
                            deviceOwner = DeviceOwner.COMPUTE, macAddr = rifMac,
                            fixedIps = Seq(IPAlloc(rifIp, subnetId)),
                            securityGroups = Seq(sgId))
        insertCreateTask(60, PortType, json, rifPortId)
        eventually {
            val nPortF = storage.get(classOf[NeutronPort], rifPortId)
            val dhcpF = storage.get(classOf[Dhcp], subnetId)
            val ipGrpF = storage.get(classOf[IPAddrGroup], sgId)
            val mPortF = storage.get(classOf[Port], rifPortId)

            chainIds.map(storage.exists(classOf[Chain], _))
                .map(_.await()) shouldBe Seq(true, true, true)

            val nPort = nPortF.await()
            nPort.hasDeviceOwner shouldBe true
            nPort.getDeviceOwner shouldBe DeviceOwner.COMPUTE
            nPort.hasDeviceId shouldBe false

            val dhcp = dhcpF.await()
            dhcp.getHostsCount shouldBe 1
            dhcp.getHosts(0).getMac shouldBe rifMac
            dhcp.getHosts(0).getIpAddress.getAddress shouldBe rifIp

            val ipGrp = ipGrpF.await()
            ipGrp.getIpAddrPortsCount shouldBe 1
            ipGrp.getIpAddrPorts(0).getIpAddress.getAddress shouldBe rifIp

            val mPort = mPortF.await()
            mPort.hasInboundFilterId shouldBe true
            mPort.hasOutboundFilterId shouldBe true
        }

        createRouterInterface(70, routerId, rifPortId, subnetId)
        eventually {
            val mPortF = storage.get(classOf[Port], rifPortId)
            val dhcpF = storage.get(classOf[Dhcp], subnetId)
            val ipGrpF = storage.get(classOf[IPAddrGroup], sgId)
            val nPortF = storage.get(classOf[NeutronPort], rifPortId)

            val nPort = nPortF.await()
            nPort.hasDeviceOwner shouldBe true
            nPort.getDeviceOwner shouldBe DeviceOwner.ROUTER_INTERFACE
            nPort.getDeviceId shouldBe routerId.toString

            checkRouterAndPeerPort(rifPortId, rifIp, rifMac)

            // Conversion from VIF to RIF port should delete chains.
            val mPort = mPortF.await()
            mPort.hasInboundFilterId shouldBe false
            mPort.hasOutboundFilterId shouldBe false
            chainIds.map(storage.exists(classOf[Chain], _))
                .map(_.await()) shouldBe Seq(false, false, false)

            // Should also delete DHCP host for port's IP allocation.
            val dhcp = dhcpF.await()
            dhcp.getHostsCount shouldBe 0

            // Should remove port address from IPAddrGroup.
            // TODO: This doesn't actually happen. See issue 1533982
//            val ipGrp = ipGrpF.await()
//            ipGrp.getIpAddrPortsCount shouldBe 0
        }

        // Should be able to delete the port with no error (MNA-766)
        insertDeleteTask(80, PortType, rifPortId)
        eventually {
            val rtrPortId = routerInterfacePortPeerId(rifPortId.asProto)
            List(storage.exists(classOf[Port], rifPortId),
                 storage.exists(classOf[Port], rtrPortId))
                .map(_.await()) shouldBe List(false, false)
        }
    }

    it should "convert non-RIF port on uplink network to RIF ports " +
              "on create" in {
        createUplinkNetwork(2, tenantNetworkId)
        createSubnet(3, tenantNetworkId, "10.0.0.0/24", subnetId)
        createDhcpPort(4, tenantNetworkId, subnetId, "10.0.0.2",
                       portId = dhcpPortId)
        createRouter(5, routerId)

        val json = portJson(rifPortId, tenantNetworkId, name = null,
                            deviceOwner = null, macAddr = "ab:cd:ef:01:02:03",
                            fixedIps = List(IPAlloc("10.0.0.3", subnetId)),
                            hostId = hostId, ifName = "eth0")
        insertCreateTask(6, PortType, json, rifPortId)
        eventually {
            val nPort = storage.get(classOf[NeutronPort], rifPortId).await()
            nPort.hasDeviceOwner shouldBe false
            nPort.hasDeviceId shouldBe false
        }

        createHost(hostId)
        createRouterInterface(7, routerId, rifPortId, subnetId)
        eventually {
            val nPort = storage.get(classOf[NeutronPort], rifPortId).await()
            nPort.hasDeviceOwner shouldBe true
            nPort.getDeviceOwner shouldBe DeviceOwner.ROUTER_INTERFACE
            nPort.getDeviceId shouldBe routerId.toString
        }

        checkEdgeRouterInterface(rifPortId, hostId, deleteTaskId = 8)
    }

    it should "create SNAT rules on router chains for same subnet traffic " +
              "on create" in {
        createTenantNetwork(2, tenantNetworkId)
        createSubnet(3, tenantNetworkId, "10.0.0.0/24", subnetId, "10.0.0.1")
        createRouter(4, routerId)
        val json = portJson(rifPortId, tenantNetworkId, null,
                            deviceOwner = DeviceOwner.ROUTER_INTERFACE,
                            macAddr = "ab:cd:ef:01:02:03",
                            fixedIps = List(IPAlloc("10.0.0.1", subnetId)))
        insertCreateTask(5, PortType, json, rifPortId)
        createRouterInterface(6, routerId, rifPortId, subnetId)

        // Verify that SNAT rules are created correctly
        eventually {
            val rtr = storage.get(classOf[Router], routerId).await()
            val peerId = PortManager.routerInterfacePortPeerId(
                UUIDUtil.toProto(rifPortId))
            val snatRule = storage.get(
                classOf[Rule], sameSubnetSnatRuleId(rtr.getOutboundFilterId,
                                                    peerId)).await()
            val revSnatRule = storage.get(
                classOf[Rule], sameSubnetSnatRuleId(rtr.getInboundFilterId,
                                                    peerId)).await()

            snatRule.getChainId shouldBe rtr.getOutboundFilterId
            snatRule.getAction shouldBe Rule.Action.ACCEPT
            snatRule.getType shouldBe Rule.Type.NAT_RULE
            val snatCond = snatRule.getCondition
            snatCond.getInPortIdsCount shouldBe 1
            snatCond.getInPortIds(0) shouldBe peerId
            snatCond.getOutPortIdsCount shouldBe 1
            snatCond.getOutPortIds(0) shouldBe peerId
            snatCond.getMatchForwardFlow shouldBe true
            snatCond.getNwDstIp shouldBe RouteManager.META_DATA_SRVC
            snatCond.getNwDstInv shouldBe true
            val snatNat = snatRule.getNatRuleData
            snatNat.getDnat shouldBe false
            snatNat.getNatTargetsCount shouldBe 1
            snatNat.getNatTargets(0).getNwStart.getAddress shouldBe "10.0.0.1"
            snatNat.getNatTargets(0).getNwEnd.getAddress shouldBe "10.0.0.1"

            revSnatRule.getChainId shouldBe rtr.getInboundFilterId
            revSnatRule.getAction shouldBe Rule.Action.ACCEPT
            revSnatRule.getType shouldBe Rule.Type.NAT_RULE
            val revSnatCond = revSnatRule.getCondition
            revSnatCond.getInPortIdsCount shouldBe 1
            revSnatCond.getInPortIds(0) shouldBe peerId
            revSnatCond.getMatchReturnFlow shouldBe true
            revSnatCond.getNwDstIp.getAddress shouldBe "10.0.0.1"
            val revSnatNat = revSnatRule.getNatRuleData
            revSnatNat.getDnat shouldBe false
        }
    }

    it should "set DHCP's routerIfPortId iff the router interface's IP is " +
              "the subnet's gateway IP" in {
        def checkDhcpAndMetadataRoute(rifPortId: UUID, portAddress: String)
        : Unit = eventually {
            val peerPortId = routerInterfacePortPeerId(
                UUIDUtil.toProto(rifPortId))
            val metadataRouteId = metadataServiceRouteId(
                peerPortId, IPAddressUtil.toProto(portAddress))

            val dhcp = storage.get(classOf[Dhcp], subnetId).await()
            val peerPort = storage.get(classOf[Port], peerPortId).await()

            if (dhcp.getDefaultGateway == peerPort.getPortAddress) {
                dhcp.getRouterIfPortId shouldBe peerPortId
                val route = storage.get(classOf[Route], metadataRouteId).await()
                route.getNextHopPortId shouldBe peerPortId
            } else {
                dhcp.hasRouterIfPortId shouldBe false
                storage.exists(classOf[Route], metadataRouteId)
                    .await() shouldBe false
            }
        }

        createTenantNetwork(10, tenantNetworkId)
        createSubnet(20, tenantNetworkId, "10.0.0.0/16", subnetId, "10.0.1.1")

        // Create a router interface with a different IP than the subnet's
        // gateway.
        createRouter(30, routerId)
        createRouterInterfacePort(40, tenantNetworkId,
                                  List(IPAlloc("10.0.2.1", subnetId)),
                                  routerId,
                                  "01:02:03:04:05:06",
                                  id = rifPortId)
        createRouterInterface(50, routerId, rifPortId, subnetId)

        val dhcpPortId = createDhcpPort(60, tenantNetworkId, subnetId, "10.0.0.2")
        checkDhcpAndMetadataRoute(rifPortId, "10.0.2.1")

        // Now create a router interface with the DHCP's gateway IP.
        val rtr2Id = createRouter(70)
        val rtr2IfPortId = createRouterInterfacePort(
            80, tenantNetworkId,
            List(IPAlloc("10.0.1.1", subnetId)), rtr2Id,
             "ab:ab:ab:ab:ab:ab", id = subnetId)
        createRouterInterface(90, rtr2Id, rtr2IfPortId, subnetId)
        checkDhcpAndMetadataRoute(rtr2IfPortId, "10.0.1.1")

        // Delete and recreate the DHCP port to make sure the metadata route
        // is deleted and then recreated with the right RIF port.
        insertDeleteTask(100, PortType, dhcpPortId)
        eventually {
            val mdRouteId = metadataServiceRouteId(
                routerInterfacePortPeerId(rtr2IfPortId.asProto),
                IPAddressUtil.toProto("10.0.0.1"))
            storage.exists(classOf[Route], mdRouteId).await() shouldBe false
        }

        createDhcpPort(110, tenantNetworkId, subnetId, "10.0.0.2", dhcpPortId)
        checkDhcpAndMetadataRoute(rtr2IfPortId, "10.0.0.2")
    }

    it should "handle translation for IPv6 neutron ports" in {
        createTenantNetwork(10, tenantNetworkId)
        createSubnet(20, tenantNetworkId, "2001::/64", subnetId, "2001::1")

        createRouter(30, routerId)
        createRouterInterfacePort(40, tenantNetworkId,
                                  List(IPAlloc("2001::2",subnetId)),
                                  routerId,
                                   "01:02:03:04:05:06",
                                  id = rifPortId)
        createRouterInterface(50, routerId, rifPortId, subnetId)

        // Verify that NAT64 routes and rules are created correctly.
        val routerPortId = routerInterfacePortPeerId(rifPortId.asProto)

        Thread.sleep(1000)
        //eventually {
            val routerPort = storage.get(classOf[Port], routerPortId).await()

            TunnelKeys.Fip64Type.isOfType(routerPort.getTunnelKey.toInt) shouldBe true
            routerPort.getPortSubnet(0).getAddress shouldBe "2001:0:0:0:0:0:0:2"
            routerPort.getPortSubnet(0).getPrefixLength shouldBe 64
            routerPort.getPortAddress.getAddress shouldBe "2001:0:0:0:0:0:0:2"

            val routes = storage.getAll(classOf[Route],
                                        routerPort.getRouteIdsList.asScala)
                                .await()
            routes should have size 2

            routes.head.getSrcSubnet.getAddress shouldBe "0.0.0.0"
            routes.head.getDstSubnet shouldBe RouterTranslator.Nat64Pool
            routes.head.getNextHopPortId shouldBe routerPort.getId
            routes.head.getNextHop shouldBe NextHop.FIP64

            routes(1).getSrcSubnet.getAddress shouldBe "::"
            routes(1).getDstSubnet.getAddress shouldBe "2001:0:0:0:0:0:0:2"
            routes(1).getNextHopPortId shouldBe routerPort.getId
            routes(1).getNextHop shouldBe NextHop.LOCAL
        //}
        val rules = storage.getAll(classOf[Rule],
                                   routerPort.getFipNatRuleIdsList.asScala)
            .await()
        rules should have size 1

        rules.head.getType shouldBe Rule.Type.LITERAL_RULE
        rules.head.getAction shouldBe Rule.Action.ACCEPT
        val snatCond = rules.head.getCondition()
        snatCond.getNwDstIp shouldBe RouterTranslator.Nat64Pool
        insertDeleteTask(60, PortType, rifPortId)

        eventually {
            storage.exists(classOf[Port], routerPortId).await() shouldBe false
        }
    }

    it should "handle translation for remove router interfaces of ipv4 subnet " +
              "when there are two subnets in the port" in {
        val ipv6PortIp = "1000:0:0:0:0:0:0:1"
        createTenantNetwork(2, tenantNetworkId)
        createSubnet(3, tenantNetworkId, "10.0.0.0/24", subnetId, "10.0.0.1")
        val subnet2Id = UUID.randomUUID()
        createSubnet(4, tenantNetworkId, "1000::/100", subnet2Id,
                     ipv6PortIp, ipVersion = 6)
        createDhcpPort(5, tenantNetworkId, subnetId, "10.0.0.2",
                       portId = dhcpPortId)
        createRouter(6, routerId)

        val portMac = "ab:cd:ef:01:02:03"

        // Creating a router interface Port should result in a port being
        // created on the network.
        createRouterInterfacePort(7, tenantNetworkId,
                                  List(IPAlloc("10.0.0.1", subnetId),
                                       IPAlloc(ipv6PortIp, subnet2Id)),
                                  routerId, portMac,
                                  id = rifPortId)
        eventually {
            val nwPort = storage.get(classOf[Port], rifPortId).await()
            nwPort.hasPeerId shouldBe false
        }

        // Creating a RouterInterface should result on a port being created on
        // the router and linked to the network port.
        createRouterInterface(8, routerId, rifPortId, subnetId, Some(Seq(subnetId, subnet2Id)))
        val rPort = checkRouterAndPeerPort(rifPortId, "10.0.0.1", portMac)
        rPort.getPortSubnetCount() shouldBe 2
        rPort.getFipNatRuleIdsCount() shouldBe 1
        rPort.getRouteIdsCount shouldBe 5

        updateRouterInterface(100, routerId, rifPortId, subnetId)

        // Only ipv6 subnet should stay
        val rPortUpdated = checkRouterAndPeerPort(rifPortId, ipv6PortIp, portMac)

        rPortUpdated.getPortSubnetCount() shouldBe 1
        rPortUpdated.getFipNatRuleIdsCount() shouldBe 1
        rPortUpdated.getRouteIdsCount() shouldBe 2
        val routesUpdated = storage.getAll(
            classOf[Route], rPortUpdated.getRouteIdsList.asScala).await()

        val rifUpdatedRouteId = routerInterfaceRouteId(rPort.getId,
                                                IPAddressUtil.toProto(ipv6PortIp))
        val rifRoute = routesUpdated.find(_.getId == rifUpdatedRouteId).get
        rifRoute.getSrcSubnet shouldBe IPSubnetUtil.AnyIPv4Subnet
        rifRoute.getDstSubnet.getAddress shouldBe
            RouterTranslator.Nat64Pool.getAddress
        rifRoute.getDstSubnet.getPrefixLength shouldBe 10
        rifRoute.getNextHopPortId shouldBe rPortUpdated.getId

        val mdsRouteId = metadataServiceRouteId(rPort.getId,
                                                IPAddressUtil.toProto("10.0.0.1"))
        routesUpdated.find(_.getId == mdsRouteId) shouldBe None

        val localRtId = localRouteId(rPortUpdated.getId,
                                     IPAddressUtil.toProto(ipv6PortIp))
        val localRoute = routesUpdated.find(_.getId == localRtId).get
        localRoute.getSrcSubnet.getAddress shouldBe "::"
        localRoute.getSrcSubnet.getPrefixLength shouldBe 0
        localRoute.getDstSubnet.getAddress shouldBe
            rPortUpdated.getPortAddress.getAddress
        localRoute.getNextHop shouldBe NextHop.LOCAL
        localRoute.getNextHopPortId shouldBe rPortUpdated.getId

        // Deleting the router interface Port should delete both ports.
        insertDeleteTask(8, PortType, rifPortId)
        eventually {
            List(storage.exists(classOf[Port], rPort.getId),
                 storage.exists(classOf[Port], rPort.getPeerId),
                 storage.exists(classOf[Route], rifUpdatedRouteId),
                 storage.exists(classOf[Route], mdsRouteId))
                .map(_.await()) shouldBe List(false, false, false, false)
        }
    }

    it should "handle translation for remove router interfaces of ipv6 subnet " +
              "when there are two subnets in the port" in {
        val ipv6PortIp = "1000:0:0:0:0:0:0:1"
        val ipv4PortIp = "10.0.0.1"

        createTenantNetwork(2, tenantNetworkId)
        createSubnet(3, tenantNetworkId, "10.0.0.0/24", subnetId, ipv4PortIp)
        val subnet2Id = UUID.randomUUID()
        createSubnet(4, tenantNetworkId, "1000::/100", subnet2Id,
                     ipv6PortIp, ipVersion = 6)
        createDhcpPort(5, tenantNetworkId, subnetId, "10.0.0.2",
                       portId = dhcpPortId)
        createRouter(6, routerId)

        val portMac = "ab:cd:ef:01:02:03"

        // Creating a router interface Port should result in a port being
        // created on the network.
        createRouterInterfacePort(7, tenantNetworkId,
                                  List(IPAlloc(ipv4PortIp, subnetId),
                                       IPAlloc(ipv6PortIp, subnet2Id)),
                                  routerId, portMac,
                                  id = rifPortId)
        eventually {
            val nwPort = storage.get(classOf[Port], rifPortId).await()
            nwPort.hasPeerId shouldBe false
        }

        // Creating a RouterInterface should result on a port being created on
        // the router and linked to the network port.
        createRouterInterface(8, routerId, rifPortId, subnetId,
                              Some(Seq(subnetId, subnet2Id)))
        val rPort = checkRouterAndPeerPort(rifPortId, ipv4PortIp, portMac)
        rPort.getPortSubnetCount() shouldBe 2
        rPort.getFipNatRuleIdsCount() shouldBe 1
        rPort.getRouteIdsCount shouldBe 5

        updateRouterInterface(100, routerId, rifPortId, subnet2Id)

        // Only ipv4 subnet should stay
        val rPortUpdated = checkRouterAndPeerPort(rifPortId, ipv4PortIp, portMac)

        rPortUpdated.getPortSubnetCount() shouldBe 1
        rPortUpdated.getFipNatRuleIdsCount() shouldBe 0
        rPortUpdated.getRouteIdsCount() shouldBe 3
        val routesUpdated = storage.getAll(
            classOf[Route], rPortUpdated.getRouteIdsList.asScala).await()

        val rifUpdatedRouteId = routerInterfaceRouteId(rPort.getId,
                                                       IPAddressUtil.toProto(ipv4PortIp))
        val rifRoute = routesUpdated.find(_.getId == rifUpdatedRouteId).get
        rifRoute.getSrcSubnet shouldBe IPSubnetUtil.AnyIPv4Subnet
        rifRoute.getDstSubnet.getAddress shouldBe ipv4PortIp
        rifRoute.getDstSubnet.getPrefixLength shouldBe 24
        rifRoute.getNextHopPortId shouldBe rPortUpdated.getId

        val mdsRouteId = metadataServiceRouteId(rPort.getId,
                                                IPAddressUtil.toProto(ipv4PortIp))
        val mdsRoute = routesUpdated.find(_.getId == mdsRouteId).get

        mdsRoute.getSrcSubnet.getAddress shouldBe ipv4PortIp
        mdsRoute.getSrcSubnet.getPrefixLength shouldBe 24
        mdsRoute.getDstSubnet shouldBe RouteManager.META_DATA_SRVC
        mdsRoute.getNextHopGateway.getAddress shouldBe "10.0.0.2"
        mdsRoute.getNextHopPortId shouldBe rPort.getId

        val localRtId = localRouteId(rPortUpdated.getId,
                                     IPAddressUtil.toProto(ipv4PortIp))
        val localRoute = routesUpdated.find(_.getId == localRtId).get
        localRoute.getSrcSubnet.getAddress shouldBe "0.0.0.0"
        localRoute.getSrcSubnet.getPrefixLength shouldBe 0
        localRoute.getDstSubnet.getAddress shouldBe
            rPortUpdated.getPortAddress.getAddress
        localRoute.getNextHop shouldBe NextHop.LOCAL
        localRoute.getNextHopPortId shouldBe rPortUpdated.getId

        // Deleting the router interface Port should delete both ports.
        insertDeleteTask(8, PortType, rifPortId)
        eventually {
            List(storage.exists(classOf[Port], rPort.getId),
                 storage.exists(classOf[Port], rPort.getPeerId),
                 storage.exists(classOf[Route], rifUpdatedRouteId),
                 storage.exists(classOf[Route], mdsRouteId))
                .map(_.await()) shouldBe List(false, false, false, false)
        }
    }

    private def checkRouterAndPeerPort(nwPortId: UUID, ipAddr: String,
                                       macAddr: String): Port = {
        val rPortId = routerInterfacePortPeerId(nwPortId.asProto)
        eventually {
            val Seq(nwPort, rPort) = storage.getAll(
                classOf[Port], Seq(nwPortId, rPortId)).await()
            nwPort.getPeerId shouldBe rPortId
            val pg = routerInterfacePortGroupId(routerId.asProto)
            rPort.getPortGroupIdsList should contain (pg)
            rPort.getPeerId shouldBe nwPortId.asProto

            rPort.getAdminStateUp shouldBe true
            rPort.getPortAddress.getAddress shouldBe ipAddr
            rPort.getPortMac shouldBe macAddr
            rPort.hasHostId shouldBe false
            rPort.getRouterId shouldBe routerId.asProto
            rPort
        }
    }

    private def checkEdgeRouterInterface(rifPortId: UUID, hostId: UUID,
                                         deleteTaskId: Int = 0): Unit = {
        val rPortId = routerInterfacePortPeerId(rifPortId.asProto)
        val rPort = eventually(storage.get(classOf[Port], rPortId).await())
        rPort.getAdminStateUp shouldBe true
        rPort.getHostId shouldBe hostId.asProto
        rPort.getInterfaceName shouldBe "eth0"
        rPort.getRouterId shouldBe routerId.asProto
        rPort.getPortAddress.getAddress shouldBe "10.0.0.3"
        rPort.getPortMac shouldBe "ab:cd:ef:01:02:03"

        rPort.getRouteIdsCount shouldBe 2
        val rifRoute = storage.get(classOf[Route], rPort.getRouteIds(0)).await()
        rifRoute.getId shouldBe RouteManager
            .routerInterfaceRouteId(rPort.getId, IPAddressUtil.toProto("10.0.0.3"))
        rifRoute.getNextHopPortId shouldBe rPort.getId
        rifRoute.getSrcSubnet shouldBe IPSubnetUtil.AnyIPv4Subnet
        rifRoute.getDstSubnet.getAddress shouldBe "10.0.0.3"
        rifRoute.getDstSubnet.getPrefixLength shouldBe 24

        val host = storage.get(classOf[Host], hostId).await()
        host.getPortIdsList.asScala should contain only rPortId

        if (deleteTaskId <= 0) return

        // Deleting the router interface Port should delete the router Port.
        insertDeleteTask(deleteTaskId, PortType, rifPortId)
        eventually {
            List(storage.exists(classOf[Port], rPortId),
                 storage.exists(classOf[Route], rifRoute.getId))
                .map(_.await()) shouldBe List(false, false)

            val portExistsFtr = storage.exists(classOf[Port], rPortId)
            val routeExistsFtr = storage.exists(classOf[Route], rifRoute.getId)
            val hostV2Ftr = storage.get(classOf[Host], hostId)
            val routerFtr = storage.get(classOf[Router], routerId)
            portExistsFtr.await() shouldBe false
            routeExistsFtr.await() shouldBe false
            hostV2Ftr.await().getPortIdsList.asScala shouldBe empty
            routerFtr.await().getPortIdsList.asScala shouldBe empty
        }
    }
}
