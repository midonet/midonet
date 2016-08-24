/*
 * Copyright 2016 Midokura SARL
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
import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Subnet => SubnetType}
import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronSubnet}
import org.midonet.cluster.models.Topology.{Dhcp, Route, Router, Port}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps
import org.scalatest.junit.JUnitRunner

/**
 * Tests basic Neutron Subnet translation.
 */
@RunWith(classOf[JUnitRunner])
class SubnetTranslatorIT extends C3POMinionTestBase {
    override protected val useLegacyDataClient = true

    it should "produce an equivalent Dhcp Object" in {
        val cidr = "10.0.0.0/24"
        val netId = createTenantNetwork(10)
        val subId = createSubnet(20, netId, cidr)
        checkDhcp(subId, netId, cidr)
    }

    it should "set the default gateway and server address accordingly" in {
        val gwIp = "10.0.0.10"
        val cidr = "10.0.0.0/24"
        val netId = createTenantNetwork(10)
        val subId = createSubnet(20, netId, cidr, gatewayIp = gwIp)
        checkDhcp(subId, netId, cidr, gwIp)
    }

    it should "set the DNS server address to the DNS server IP" in {
        val cidr = "10.0.0.0/24"
        val dnsServer = "10.0.0.50"
        val netId = createTenantNetwork(10)
        val subId = createSubnet(20, netId, cidr, dnsServers = List(dnsServer))
        checkDhcp(subId, netId, cidr, dnsServers = List(dnsServer))
    }

    it should "set the Opt121 routes" in {
        val cidr = "10.0.0.0/24"
        val netId = createTenantNetwork(10)
        val hostRoutes = List(HostRoute("20.20.20.0/24", "10.0.0.13"),
                              HostRoute("20.20.30.0/24", "10.0.0.14"))
        val subId = createSubnet(20, netId, cidr, hostRoutes = hostRoutes)
        checkDhcp(subId, netId, cidr, hostRoutes = hostRoutes)
    }

    it should "not create a dhcp for uplink nets" in {
        val cidr = "10.0.0.0/24"
        val netId = createTenantNetwork(10, uplink = true)
        val subId = createSubnet(20, netId, cidr)
        checkNoDhcp(subId)
    }

    it should "not update a dhcp for uplink nets" in {
        val cidr = "10.0.0.0/24"
        val netId = createTenantNetwork(10, uplink = true)
        val subId = createSubnet(20, netId, cidr)

        checkNoDhcp(subId)
        val subJson = subnetJson(subId, netId, cidr)
        insertUpdateTask(30, SubnetType, subJson, subId)
        checkNoDhcp(subId)
    }

    it should "delete dhcp entry when deleting subnet" in {
        val cidr = "10.0.0.0/24"
        val netId = createTenantNetwork(10)
        val subId = createSubnet(20, netId, cidr)
        checkDhcp(subId, netId, cidr)
        insertDeleteTask(30, SubnetType, subId)
        checkNoDhcp(subId, nSubExists = false)
    }

    it should "update opt121 routes" in {
        val gwIp = "10.0.0.10"
        val cidr = "10.0.0.0/24"
        val netId = createTenantNetwork(10)
        val subId = createSubnet(20, netId, cidr)
        checkDhcp(subId, netId, cidr)

        val hostRoutes = List(HostRoute("20.20.20.0/24", "10.0.0.13"),
                              HostRoute("20.20.30.0/24", "10.0.0.14"))
        val subJson = subnetJson(subId, netId, cidr = cidr, gatewayIp = gwIp,
                                 hostRoutes = hostRoutes)

        insertUpdateTask(30, SubnetType, subJson, subId)
        checkDhcp(subId, netId, cidr, gwIp = gwIp, hostRoutes = hostRoutes)
    }

