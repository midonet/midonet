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

package org.midonet.cluster.services.c3po

import org.junit.runner.RunWith
import org.midonet.cluster.models.Commons.Protocol
import org.midonet.cluster.models.Neutron.NeutronFirewallRule.FirewallRuleAction
import org.midonet.cluster.util.IPSubnetUtil
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FunSuite, Matchers}

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron._

@RunWith(classOf[JUnitRunner])
class NeutronDeserializerTest extends FunSuite with Matchers {


    // Basic message deserialization. Interesting features:
    // 1. UUID field, which requires special handling.
    // 2. Field named "router:external." Deserializer should strip out the part
    //    up to and including the colon, reading it as just "external."
    test("Neutron Network deserialization") {
        val json =
            """
              |{
              |    "status": "ACTIVE",
              |    "name": "private-network",
              |    "admin_state_up": true,
              |    "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
              |    "shared": true,
              |    "id": "d32019d3-bc6e-4319-9c1d-6722fc136a22",
              |    "router:external": true
              |}
            """.stripMargin

        val network =
            NeutronDeserializer.toMessage(json, classOf[NeutronNetwork])
        network.getStatus should equal("ACTIVE")
        network.getName should equal("private-network")
        network.getAdminStateUp shouldBe true
        network.getTenantId should equal("4fd44f30292945e481c7b8a0c8908869")
        network.getShared shouldBe true
        network.getId.getMsb shouldBe 0xd32019d3bc6e4319L
        network.getId.getLsb shouldBe 0x9c1d6722fc136a22L
    }

    // Interesting features:
    // 1. Nested SecurityGroupRule, which themselves have nested UUIDs.
    // 2. In addition to having a complex message type, securityGroupRules
    //    is a repeated field.
    // 3. Enums (EtherType, Protocol, RuleDirection).
    test("Neutron SecurityGroup deserialization.") {
        val json =
            """
              |{
              |    "id": "cbb90306-60e8-446a-9a8a-e31840951096",
              |    "tenant_id": "dffc89ff6f1644ba8b00af458fa2b76d",
              |    "name": "secgrp1",
              |    "description": "Test security group",
              |    "security_group_rules": [
              |        {
              |            "id": "6a7e9264-8fe9-4429-809a-cf2514275b75",
              |            "security_group_id": "cbb90306-60e8-446a-9a8a-e31840951096",
              |            "direction": "egress",
              |            "ethertype": "IPv4",
              |            "protocol": "TCP",
              |            "port_range_min": 10000,
              |            "port_range_max": 10009
              |        },
              |        {
              |            "id": "1af2f735-6a02-4954-ae21-8316086c2e5e",
              |            "security_group_id": "cbb90306-60e8-446a-9a8a-e31840951096",
              |            "direction": "ingress",
              |            "ethertype": "IPv6",
              |            "protocol": "ICMPv6"
              |        }
              |    ]
              |}
            """.stripMargin

        val secGrp =
            NeutronDeserializer.toMessage(json, classOf[SecurityGroup])
        secGrp.getId.getMsb shouldBe 0xcbb9030660e8446aL
        secGrp.getId.getLsb shouldBe 0x9a8ae31840951096L
        secGrp.getTenantId shouldBe "dffc89ff6f1644ba8b00af458fa2b76d"
        secGrp.getDescription shouldBe "Test security group"

        val rule1 = secGrp.getSecurityGroupRules(0)
        rule1.getId.getMsb shouldBe 0x6a7e92648fe94429L
        rule1.getId.getLsb shouldBe 0x809acf2514275b75L
        rule1.getSecurityGroupId should equal(secGrp.getId)
        rule1.getDirection shouldBe Commons.RuleDirection.EGRESS
        rule1.getEthertype shouldBe Commons.EtherType.IPV4
        rule1.getProtocol shouldBe Commons.Protocol.TCP
        rule1.getPortRangeMin shouldBe 10000
        rule1.getPortRangeMax shouldBe 10009

        val rule2 = secGrp.getSecurityGroupRules(1)
        rule2.getId.getMsb shouldBe 0x1af2f7356a024954L
        rule2.getId.getLsb shouldBe 0xae218316086c2e5eL
        rule2.getSecurityGroupId should equal(secGrp.getId)
        rule2.getDirection shouldBe Commons.RuleDirection.INGRESS
        rule2.getEthertype shouldBe Commons.EtherType.IPV6
        rule2.getProtocol shouldBe Commons.Protocol.ICMPV6
    }

