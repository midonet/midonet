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

import java.util.UUID

import org.junit.runner.RunWith
import org.midonet.cluster.models.Commons.Protocol
import org.midonet.cluster.models.Neutron.NeutronFirewallRule.FirewallRuleAction
import org.midonet.cluster.util.{UUIDUtil, IPSubnetUtil}
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
        val netId = UUID.randomUUID()
        val json =
            s"""
              |{
              |    "status": "ACTIVE",
              |    "name": "private-network",
              |    "admin_state_up": true,
              |    "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
              |    "shared": true,
              |    "id": "$netId",
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
        network.getId shouldBe UUIDUtil.toProto(netId)
    }

    // Interesting features:
    // 1. Nested SecurityGroupRule, which themselves have nested UUIDs.
    // 2. In addition to having a complex message type, securityGroupRules
    //    is a repeated field.
    // 3. Enums (EtherType, Protocol, RuleDirection).
    test("Neutron SecurityGroup deserialization.") {
        val sgId = UUID.randomUUID()
        val rule1Id = UUID.randomUUID()
        val rule2Id = UUID.randomUUID()
        val json =
            s"""
              |{
              |    "id": "$sgId",
              |    "tenant_id": "dffc89ff6f1644ba8b00af458fa2b76d",
              |    "name": "secgrp1",
              |    "description": "Test security group",
              |    "security_group_rules": [
              |        {
              |            "id": "$rule1Id",
              |            "security_group_id": "$sgId",
              |            "direction": "egress",
              |            "ethertype": "IPv4",
              |            "protocol": "TCP",
              |            "port_range_min": 10000,
              |            "port_range_max": 10009
              |        },
              |        {
              |            "id": "$rule2Id",
              |            "security_group_id": "$sgId",
              |            "direction": "ingress",
              |            "ethertype": "IPv6",
              |            "protocol": "ICMPv6"
              |        }
              |    ]
              |}
            """.stripMargin

        val secGrp =
            NeutronDeserializer.toMessage(json, classOf[SecurityGroup])
        secGrp.getId shouldBe UUIDUtil.toProto(sgId)
        secGrp.getTenantId shouldBe "dffc89ff6f1644ba8b00af458fa2b76d"
        secGrp.getDescription shouldBe "Test security group"

        val rule1 = secGrp.getSecurityGroupRules(0)
        rule1.getId shouldBe UUIDUtil.toProto(rule1Id)
        rule1.getSecurityGroupId should equal(secGrp.getId)
        rule1.getDirection shouldBe Commons.RuleDirection.EGRESS
        rule1.getEthertype shouldBe Commons.EtherType.IPV4
        rule1.getProtocol shouldBe Commons.Protocol.TCP
        rule1.getPortRangeMin shouldBe 10000
        rule1.getPortRangeMax shouldBe 10009

        val rule2 = secGrp.getSecurityGroupRules(1)
        rule2.getId shouldBe UUIDUtil.toProto(rule2Id)
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
        val subId = UUID.randomUUID()
        val json =
            s"""
              |{
              |    "id": "$subId",
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
        subnet.getId shouldBe UUIDUtil.toProto(subId)
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
        val portId = UUID.randomUUID()
        val json =
            s"""
              |{
              |    "name": "router-gateway-port",
              |    "admin_state_up": true,
              |    "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
              |    "id": "$portId",
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
        port.getId shouldBe UUIDUtil.toProto(portId)
        port.getDeviceOwner shouldBe NeutronPort.DeviceOwner.ROUTER_INTERFACE
        port.getPortSecurityEnabled shouldBe true
        port.getAllowedAddressPairsCount shouldBe 1
        port.getAllowedAddressPairsList.get(0).getIpAddress.getAddress shouldBe "1.2.3.4"
        port.getAllowedAddressPairsList.get(0).getMacAddress shouldBe "01:02:03:04:05:06"
    }

    test("Neutron Compute Port deserialization") {
        val portId = UUID.randomUUID()
        val json =
            s"""
              |{
              |    "name": "compute-port",
              |    "admin_state_up": true,
              |    "tenant_id": "4fd44f30292945e481c7b8a0c8908869",
              |    "id": "$portId",
              |    "device_owner": "compute:some_az"
              |}
            """.stripMargin

        val port =
            NeutronDeserializer.toMessage(json, classOf[NeutronPort])
        port.getName should equal("compute-port")
        port.getAdminStateUp shouldBe true
        port.getTenantId should equal("4fd44f30292945e481c7b8a0c8908869")
        port.getId shouldBe UUIDUtil.toProto(portId)
        port.getDeviceOwner shouldBe NeutronPort.DeviceOwner.COMPUTE
        port.getPortSecurityEnabled shouldBe true
        port.getAllowedAddressPairsCount shouldBe 0
    }

    test("Neutron Router deserialization") {
        val routerId = UUID.randomUUID()
        val json =
            s"""
              |{
              |    "name": "test-router",
              |    "admin_state_up": true,
              |    "id": "$routerId",
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
        router.getId shouldBe UUIDUtil.toProto(routerId)
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
        val fwId = UUID.randomUUID()
        val fwPolicyId = UUID.randomUUID()
        val router1Id = UUID.randomUUID()
        val router2Id = UUID.randomUUID()
        val fwRuleId = UUID.randomUUID()
        val json =
            s"""
              |{
              |    "name": "test-fw",
              |    "description": "test-desc",
              |    "shared": true,
              |    "status": "ACTIVE",
              |    "firewall_policy_id": "$fwPolicyId",
              |    "admin_state_up": true,
              |    "tenant_id": "test-tenant",
              |    "id": "$fwId",
              |    "firewall_rule_list": [
              |        {"id": "$fwRuleId",
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
              |    "add-router-ids": ["$router1Id"],
              |    "del-router-ids": ["$router2Id"]
              |}
            """.stripMargin

        val fw = NeutronDeserializer.toMessage(json, classOf[NeutronFirewall])
        fw.getId shouldBe UUIDUtil.toProto(fwId)
        fw.getName shouldBe "test-fw"
        fw.getDescription shouldBe "test-desc"
        fw.getShared shouldBe true
        fw.getStatus shouldBe "ACTIVE"
        fw.getFirewallPolicyId shouldBe UUIDUtil.toProto(fwPolicyId)
        fw.getAdminStateUp shouldBe true
        fw.getTenantId shouldBe "test-tenant"

        fw.getAddRouterIdsCount shouldBe 1
        fw.getAddRouterIds(0) shouldBe  UUIDUtil.toProto(router1Id)
        fw.getDelRouterIdsCount shouldBe 1
        fw.getDelRouterIds(0) shouldBe  UUIDUtil.toProto(router2Id)

        fw.getFirewallRuleListCount shouldBe 1
        fw.getFirewallRuleList(0).getId shouldBe  UUIDUtil.toProto(fwRuleId)
        fw.getFirewallRuleList(0).getTenantId shouldBe "test-tenant"
        fw.getFirewallRuleList(0).getName shouldBe "test-fw-rule"
        fw.getFirewallRuleList(0).getDescription shouldBe "test-desc"
        fw.getFirewallRuleList(0).getShared shouldBe true
        fw.getFirewallRuleList(0).getProtocol shouldBe Protocol.TCP
        fw.getFirewallRuleList(0).getIpVersion shouldBe 4
        fw.getFirewallRuleList(0).getSourceIpAddress shouldBe
            IPSubnetUtil.toProto("10.0.0.0/24")
        fw.getFirewallRuleList(0).getDestinationIpAddress shouldBe
            IPSubnetUtil.fromAddress("200.0.0.1")
        fw.getFirewallRuleList(0).getSourcePort shouldBe "80"
        fw.getFirewallRuleList(0).getDestinationPort shouldBe "8080:8085"
        fw.getFirewallRuleList(0).getAction shouldBe FirewallRuleAction.DENY
        fw.getFirewallRuleList(0).getPosition shouldBe 4
        fw.getFirewallRuleList(0).getEnabled shouldBe false
     }
}
