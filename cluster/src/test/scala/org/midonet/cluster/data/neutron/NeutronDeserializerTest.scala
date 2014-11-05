/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.cluster.data.neutron

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron
import org.scalatest.{FunSuite, Spec, Matchers}

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
            NeutronDeserializer.toMessage(json, classOf[Neutron.NeutronNetwork])
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
              |            "direction": "EGRESS",
              |            "ethertype": "IPv4",
              |            "protocol": "TCP",
              |            "port_range_min": 10000,
              |            "port_range_max": 10009
              |        },
              |        {
              |            "id": "1af2f735-6a02-4954-ae21-8316086c2e5e",
              |            "security_group_id": "cbb90306-60e8-446a-9a8a-e31840951096",
              |            "direction": "INGRESS",
              |            "ethertype": "IPv6",
              |            "protocol": "ICMPv6"
              |        }
              |    ]
              |}
            """.stripMargin

        val secGrp =
            NeutronDeserializer.toMessage(json, classOf[Neutron.SecurityGroup])
        secGrp.getId.getMsb shouldBe 0xcbb9030660e8446aL
        secGrp.getId.getLsb shouldBe 0x9a8ae31840951096L
        secGrp.getTenantId shouldBe "dffc89ff6f1644ba8b00af458fa2b76d"
        secGrp.getDescription shouldBe "Test security group"

        val rule1 = secGrp.getSecurityGroupRules(0)
        rule1.getId.getMsb shouldBe 0x6a7e92648fe94429L
        rule1.getId.getLsb shouldBe 0x809acf2514275b75L
        rule1.getSecurityGroupId should equal(secGrp.getId)
        rule1.getDirection shouldBe Commons.RuleDirection.EGRESS
        rule1.getEthertype shouldBe Commons.EtherType.IPv4
        rule1.getProtocol shouldBe Commons.Protocol.TCP
        rule1.getPortRangeMin shouldBe 10000
        rule1.getPortRangeMax shouldBe 10009

        val rule2 = secGrp.getSecurityGroupRules(1)
        rule2.getId.getMsb shouldBe 0x1af2f7356a024954L
        rule2.getId.getLsb shouldBe 0xae218316086c2e5eL
        rule2.getSecurityGroupId should equal(secGrp.getId)
        rule2.getDirection shouldBe Commons.RuleDirection.INGRESS
        rule2.getEthertype shouldBe Commons.EtherType.IPv6
        rule2.getProtocol shouldBe Commons.Protocol.ICMPv6
    }

    // Interesting features:
    // 1. Contains nested message defintion and a repeated field of that type.
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
              |            "first_ip": "10.0.0.1",
              |            "last_ip": "10.0.0.255"
              |        },
              |        {
              |            "first_ip": "1234::1",
              |            "last_ip": "1234::ffff"
              |        }
              |    ],
              |    "dns_nameservers": [
              |        "100.0.0.1",
              |        "200.0.0.1"
              |    ]
              |}
            """.stripMargin

        val subnet =
            NeutronDeserializer.toMessage(json, classOf[Neutron.NeutronSubnet])
        subnet.getName shouldBe "Test subnet"
        subnet.getGatewayIp.getVersion shouldBe Commons.IPAddress.Version.IPV4
        subnet.getGatewayIp.getAddress shouldBe "123.45.67.89"
        subnet.getDnsNameserversList should contain inOrder("100.0.0.1", "200.0.0.1")
        subnet.getAllocationPoolsCount shouldBe 2

        val pool1 = subnet.getAllocationPools(0)
        pool1.getFirstIp.getVersion shouldBe Commons.IPAddress.Version.IPV4
        pool1.getFirstIp.getAddress shouldBe "10.0.0.1"
        pool1.getLastIp.getVersion shouldBe Commons.IPAddress.Version.IPV4
        pool1.getLastIp.getAddress shouldBe "10.0.0.255"

        val pool2 = subnet.getAllocationPools(1)
        pool2.getFirstIp.getVersion shouldBe Commons.IPAddress.Version.IPV6
        pool2.getFirstIp.getAddress shouldBe "1234::1"
        pool2.getLastIp.getVersion shouldBe Commons.IPAddress.Version.IPV6
        pool2.getLastIp.getAddress shouldBe "1234::ffff"
    }
}