    // Interesting features:
    // 1. Contains nested message definitions and a repeated field of that type.
    // 2. IPAddress fields.
    test("NeutronSubnet deserialization") {
        // Skipping a bunch of fields since they're basically the same as
        // fields covered in other tests.
        val json =
            """
              |{
              |    "id": "cfb29505-5daf-4624-b3de-64e36908e795",
              |    "name": "Test subnet",
              |    "gateway_ip": "123.45.67.89",
              |    "allocation_pools": [
              |        {
              |            "start": "10.0.0.1",
              |            "end": "10.0.0.255"
              |        },
              |        {
              |            "start": "1234::1",
              |            "end": "1234::ffff"
              |        }
              |    ],
              |    "dns_nameservers": [
              |        "100.0.0.1",
              |        "200.0.0.1"
              |    ]
              |}
            """.stripMargin

        val subnet =
            NeutronDeserializer.toMessage(json, classOf[NeutronSubnet])
        subnet.getName shouldBe "Test subnet"
        subnet.getGatewayIp.getVersion shouldBe Commons.IPVersion.V4
        subnet.getGatewayIp.getAddress shouldBe "123.45.67.89"
        subnet.getDnsNameserversList.size shouldBe 2
        val dnsAddr1 = subnet.getDnsNameserversList.get(0)
        val dnsAddr2 = subnet.getDnsNameserversList.get(1)
        dnsAddr1.getAddress shouldBe "100.0.0.1"
        dnsAddr1.getVersion shouldBe Commons.IPVersion.V4
        dnsAddr2.getAddress shouldBe "200.0.0.1"
        dnsAddr2.getVersion shouldBe Commons.IPVersion.V4

        val pool1 = subnet.getAllocationPools(0)
        pool1.getStart.getVersion shouldBe Commons.IPVersion.V4
        pool1.getStart.getAddress shouldBe "10.0.0.1"
        pool1.getEnd.getVersion shouldBe Commons.IPVersion.V4
        pool1.getEnd.getAddress shouldBe "10.0.0.255"

        val pool2 = subnet.getAllocationPools(1)
        pool2.getStart.getVersion shouldBe Commons.IPVersion.V6
        pool2.getStart.getAddress shouldBe "1234:0:0:0:0:0:0:1"
        pool2.getEnd.getVersion shouldBe Commons.IPVersion.V6
        pool2.getEnd.getAddress shouldBe "1234:0:0:0:0:0:0:ffff"
    }

    test("Neutron Port deserialization") {
        val json =
            """
              |{
              |    "name": "router-gateway-port",
              |    "admin_state_up": true,
              |    "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
              |    "id": "d32019d3-bc6e-4319-9c1d-6722fc136a22",
              |    "device_owner": "network:router_interface",
              |    "port_security_enabled": true,
              |    "allowed_address_pairs": [{"ip_address": "1.2.3.4", "mac_address": "01:02:03:04:05:06"}]
              |}
            """.stripMargin

        val port =
            NeutronDeserializer.toMessage(json, classOf[NeutronPort])
        port.getName should equal("router-gateway-port")
        port.getAdminStateUp shouldBe true
        port.getTenantId should equal("4fd44f30292945e481c7b8a0c8908869")
        port.getId.getMsb shouldBe 0xd32019d3bc6e4319L
        port.getId.getLsb shouldBe 0x9c1d6722fc136a22L
        port.getDeviceOwner shouldBe NeutronPort.DeviceOwner.ROUTER_INTERFACE
        port.getPortSecurityEnabled shouldBe true
        port.getAllowedAddressPairsCount shouldBe 1
        port.getAllowedAddressPairsList.get(0).getIpAddress.getAddress shouldBe "1.2.3.4"
        port.getAllowedAddressPairsList.get(0).getMacAddress shouldBe "01:02:03:04:05:06"
    }

    test("Neutron Compute Port deserialization") {
        val json =
            """
              |{
              |    "name": "compute-port",
              |    "admin_state_up": true,
              |    "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
              |    "id": "d32019d3-bc6e-4319-9c1d-6722fc136a22",
              |    "device_owner": "compute:some_az"
              |}
            """.stripMargin

        val port =
            NeutronDeserializer.toMessage(json, classOf[NeutronPort])
        port.getName should equal("compute-port")
        port.getAdminStateUp shouldBe true
        port.getTenantId should equal("4fd44f30292945e481c7b8a0c8908869")
        port.getId.getMsb shouldBe 0xd32019d3bc6e4319L
        port.getId.getLsb shouldBe 0x9c1d6722fc136a22L
        port.getDeviceOwner shouldBe NeutronPort.DeviceOwner.COMPUTE
        port.getPortSecurityEnabled shouldBe true
        port.getAllowedAddressPairsCount shouldBe 0
    }

    test("Neutron Router deserialization") {
        val json =
            """
              |{
              |    "name": "test-router",
              |    "admin_state_up": true,
              |    "id": "d32019d3-bc6e-4319-9c1d-6722fc136a22",
              |    "external_gateway_info": {
              |        "enable_snat": true,
              |        "fixed_external_ips": [ "10.0.0.1", "10.0.0.2"]
              |    }
              |}
            """.stripMargin

        // "Fixed_external_ips" is a unrecognized field. Should be ignored.
        val router =
            NeutronDeserializer.toMessage(json, classOf[NeutronRouter])
        router.getName should equal("test-router")
        router.getAdminStateUp shouldBe true
        router.getId.getMsb shouldBe 0xd32019d3bc6e4319L
        router.getId.getLsb shouldBe 0x9c1d6722fc136a22L
        router.getExternalGatewayInfo.getEnableSnat shouldBe true
    }

