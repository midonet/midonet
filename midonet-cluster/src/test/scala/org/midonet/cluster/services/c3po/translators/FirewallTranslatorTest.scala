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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.{IPSubnet, IPVersion, Protocol}
import org.midonet.cluster.models.Neutron.NeutronFirewallRule
import org.midonet.cluster.models.Neutron.NeutronFirewallRule.FirewallRuleAction
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.models.Topology.Rule.{Action, Type}
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}

@RunWith(classOf[JUnitRunner])
class FirewallTranslatorTest extends TranslatorTestBase  {

    import FirewallTranslator._

    protected var translator: FirewallTranslator = _

    private def neutronFwRule(action: FirewallRuleAction = FirewallRuleAction.ALLOW,
                              ipVersion: IPVersion = null,
                              protocol: Protocol = null,
                              srcAddr: IPSubnet = null,
                              dstAddr: IPSubnet = null,
                              srcPort: String = null,
                              dstPort: String = null) : NeutronFirewallRule = {
        val r = NeutronFirewallRule.newBuilder()
            .setId(UUIDUtil.randomUuidProto)
            .setAction(action)

        if (ipVersion != null) {
            r.setIpVersion(ipVersion.getNumber)
        }

        if (srcPort != null) {
            r.setSourcePort(srcPort)
        }

        if (dstPort != null) {
            r.setDestinationPort(dstPort)
        }

        if (srcAddr != null) {
            r.setSourceIpAddress(srcAddr)
        }

        if (dstAddr != null) {
            r.setDestinationIpAddress(dstAddr)
        }

        if (protocol != null) {
            r.setProtocol(protocol)
        }

        r.build()
    }

    private def assertRuleMatches(rule: Rule, nRule: NeutronFirewallRule,
                                  chainId: Commons.UUID): Unit = {
        // Otherwise defaults to TCP
        val protocol = if (nRule.hasProtocol)
            nRule.getProtocol.getNumber else 0

        rule.getType shouldBe Type.LITERAL_RULE
        rule.getChainId shouldBe chainId
        rule.getId shouldBe nRule.getId
        rule.getCondition.getNwProto shouldBe protocol
        rule.getCondition.getNwSrcIp shouldBe nRule.getSourceIpAddress
        rule.getCondition.getNwDstIp shouldBe nRule.getDestinationIpAddress
    }

    private def validateRuleConversion(nRule: NeutronFirewallRule) = {
        val chainId = UUIDUtil.toProto(UUID.randomUUID())
        assertRuleMatches(toRule(nRule, chainId), nRule, chainId)
    }

    "A rule allowing all" should "be translated" in
        validateRuleConversion(neutronFwRule())

    "A rule denying all" should "be translated" in
        validateRuleConversion(neutronFwRule(action = FirewallRuleAction.DENY))

    "A rule allowing TCP" should "be translated" in
        validateRuleConversion(neutronFwRule(protocol = Protocol.TCP))

    "A rule allowing UDP" should "be translated" in
        validateRuleConversion(neutronFwRule(protocol = Protocol.UDP))

    "A rule allowing ICMP" should "be translated" in
        validateRuleConversion(neutronFwRule(protocol = Protocol.ICMP))

    "A rule allowing traffic from a subnet" should "be translated" in
        validateRuleConversion(neutronFwRule(
            srcAddr = IPSubnetUtil.toProto("200.0.0.0/24")))

    "A rule allowing traffic from an address" should "be translated" in
        validateRuleConversion(neutronFwRule(
            srcAddr = IPSubnetUtil.fromAddress("200.0.0.10")))

    "A rule allowing traffic to a subnet" should "be translated" in
        validateRuleConversion(neutronFwRule(
            dstAddr = IPSubnetUtil.toProto("200.0.0.0/24")))

    "A rule allowing traffic to an address" should "be translated" in
        validateRuleConversion(neutronFwRule(
            dstAddr = IPSubnetUtil.fromAddress("200.0.0.10")))

    "A rule allowing traffic to a port " should "be translated" in
        validateRuleConversion(neutronFwRule(dstPort = "80"))

    "A rule allowing traffic to a port range " should "be translated" in
        validateRuleConversion(neutronFwRule(dstPort = "8080:8090"))

    "A rule allowing traffic from a port " should "be translated" in
        validateRuleConversion(neutronFwRule(srcPort = "80"))

    "A rule allowing traffic from a port range " should "be translated" in
        validateRuleConversion(neutronFwRule(srcPort = "8080:8090"))

    "A rule allowing traffic from IPv4" should "be translated" in
        validateRuleConversion(neutronFwRule(ipVersion = IPVersion.V4))

    it should "produce IllegalArgumentException for IPv6" in {
        intercept[IllegalArgumentException] {
            toRule(neutronFwRule(ipVersion = IPVersion.V6),
                   UUIDUtil.randomUuidProto)
        }
    }

    "Valid Neutron FW rule actions" should "be translated" in {
        toTopologyRuleAction(FirewallRuleAction.ALLOW) shouldBe Action.RETURN
        toTopologyRuleAction(FirewallRuleAction.DENY) shouldBe Action.DROP
    }

    "An attempt to convert null Neutron FW rule action" should
        "throw IllegalArgumentException" in {
        intercept[IllegalArgumentException] {
            toTopologyRuleAction(null)
        }
    }
}
