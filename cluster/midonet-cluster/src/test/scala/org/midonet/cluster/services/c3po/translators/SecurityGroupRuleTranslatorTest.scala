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

import scala.collection.JavaConverters._

import com.google.protobuf.Message

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.{MatchResult, Matcher}

import org.midonet.cluster.data.Bridge
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.{SecurityGroupRule, NeutronPort}
import org.midonet.cluster.models.Topology.{Chain, Port, Rule}
import org.midonet.cluster.services.c3po.C3POStorageManager.{OpType, Operation}
import org.midonet.cluster.services.c3po.{midonet, neutron}
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.midolman.state.{MacPortMap, PathBuilder}
import org.midonet.packets.{ARP, IPv4, IPv6, MAC}


/* A common base class for testing NeutronPort CRUD translation. */
class SecurityGroupRuleTranslatorTest extends TranslatorTestBase {
    protected var translator: SecurityGroupRuleTranslator = _

    protected val zkRoot = "/midonet/test"

    protected val sgId = randomUuidProto
    protected val sgrId = randomUuidProto
    protected val mChainId = randomUuidProto
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

    protected val defaultSg = nSecurityGroupFromTxt(sgBase())

    protected val mChain = mChainFromTxt(s"""id { $mChainId }""".stripMargin)

    before {
        initMockStorage()
        translator = new SecurityGroupRuleTranslator(storage)
        bind(sgId, defaultSg)
        bind(sgrId, midoSshRule)
        bind(mChainId, mChain)
    }

    "SSH rule" should "exist when creating the rule" in {
        val sshInRuleText = sgRuleBase() + s"""
            id { $sgrId }
            protocol: TCP
            security_group_id: { $sgId }
            tenant_id: 'neutron tenant'
            direction: INGRESS
            port_range_max: 22
            port_range_min: 22
        """.stripMargin
        val nSshInRule = nSecurityGroupRuleFromTxt(sshInRuleText)
        val mSshInRule = SecurityGroupRuleManager.translate(nSshInRule)

        val midoOps = translator.translate(neutron.Create(nSshInRule))

        val inChain = findChainOp(midoOps, OpType.Update,
                                  ChainManager.inChainId(defaultSg.getId))
        inChain shouldBe null

        val outChain = findChainOp(midoOps, OpType.Update,
                                   ChainManager.outChainId(defaultSg.getId))
        outChain should not be null
        outChain.getRuleIdsList.size shouldBe 1
        midoOps should contain (midonet.Create(mSshInRule))
    }

    "SSH rule" should "be deleted when deleting the rule" in {
        val midoOps = translator.translate(
            neutron.Delete(classOf[SecurityGroupRule], sgrId))

        val chain = findChainOp(midoOps, OpType.Update, mChainId)
        chain should not be null
        chain.getRuleIdsList.size shouldBe 0
        midoOps should contain (midonet.Delete(classOf[Rule], sgrId))
    }
}