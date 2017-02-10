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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronSubnet}
import org.midonet.cluster.models.Topology.{Route, Dhcp}
import org.midonet.cluster.services.c3po.C3POStorageManager._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._
import org.midonet.cluster.util.IPSubnetUtil.richProtoIPSubnet
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}

/**
 * Tests basic Neutron Subnet translation.
 */
@RunWith(classOf[JUnitRunner])
class SubnetTranslatorTest extends TranslatorTestBase {
    protected var translator: SubnetTranslator = _

    before {
        initMockStorage()
        translator = new SubnetTranslator(storage)
    }

    private val subnetId = randomUuidProto
    private val networkId = randomUuidProto
    private val tenantId = "tenant"
    private val nSubnet = nSubnetFromTxt(s"""
        id { $subnetId }
        network_id { $networkId }
        tenant_id: "tenant1"
        """)
    private val mDhcp = mDhcpFromTxt(s"""
        id { $subnetId }
        network_id { $networkId }
        """)
    private val nTenantNetwork = nNetworkFromTxt(s"""
        id { $networkId }
        """)
    private val nUplinkNetwork = nNetworkFromTxt(s"""
        $nTenantNetwork
        network_type: UPLINK
        """)
    private val mTenantNetwork = mNetworkFromTxt(s"""
        id { $networkId }
        """)

    "Basic subnet CREATE" should "produce an equivalent Dhcp Object" in {
        bind(networkId, nTenantNetwork)
        translator.translate(transaction, Create(nSubnet))
        midoOps should contain only Create(mDhcp)
    }

    private val gatewayIp = IPAddressUtil.toProto("10.0.0.1")
    private val gatewaySubnet = IPSubnetUtil.fromAddr(gatewayIp, 24)
    private val gatewaySubnetCidr = gatewaySubnet.asJava.toString

    private val gatewayRouteId1 = randomUuidProto
    private val gatewayRouteId2 = randomUuidProto

    private val nSubnetWithGatewayIp = nSubnetFromTxt(s"""
        $nSubnet
        cidr { $gatewaySubnet }
        ip_version: 4
        gateway_ip { $gatewayIp }
        enable_dhcp: true
        """)
    private val mDhcpWithDefaultGateway = mDhcpFromTxt(s"""
        $mDhcp
        default_gateway { $gatewayIp }
        server_address { $gatewayIp }
        subnet_address { $gatewaySubnet }
        enabled: true
        """)
    "CREATE subnet with a gateway IP" should "set the default gateway and " +
    "server address accordingly" in {
        bind(networkId, nTenantNetwork)
        translator.translate(transaction,
                             Create(nSubnetWithGatewayIp))
        midoOps should contain only Create(mDhcpWithDefaultGateway)
    }

    private val dnsServerIp1 = IPAddressUtil.toProto("10.0.0.2")
    private val dnsServerIp2 = IPAddressUtil.toProto("10.0.0.3")
    private val nSubnetWithDNS  = nSubnetFromTxt(s"""
        $nSubnet
        dns_nameservers { $dnsServerIp1 }
        dns_nameservers { $dnsServerIp2 }
        enable_dhcp: true
        """)
    private val mDhcpWithDNS  = mDhcpFromTxt(s"""
        $mDhcp
        dns_server_address { $dnsServerIp1 }
        dns_server_address { $dnsServerIp2 }
        enabled : true
        """)

    "CREATE subnet with DNS name server IPs" should "set the DNS server " +
    "addresses accordingly" in {
        bind(networkId, nTenantNetwork)
        translator.translate(transaction, Create(nSubnetWithDNS))
        midoOps should contain only Create(mDhcpWithDNS)
    }

    private val dest1Subnet = IPSubnetUtil.toProto("10.0.0.0/24")
    private val dest2Subnet = IPSubnetUtil.toProto("10.0.1.0/24")
    private val nSubnetWithRoutes  = nSubnetFromTxt(s"""
        $nSubnet
        host_routes {
            destination { $dest1Subnet }
            nexthop { $gatewayIp }
        }
        host_routes {
            destination { $dest2Subnet }
            nexthop { $gatewayIp }
        }
        """)
    private val mDhcpWithOpt121Routs  = mDhcpFromTxt(s"""
        $mDhcp
        opt121_routes {
            dst_subnet { $dest1Subnet }
            gateway { $gatewayIp }
        }
        opt121_routes {
            dst_subnet { $dest2Subnet }
            gateway { $gatewayIp }
        }
        """)

