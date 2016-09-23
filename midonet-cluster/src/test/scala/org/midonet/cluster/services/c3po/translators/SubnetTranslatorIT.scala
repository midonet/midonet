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
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Subnet => SubnetType}
import org.midonet.cluster.models.Neutron.NeutronSubnet
import org.midonet.cluster.models.Topology.Dhcp
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.util.concurrent.toFutureOps

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
