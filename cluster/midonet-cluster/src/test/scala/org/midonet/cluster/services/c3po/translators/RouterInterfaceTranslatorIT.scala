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
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType, Port => PortType, Router => RouterType, RouterInterface => RouterInterfaceType, Subnet => SubnetType}
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner.ROUTER_INTERFACE
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.PortManager.routerInterfacePortPeerId
import org.midonet.cluster.services.c3po.translators.RouteManager.{metadataServiceRouteId, routerInterfaceRouteId}
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil.asRichJavaUuid
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class RouterInterfaceTranslatorIT extends C3POMinionTestBase {
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
        createSubnet(3, subnetId, tenantNetworkId, "10.0.0.0/24")
        createDhcpPort(4, dhcpPortId, tenantNetworkId, subnetId, "10.0.0.2")
        createRouter(5, routerId)

        // Creating a router interface Port should result in a port being
        // created on the network.
        createRouterInterfacePort(6, rifPortId, tenantNetworkId, routerId,
                                  "10.0.0.3", "ab:cd:ef:01:02:03", subnetId)
        eventually {
            val nwPort = storage.get(classOf[Port], rifPortId).await()
            nwPort.hasPeerId shouldBe false
        }

        // Creating a RouterInterface should result on a port being created on
        // the router and linked to the network port.
        createRouterInterface(7, routerId, rifPortId, subnetId)
        val rPeerPortId = routerInterfacePortPeerId(rifPortId.asProto)
        val rPort = eventually {
            val Seq(nwPort, rPeerPort) = storage.getAll(
                classOf[Port], Seq(rifPortId, rPeerPortId)).await()
            nwPort.getPeerId shouldBe rPeerPort.getId
            rPeerPort.getPeerId shouldBe nwPort.getId
            rPeerPort
        }

        rPort.getAdminStateUp shouldBe true
        rPort.getPortAddress.getAddress shouldBe "10.0.0.3"
        rPort.hasHostId shouldBe false
        rPort.getRouterId shouldBe routerId.asProto

        rPort.getRouteIdsCount shouldBe 2
        val routes = storage.getAll(
            classOf[Route], rPort.getRouteIdsList.asScala).await()

        val rifRouteId = routerInterfaceRouteId(rPort.getId)
        val rifRoute = routes.find(_.getId == rifRouteId).get
        rifRoute.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4
        rifRoute.getDstSubnet.getAddress shouldBe "10.0.0.0"
        rifRoute.getDstSubnet.getPrefixLength shouldBe 24
        rifRoute.getNextHopPortId shouldBe rPeerPortId

        val mdsRouteId = metadataServiceRouteId(rPort.getId)
        val mdsRoute = routes.find(_.getId == mdsRouteId).get
        mdsRoute.getSrcSubnet.getAddress shouldBe "10.0.0.0"
        mdsRoute.getSrcSubnet.getPrefixLength shouldBe 24
        mdsRoute.getDstSubnet shouldBe RouteManager.META_DATA_SRVC
        mdsRoute.getNextHopGateway.getAddress shouldBe "10.0.0.2"
        mdsRoute.getNextHopPortId shouldBe rPeerPortId

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

    "RouterInterfaceTranslator" should "handle translation for interfaces on " +
                                       "edge routers" in {
        createUplinkNetwork(2, uplinkNetworkId)
        createSubnet(3, subnetId, uplinkNetworkId, "10.0.0.0/24")
        createDhcpPort(4, dhcpPortId, uplinkNetworkId, subnetId, "10.0.0.2")
        createRouter(5, routerId)

        // Creating a router interface Port on the uplink network should do
        // nothing.
        createRouterInterfacePort(6, rifPortId, uplinkNetworkId, routerId,
                                  "10.0.0.3", "ab:cd:ef:01:02:03", subnetId,
                                  hostId = hostId, ifName = "eth0")
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
        val rPortId = routerInterfacePortPeerId(rifPortId.asProto)
        val rPort = eventually(storage.get(classOf[Port], rPortId).await())
        rPort.getAdminStateUp shouldBe true
        rPort.getHostId shouldBe hostId.asProto
        rPort.getInterfaceName shouldBe "eth0"
        rPort.getRouterId shouldBe routerId.asProto

        rPort.getRouteIdsCount shouldBe 1
        val rifRoute = storage.get(classOf[Route], rPort.getRouteIds(0)).await()
        rifRoute.getId shouldBe RouteManager.routerInterfaceRouteId(rPort.getId)
        rifRoute.getNextHopPortId shouldBe rPort.getId
        rifRoute.getSrcSubnet shouldBe IPSubnetUtil.univSubnet4
        rifRoute.getDstSubnet.getAddress shouldBe "10.0.0.0"
        rifRoute.getDstSubnet.getPrefixLength shouldBe 24

        val host = storage.get(classOf[Host], hostId).await()
        host.getPortIdsList.asScala should contain only rPortId

        // Deleting the router interface Port should delete the router Port.
        insertDeleteTask(8, PortType, rifPortId)
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
