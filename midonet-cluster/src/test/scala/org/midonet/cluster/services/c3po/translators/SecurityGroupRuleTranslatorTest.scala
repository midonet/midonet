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

import org.midonet.cluster.models.Commons.{Protocol, UUID}
import org.midonet.cluster.models.Commons.Condition.FragmentPolicy
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.SecurityGroupRule
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Delete, Create}
import org.midonet.cluster.services.c3po.OpType
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil.randomUuidProto


/* A common base class for testing NeutronPort CRUD translation. */
@RunWith(classOf[JUnitRunner])
class SecurityGroupRuleTranslatorTest extends TranslatorTestBase
                                      with ChainManager {
    protected var translator: SecurityGroupRuleTranslator = _

    protected val zkRoot = "/midonet/test"

    protected val sgId = randomUuidProto
    protected val sgrId = randomUuidProto
    protected val mChainId = outChainId(sgId)
    protected val sgJUuid = UUIDUtil.fromProto(sgId)

    protected def sgBase(sgId: UUID = sgId) = s"""
        id { $sgId }
        tenant_id: 'neutron tenant'
        name: 'sg'
        """

    protected def sgRuleBase() =s"""
         id { $sgrId }
         protocol: TCP
         security_group_id: { $sgId }
         tenant_id: 'neutron tenant'
         """.stripMargin

    protected def midoSshRule = mRuleFromTxt(
        s"""
            id { $sgrId }
            chain_id { $mChainId }
        """.stripMargin)

    protected def nSshRule = nSecurityGroupRuleFromTxt(
        sgRuleBase() + s"""
            direction: INGRESS
            port_range_max: 22
            port_range_min: 22
        """.stripMargin)

    protected val defaultSg = nSecurityGroupFromTxt(sgBase())

    protected val mChain = mChainFromTxt(
        s"""
            id { $mChainId }
            name: 'chain name'
            rule_ids { $sgrId }
        """.stripMargin)

    before {
        initMockStorage()
        translator = new SecurityGroupRuleTranslator()
        bind(sgId, defaultSg)
        bind(sgrId, midoSshRule)
        bind(sgrId, nSshRule)
        bind(mChainId, mChain)
    }

    "SSH rule" should "exist when creating the rule" in {
        val sshInRuleText = sgRuleBase() + s"""
            direction: INGRESS
            port_range_max: 22
            port_range_min: 22
        """.stripMargin
        val nSshInRule = nSecurityGroupRuleFromTxt(sshInRuleText)
        val mSshInRule = SecurityGroupRuleManager.translate(nSshInRule)

        translator.translate(transaction, Create(nSshInRule))

        val inChain = findChainOp(midoOps, OpType.Update,
                                  inChainId(defaultSg.getId))
        inChain shouldBe null

        val outChain = findChainOp(midoOps, OpType.Update, outChainId(sgId))
        outChain shouldBe null
    }

    "SSH rule" should "be deleted when deleting the rule" in {
        translator.translate(transaction, Delete(classOf[SecurityGroupRule],
                                                 sgrId))

        val chain = findChainOp(midoOps, OpType.Update, mChainId)
        chain shouldBe null
    }

    "Ingress SecurityGroupRule translation" should "correspond " +
    "to mido rules" in {
        val start = 100
        val end = 117
        val ip = "1.1.1.0"
        val len = "24"
        val nRule = nSecurityGroupRuleFromTxt(s"""
         id { $sgrId }
         protocol: TCP
         security_group_id: { $sgId }
         tenant_id: 'neutron tenant'
         direction: INGRESS
         port_range_max: $end
         port_range_min: $start
         remote_ip_prefix: '$ip/$len'
         """.stripMargin)

        val mRules = SecurityGroupRuleManager.translate(nRule)
        mRules.size shouldBe 2
        val mRule = mRules(0)
        mRule.getId shouldBe sgrId
        mRule.getCondition.getNwProto shouldBe Protocol.TCP.getNumber
        mRule.getCondition.getFragmentPolicy shouldBe FragmentPolicy.HEADER
        mRule.getCondition.getTpDst.getStart shouldBe start
        mRule.getCondition.getTpDst.getEnd shouldBe end
        mRule.getCondition.getNwSrcIp.getAddress shouldBe ip
        val mRule2 = mRules(1)
        mRule2.getId shouldBe SecurityGroupRuleManager.nonHeaderRuleId(sgrId)
        mRule2.getCondition.getNwProto shouldBe Protocol.TCP.getNumber
        mRule2.getCondition.getFragmentPolicy shouldBe FragmentPolicy.NONHEADER
        mRule2.getCondition.hasTpDst shouldBe false
        mRule2.getCondition.getNwSrcIp.getAddress shouldBe ip
    }

    "Egress SecurityGroupRule translation" should "correspond " +
    "to mido rules" in {
        val start = 100
        val end = 117
        val ip = "1.1.1.0"
        val len = "24"
        val nRule = nSecurityGroupRuleFromTxt(s"""
            id { $sgrId }
            protocol: UDP
            security_group_id: { $sgId }
            tenant_id: 'neutron tenant'
            direction: EGRESS
            port_range_max: $end
            port_range_min: $start
            remote_ip_prefix: '$ip/$len'
            """.stripMargin)

        val mRules = SecurityGroupRuleManager.translate(nRule)
        mRules.size shouldBe 2
        val mRule = mRules(0)
        mRule.getId shouldBe sgrId
        mRule.getCondition.getNwProto shouldBe Protocol.UDP.getNumber
        mRule.getCondition.getFragmentPolicy shouldBe FragmentPolicy.HEADER
        mRule.getCondition.getTpDst.getStart shouldBe start
        mRule.getCondition.getTpDst.getEnd shouldBe end
        mRule.getCondition.getNwDstIp.getAddress shouldBe ip
        val mRule2 = mRules(1)
        mRule2.getId shouldBe SecurityGroupRuleManager.nonHeaderRuleId(sgrId)
        mRule2.getCondition.getNwProto shouldBe Protocol.UDP.getNumber
        mRule2.getCondition.getFragmentPolicy shouldBe FragmentPolicy.NONHEADER
        mRule2.getCondition.hasTpDst shouldBe false
        mRule2.getCondition.getNwDstIp.getAddress shouldBe ip
    }

    "Rule" should "allow lower-bounded open port range" in {
        val ruleText = sgRuleBase() + s"""
            direction: INGRESS
            port_range_min: 10
        """.stripMargin
        val nRule = nSecurityGroupRuleFromTxt(ruleText)

        val mRules = SecurityGroupRuleManager.translate(nRule)
        mRules.size shouldBe 2
        val mRule = mRules(0)
        mRule.getId shouldBe sgrId
        mRule.getCondition.getFragmentPolicy shouldBe FragmentPolicy.HEADER
        mRule.getCondition.getTpDst.hasStart shouldBe true
        mRule.getCondition.getTpDst.getStart shouldBe 10
        mRule.getCondition.getTpDst.hasEnd shouldBe false
        val mRule2 = mRules(1)
        mRule2.getId shouldBe SecurityGroupRuleManager.nonHeaderRuleId(sgrId)
        mRule2.getCondition.getFragmentPolicy shouldBe FragmentPolicy.NONHEADER
        mRule2.getCondition.hasTpDst shouldBe false
    }

    "Rule" should "allow upper-bounded open port range" in {
        val ruleText = sgRuleBase() + s"""
            direction: INGRESS
            port_range_max: 10
        """.stripMargin
        val nRule = nSecurityGroupRuleFromTxt(ruleText)

        val mRules = SecurityGroupRuleManager.translate(nRule)
        mRules.size shouldBe 2
        val mRule = mRules(0)
        mRule.getId shouldBe sgrId
        mRule.getCondition.getFragmentPolicy shouldBe FragmentPolicy.HEADER
        mRule.getCondition.getTpDst.hasStart shouldBe false
        mRule.getCondition.getTpDst.hasEnd shouldBe true
        mRule.getCondition.getTpDst.getEnd shouldBe 10
        val mRule2 = mRules(1)
        mRule2.getId shouldBe SecurityGroupRuleManager.nonHeaderRuleId(sgrId)
        mRule2.getCondition.getFragmentPolicy shouldBe FragmentPolicy.NONHEADER
        mRule2.getCondition.hasTpDst shouldBe false
    }

    "ICMP code and type" should "convert to src/dst port ranges" in {
        val ruleText = sgRuleBase() + s"""
            direction: INGRESS
            protocol: ICMP
            port_range_min: 3
            port_range_max: 1
        """.stripMargin
        val nRule = nSecurityGroupRuleFromTxt(ruleText)

        val mRules = SecurityGroupRuleManager.translate(nRule)
        mRules.size shouldBe 2
        val mRule = mRules(0)
        mRule.getId shouldBe sgrId
        mRule.getCondition.getFragmentPolicy shouldBe FragmentPolicy.HEADER
        mRule.getCondition.getTpSrc.hasStart shouldBe true
        mRule.getCondition.getTpSrc.getStart shouldBe 3
        mRule.getCondition.getTpSrc.hasEnd shouldBe true
        mRule.getCondition.getTpSrc.getEnd shouldBe 3
        mRule.getCondition.getTpDst.hasStart shouldBe true
        mRule.getCondition.getTpDst.getStart shouldBe 1
        mRule.getCondition.getTpDst.hasEnd shouldBe true
        mRule.getCondition.getTpDst.getEnd shouldBe 1
        val mRule2 = mRules(1)
        mRule2.getId shouldBe SecurityGroupRuleManager.nonHeaderRuleId(sgrId)
        mRule2.getCondition.getFragmentPolicy shouldBe FragmentPolicy.NONHEADER
        mRule2.getCondition.hasTpSrc shouldBe false
        mRule2.getCondition.hasTpDst shouldBe false
    }

    "Non-L4 ingress SecurityGroupRule translation" should "correspond " +
    "to a mido rule" in {
        val ip = "1.1.1.0"
        val len = "24"
        val nRule = nSecurityGroupRuleFromTxt(s"""
         id { $sgrId }
         protocol: TCP
         security_group_id: { $sgId }
         tenant_id: 'neutron tenant'
         direction: INGRESS
         remote_ip_prefix: '$ip/$len'
         """.stripMargin)

        val mRules = SecurityGroupRuleManager.translate(nRule)
        mRules.size shouldBe 1
        val mRule = mRules(0)
        mRule.getId shouldBe sgrId
        mRule.getCondition.getNwProto shouldBe Protocol.TCP.getNumber
        mRule.getCondition.getFragmentPolicy shouldBe FragmentPolicy.ANY
        mRule.getCondition.getNwSrcIp.getAddress shouldBe ip
    }
}
