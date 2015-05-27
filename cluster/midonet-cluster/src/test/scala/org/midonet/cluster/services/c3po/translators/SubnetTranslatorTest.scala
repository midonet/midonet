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

import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronSubnet}
import org.midonet.cluster.models.Topology.Dhcp
import org.midonet.cluster.services.c3po.{midonet, neutron}
import org.midonet.cluster.models.ModelsUtil._
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
        network_type: LOCAL
        """)

    "Basic subnet CREATE" should "produce an equivalent Dhcp Object" in {
        bind(networkId, nTenantNetwork)
        val midoOps = translator.translate(neutron.Create(nSubnet))
        midoOps should contain only midonet.Create(mDhcp)
    }

    private val gatewayIp = IPAddressUtil.toProto("10.0.0.1")
    private val gatewaySubnet = IPSubnetUtil.fromAddr(gatewayIp, 24)
    private val gatewaySubnetCidr = gatewaySubnet.asJava.toString
    private val nSubnetWithGatewayIp = nSubnetFromTxt(s"""
        $nSubnet
        cidr: "$gatewaySubnetCidr"
        ip_version: 4
        gateway_ip { $gatewayIp }
        """)
    private val mDhcpWithDefaultGateway = mDhcpFromTxt(s"""
        $mDhcp
        default_gateway { $gatewayIp }
        server_address { $gatewayIp }
        subnet_address { $gatewaySubnet }
        """)
    "CREATE subnet with a gateway IP" should "set the default gateway and " +
    "server address accordingly" in {
        bind(networkId, nTenantNetwork)
        val midoOps = translator.translate(neutron.Create(nSubnetWithGatewayIp))
        midoOps should contain only midonet.Create(mDhcpWithDefaultGateway)
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
        val midoOps = translator.translate(neutron.Create(nSubnetWithDNS))
        midoOps should contain only midonet.Create(mDhcpWithDNS)
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
        val midoOps = translator.translate(neutron.Create(nSubnetWithRoutes))
        midoOps should contain only midonet.Create(mDhcpWithOpt121Routs)
    }

    "CREATE subnet on uplink network" should "do nothing" in {
        bind(networkId, nUplinkNetwork)
        val midoOps = translator.translate(neutron.Create(nSubnetWithRoutes))
        midoOps shouldBe empty
    }

    "UPDATE subnet on uplink network" should "do nothing" in {
        bind(networkId, nUplinkNetwork)
        val midoOps = translator.translate(neutron.Update(nSubnetWithRoutes))
        midoOps shouldBe empty
    }

    "DELETE subnet" should "delete the DHCP" in {
        val midoOps = translator.translate(
            neutron.Delete(classOf[NeutronSubnet], subnetId))
        midoOps should contain only midonet.Delete(classOf[Dhcp], subnetId)
    }
}