    "CREATE subnet with host routes" should "set Opt121 routes" in {
        bind(networkId, nTenantNetwork)
        translator.translate(transaction,
                             Create(nSubnetWithRoutes))
        midoOps should contain only Create(mDhcpWithOpt121Routs)
    }

    "CREATE subnet on uplink network" should "do nothing" in {
        bind(networkId, nUplinkNetwork)
        translator.translate(transaction,
                             Create(nSubnetWithRoutes))
        midoOps shouldBe empty
    }

    "UPDATE subnet on uplink network" should "do nothing" in {
        bind(networkId, nUplinkNetwork)
        translator.translate(transaction,
                             Update(nSubnetWithRoutes))
        midoOps shouldBe empty
    }

    "DELETE subnet on uplink network" should "do nothing" in {
        bind(networkId, nUplinkNetwork)
        bind(nSubnetWithRoutes.getId, nSubnetWithRoutes)
        translator.translate(transaction,
                             Delete(classOf[NeutronSubnet], nSubnetWithRoutes.getId))
        midoOps should contain only Delete(classOf[Dhcp], subnetId)
    }

    "DELETE subnet with no default route" should "delete the DHCP" in {
        bind(networkId, nTenantNetwork)
        val subnet = nSubnetWithGatewayIp.toBuilder.clearGatewayIp().build()
        val dhcp =
            mDhcpWithDefaultGateway.toBuilder.clearDefaultGateway().build()
        bind(subnetId, subnet)
        bind(subnetId, dhcp)

        translator.translate(transaction,
                             Delete(classOf[NeutronSubnet], subnetId))
        midoOps should contain only Delete(classOf[Dhcp], subnetId)
    }

    private val mDhcpWithGWRoutes = mDhcpFromTxt(s"""
        $mDhcpWithDefaultGateway
        default_gateway { $gatewayIp }
        server_address { $gatewayIp }
        subnet_address { $gatewaySubnet }
        gateway_route_ids { $gatewayRouteId1 }
        """)

    "DELETE subnet with default route" should "update routes and delete " +
    "the DHCP" in {
        bind(networkId, nTenantNetwork)
        bind(subnetId, nSubnetWithGatewayIp)
        bind(subnetId, mDhcpWithGWRoutes)

        val id_list = mDhcpWithGWRoutes.getGatewayRouteIdsList
        val rt1 = Route.newBuilder()
          .setId(id_list.get(0)).build()

        bind(id_list.get(0), rt1)

        translator.translate(transaction,
                             Delete(classOf[NeutronSubnet], subnetId))

        midoOps should contain only Delete(classOf[Dhcp], subnetId)
    }

    "UPDATE subnet with dhcp port" should "update Opt121 routes" in {
        val portId1 = randomUuidProto
        val portId2 = randomUuidProto
        val dhcpIp = "192.0.2.1"
        val nPort2 = nPortFromTxt(s"""
            id { $portId2 }
            device_owner: DHCP
            fixed_ips {
              ip_address {
                version: V4
                address: "$dhcpIp"
              }
              subnet_id { $subnetId }
            }
        """)
        val mNetwork = mNetworkFromTxt(s"""
            $mTenantNetwork
            port_ids { $portId1 }
            port_ids { $portId2 }
        """)

        val expectedResult = mDhcpFromTxt(s"""
            $mDhcpWithDefaultGateway
            opt121_routes {
                dst_subnet { ${ RouteManager.META_DATA_SRVC } }
                gateway { ${ IPAddressUtil.toProto(dhcpIp) } }
            }
        """)

        bind(networkId, mNetwork)
        bind(networkId, nTenantNetwork)
        bind(subnetId, mDhcpWithDefaultGateway)
        bind(portId1, null, classOf[NeutronPort])  // Non-Neutron port
        bind(portId2, nPort2)
        translator.translate(transaction,
                             Update(nSubnetWithGatewayIp))
        midoOps should contain only Update(expectedResult)
    }
}
