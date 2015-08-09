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
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.Protocol
import org.midonet.cluster.models.Neutron.NeutronFirewallRule
import org.midonet.cluster.models.Neutron.NeutronFirewallRule.FirewallRuleAction
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.models.Topology.Rule.{Action, Type}
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FirewallTranslatorTest extends TranslatorTestBase with ChainManager {

    import FirewallTranslator._
    protected var translator: FirewallTranslator = _

    private def neutronRule(action: FirewallRuleAction,
                            protocol: Protocol,
                            srcAddr: String,
                            dstAddr: String,
                            srcPort: String,
                            dstPort: String): NeutronFirewallRule = {

        val ruleId = UUIDUtil.toProto(UUID.randomUUID())
        NeutronFirewallRule.newBuilder()
            .setId(ruleId)
            .setAction(action)
            .setSourcePort(srcPort)
            .setDestinationPort(dstPort)
            .setSourceIpAddress(IPSubnetUtil.fromAddr(srcAddr))
            .setDestinationIpAddress(IPSubnetUtil.fromAddr(dstAddr))
            .setProtocol(protocol)
            .build()
    }

    private def assertIngressRulesMatch(rule: Rule, nRule: NeutronFirewallRule,
                                        chainId: Commons.UUID): Unit = {
        val action = if (nRule.getAction == FirewallRuleAction.ALLOW)
            Action.RETURN else Action.DROP

        rule.getChainId shouldBe chainId
        rule.getId shouldBe nRule.getId
        rule.getAction shouldBe action
        rule.getType shouldBe Type.LITERAL_RULE
        rule.getCondition.getNwProto shouldBe nRule.getProtocol.getNumber
    }

    private def validateIngressRule(nRule: NeutronFirewallRule) = {
        val chainId = UUIDUtil.toProto(UUID.randomUUID())
        assertIngressRulesMatch(
            toPreRoutingRule(nRule, chainId), nRule, chainId)
    }

    "Neutron firewall rule " should
        "translate to a proper midonet rule" in {

        validateIngressRule(
            neutronRule(FirewallRuleAction.ALLOW, Protocol.TCP,
                        "0.0.0.0", "0.0.0.0",
                        "80", "8080"))
    }
}