    test("Empty-string enum value ignored") {
        val json =
            """
              |{
              |    "name": "test-port",
              |    "device_owner": "",
              |    "status": "valid"
              |}
            """.stripMargin

        val port = NeutronDeserializer.toMessage(json, classOf[NeutronPort])
        port.getName shouldBe "test-port"
        port.hasDeviceOwner shouldBe false
        port.getStatus shouldBe "valid"
    }

    test("Neutron Firewall deserialization") {
        val json =
            """
              |{
              |    "name": "test-fw",
              |    "description": "test-desc",
              |    "shared": true,
              |    "status": "ACTIVE",
              |    "firewall_policy_id": "c63e3aa8-45d7-11e5-8181-0242ac110002",
              |    "admin_state_up": true,
              |    "tenant_id": "test-tenant",
              |    "id": "9b77a944-45d7-11e5-a6b4-0242ac110002",
              |    "firewall_rule_list": [
              |        {"id": "158dac38-45d8-11e5-b408-0242ac110002",
              |         "tenant_id": "test-tenant",
              |         "name": "test-fw-rule",
              |         "description": "test-desc",
              |         "shared": true,
              |         "protocol": "tcp",
              |         "ip_version": 4,
              |         "source_ip_address": "10.0.0.0/24",
              |         "destination_ip_address": "200.0.0.1",
              |         "source_port": "80",
              |         "destination_port": "8080:8085",
              |         "action": "deny",
              |         "position": 4,
              |         "enabled": false
              |        }
              |    ],
              |    "add-router-ids": ["f51c6aa2-45d7-11e5-83a4-0242ac110002"],
              |    "del-router-ids": ["fdcb8f84-45d7-11e5-84a9-0242ac110002"]
              |}
            """.stripMargin

        val fw = NeutronDeserializer.toMessage(json, classOf[NeutronFirewall])
        fw.getId.getMsb shouldBe 0x9b77a94445d711e5L
        fw.getId.getLsb shouldBe 0xa6b40242ac110002L
        fw.getName shouldBe "test-fw"
        fw.getDescription shouldBe "test-desc"
        fw.getShared shouldBe true
        fw.getStatus shouldBe "ACTIVE"
        fw.getFirewallPolicyId.getMsb shouldBe 0xc63e3aa845d711e5L
        fw.getFirewallPolicyId.getLsb shouldBe 0x81810242ac110002L
        fw.getAdminStateUp shouldBe true
        fw.getTenantId shouldBe "test-tenant"

        fw.getAddRouterIdsCount shouldBe 1
        fw.getAddRouterIds(0).getMsb shouldBe 0xf51c6aa245d711e5L
        fw.getAddRouterIds(0).getLsb shouldBe 0x83a40242ac110002L
        fw.getDelRouterIdsCount shouldBe 1
        fw.getDelRouterIds(0).getMsb shouldBe 0xfdcb8f8445d711e5L
        fw.getDelRouterIdsOrBuilder(0).getLsb shouldBe 0x84a90242ac110002L

        fw.getFirewallRuleListCount shouldBe 1
        fw.getFirewallRuleList(0).getId.getMsb shouldBe 0x158dac3845d811e5L
        fw.getFirewallRuleList(0).getId.getLsb shouldBe 0xb4080242ac110002L
        fw.getFirewallRuleList(0).getTenantId shouldBe "test-tenant"
        fw.getFirewallRuleList(0).getName shouldBe "test-fw-rule"
        fw.getFirewallRuleList(0).getDescription shouldBe "test-desc"
        fw.getFirewallRuleList(0).getShared shouldBe true
        fw.getFirewallRuleList(0).getProtocol shouldBe Protocol.TCP
        fw.getFirewallRuleList(0).getIpVersion shouldBe 4
        fw.getFirewallRuleList(0).getSourceIpAddress shouldBe
            IPSubnetUtil.toProto("10.0.0.0/24")
        fw.getFirewallRuleList(0).getDestinationIpAddress shouldBe
            IPSubnetUtil.fromAddr("200.0.0.1")
        fw.getFirewallRuleList(0).getSourcePort shouldBe "80"
        fw.getFirewallRuleList(0).getDestinationPort shouldBe "8080:8085"
        fw.getFirewallRuleList(0).getAction shouldBe FirewallRuleAction.DENY
        fw.getFirewallRuleList(0).getPosition shouldBe 4
        fw.getFirewallRuleList(0).getEnabled shouldBe false
     }
}