    it should "update subnet list in the neutron network" in {
        val cidr = "10.0.0.0/24"
        val netId = createTenantNetwork(10)
        val subId = createSubnet(20, netId, cidr)
        checkDhcp(subId, netId, cidr)

        eventually {
            val net = storage.get(classOf[NeutronNetwork], netId).await()
            net.getSubnetsCount shouldBe 1
            net.getSubnetsList.get(0) shouldBe toProto(subId)
        }

        insertDeleteTask(30, SubnetType, subId)
        checkNoDhcp(subId, nSubExists = false)
        eventually {
            val net = storage.get(classOf[NeutronNetwork], netId).await()
            net.getSubnetsCount shouldBe 0
        }
    }

    it should "add multiple routes if multiple subnets" in {
        val cidr = "10.0.0.0/24"
        val cidr2 = "20.0.0.0/24"
        val rPortIp = "10.0.0.1"
        val mac = "ab:cd:ef:01:02:03"
        val netId = createTenantNetwork(10)

        val rId = createRouter(30)

        val subId = createSubnet(20, netId, cidr)
        checkDhcp(subId, netId, cidr)

        val rifId = createRouterInterfacePort(
            40, netId, subId, rId, rPortIp, mac)

        createSubnet(45, netId, cidr2)
        checkDhcp(subId, netId, cidr)

        createRouterInterface(50, rId, rifId, subId)

        checkPortRoutes(rId, rPortIp, Seq(cidr, cidr2))
    }

    it should "delete extra routes if multiple subnets" in {
        val cidr = "10.0.0.0/24"
        val cidr2 = "20.0.0.0/24"
        val rPortIp = "10.0.0.1"
        val mac = "ab:cd:ef:01:02:03"
        val netId = createTenantNetwork(10)

        val rId = createRouter(30)

        val subId = createSubnet(20, netId, cidr)
        checkDhcp(subId, netId, cidr)

        val rifId = createRouterInterfacePort(
            40, netId, subId, rId, rPortIp, mac)

        val subId2 = createSubnet(45, netId, cidr2)
        checkDhcp(subId, netId, cidr)

        createRouterInterface(50, rId, rifId, subId)

        checkPortRoutes(rId, rPortIp, Seq(cidr, cidr2))

        insertDeleteTask(60, SubnetType, subId2)

        checkPortRoutes(rId, rPortIp, Seq(cidr))
    }

    it should "only delete extra routes related to net" in {

        val rId = createRouter(10)

        val cidr = "10.0.0.0/24"
        val netId = createTenantNetwork(20)
        val subId = createSubnet(30, netId, cidr)
        checkDhcp(subId, netId, cidr)

        val rPortIp = "10.0.0.1"
        val mac = "ab:cd:ef:01:02:03"
        val rifId = createRouterInterfacePort(
            40, netId, subId, rId, rPortIp, mac)
        createRouterInterface(50, rId, rifId, subId)

        checkPortRoutes(rId, rPortIp, Seq(cidr))

        val cidr2 = "20.0.0.0/24"
        val netId2 = createTenantNetwork(60)
        val subId2 = createSubnet(70, netId2, cidr2)
        checkDhcp(subId2, netId2, cidr2)

        val rPortIp2 = "20.0.0.1"
        val mac2 = "ab:cd:ef:01:02:04"
        val rifId2 = createRouterInterfacePort(
            80, netId2, subId2, rId, rPortIp2, mac2)
        createRouterInterface(90, rId, rifId2, subId2)

        checkPortRoutes(rId, rPortIp, Seq(cidr))
        checkPortRoutes(rId, rPortIp2, Seq(cidr2))

        val rId2 = createRouter(100)

        val rPortIp3 = "10.0.0.2"
        val mac3 = "ab:cd:ef:01:02:07"
        val rifId3 = createRouterInterfacePort(
            110, netId, subId, rId2, rPortIp3, mac3)
        createRouterInterface(120, rId2, rifId3, subId)

        checkPortRoutes(rId, rPortIp, Seq(cidr))
        checkPortRoutes(rId, rPortIp2, Seq(cidr2))
        checkPortRoutes(rId2, rPortIp3, Seq(cidr))

        val rPortIp4 = "20.0.0.2"
        val mac4 = "ab:cd:ef:01:04:07"
        val rifId4 = createRouterInterfacePort(
            130, netId2, subId2, rId2, rPortIp4, mac4)
        createRouterInterface(140, rId2, rifId4, subId2)

        checkPortRoutes(rId, rPortIp, Seq(cidr))
        checkPortRoutes(rId, rPortIp2, Seq(cidr2))
        checkPortRoutes(rId2, rPortIp3, Seq(cidr))
        checkPortRoutes(rId2, rPortIp4, Seq(cidr2))

        val cidr3 = "30.0.0.0/24"
        val subId3 = createSubnet(150, netId2, cidr3)
        checkDhcp(subId3, netId2, cidr3)

        checkPortRoutes(rId, rPortIp, Seq(cidr))
        checkPortRoutes(rId, rPortIp2, Seq(cidr2, cidr3))
        checkPortRoutes(rId2, rPortIp3, Seq(cidr))
        checkPortRoutes(rId2, rPortIp4, Seq(cidr2, cidr3))

        insertDeleteTask(160, SubnetType, subId3)

        checkPortRoutes(rId, rPortIp, Seq(cidr))
        checkPortRoutes(rId, rPortIp2, Seq(cidr2))
        checkPortRoutes(rId2, rPortIp3, Seq(cidr))
        checkPortRoutes(rId2, rPortIp4, Seq(cidr2))
    }

    private def checkPortRoutes(rId: UUID, portIp: String,
                                cidrs: Seq[String]): Unit = {
        eventually {
            val router = storage.get(classOf[Router], rId).await()
            val ports = storage.getAll(classOf[Port], router.getPortIdsList).await()
            ports.nonEmpty shouldBe true
            val port = ports.filter(p => p.getPortAddress.getAddress == portIp).head

            val routes = storage.getAll(classOf[Route], port.getRouteIdsList).await()
            val portCidrs = for {
                r <- routes if r.getDstSubnet.getPrefixLength < 32
            } yield {
                val rSub = r.getDstSubnet
                s"${rSub.getAddress}/${rSub.getPrefixLength}"
            }
            portCidrs should contain theSameElementsAs cidrs
        }
    }

    private def checkNoDhcp(subId: UUID, nSubExists: Boolean = true): Unit = {
        eventually {
            storage.exists(classOf[NeutronSubnet], subId).await() shouldBe nSubExists
            storage.exists(classOf[Dhcp], subId).await() shouldBe false
        }
    }

    private def checkDhcp(subId: UUID, netId: UUID, cidr: String,
                          gwIp: String = null,
                          dnsServers: List[String] = null,
                          hostRoutes: List[HostRoute] = null): Unit = {
        eventually {
            val dhcp = storage.get(classOf[Dhcp], subId).await()
            dhcp.getNetworkId shouldBe toProto(netId)
            dhcp.getSubnetAddress shouldBe IPSubnetUtil.toProto(cidr)

            if (gwIp != null) {
                dhcp.getDefaultGateway shouldBe IPAddressUtil.toProto(gwIp)
            } else {
                dhcp.hasDefaultGateway shouldBe false
            }

            if (dnsServers != null) {
                val dns = dhcp.getDnsServerAddressList.asScala map (_.getAddress)
                dns should contain theSameElementsAs dnsServers
            } else {
                dhcp.getDnsServerAddressCount shouldBe 0
            }

            if (hostRoutes != null) {
                val dhcpHR = dhcp.getOpt121RoutesList.asScala map { o =>
                    val gwAddr = o.getGateway.getAddress
                    val dstSub = o.getDstSubnet.getAddress + "/" +
                                 o.getDstSubnet.getPrefixLength
                    HostRoute(dstSub, gwAddr)
                }
                dhcpHR should contain theSameElementsAs hostRoutes
            } else {
                dhcp.getOpt121RoutesCount shouldBe 0
            }
        }
    }
}
