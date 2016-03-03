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
import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{SecurityGroup => SecurityGroupType, SecurityGroupRule => SecurityGroupRuleType}
import org.midonet.cluster.models.Commons._
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.IPv4Subnet
import org.midonet.util.concurrent.toFutureOps
import org.scalatest.junit.JUnitRunner

import scala.collection.JavaConverters._


/**
 * An integration tests for Neutron Importer / C3PO CRUD operations on Network
 * objects.
 */
@RunWith(classOf[JUnitRunner])
class SecurityGroupIT extends C3POMinionTestBase with ChainManager {

    "SecurityGroupRuleTranslator" should "create rules associated with " +
    "security group" in {
        val sgId = createSecurityGroup(2)

        val existRuleId = UUID.randomUUID()
        val existRule = ruleJson(existRuleId, sgId,
            direction = RuleDirection.INGRESS)
        insertCreateTask(3, SecurityGroupRuleType, existRule, existRuleId)

        val newRuleId = UUID.randomUUID()
        val newRuleJson = ruleJson(newRuleId, sgId,
                                   direction = RuleDirection.EGRESS)
        insertCreateTask(4, SecurityGroupRuleType, newRuleJson, newRuleId)

        eventually {
            // check that the chain has the right rule
            val inChain = storage.get(classOf[Chain], inChainId(sgId)).await()
            inChain.getRuleIdsList.asScala(0) should be(toProto(newRuleId))

            // check that the rule exists
            val inRule = storage.get(classOf[Rule], newRuleId).await()
            inRule should not be null

            // check that the out chain has the right rule
            val outChain = storage.get(classOf[Chain],
                                       outChainId(sgId)).await()
            outChain.getRuleIdsList.asScala(0) should be(toProto(existRuleId))

            // check that the rule exists
            storage.exists(classOf[Rule], existRuleId).await() shouldBe true
        }
    }

    it should "delete rules associated with security group" in {
        val sgId = UUID.randomUUID()

        val existRuleId = UUID.randomUUID()
        val existRule = ruleJson(existRuleId, sgId)

        createSecurityGroup(2, sgId, rules = List(existRule))

        eventually {
            val sg = storage.get(classOf[SecurityGroup], sgId).await()
            sg.getSecurityGroupRulesCount shouldBe 1
        }

        insertDeleteTask(3, SecurityGroupRuleType, existRuleId)
        eventually {
            val outChain = storage.get(classOf[Chain],
                                       outChainId(sgId)).await()
            outChain.getRuleIdsList.asScala.size shouldBe 0

            // The rule should not exist in storage
            storage.exists(classOf[Rule], existRuleId).await() shouldBe false
        }
    }

    "SecurityGroupTranslator" should "handle security group CRUD" in {
        val sg1Id = UUID.randomUUID()
        val rule1Id = UUID.randomUUID()
        val ruleSub = new IPv4Subnet("10.0.0.1", 24)
        val rule1Json = ruleJson(rule1Id, sg1Id, portRange = 15000 to 15500,
                                 remoteIpPrefix = ruleSub)

        val sg2Id = UUID.randomUUID()
        val rule2Id = UUID.randomUUID()
        val rule2Json = ruleJson(rule2Id, sg1Id, etherType = EtherType.IPV6,
                                 remoteSgId = sg2Id)

        createSecurityGroup(2, sg1Id, rules = List(rule1Json, rule2Json))
        createSecurityGroup(3, sg2Id, rules = List())

        val ipg1 = eventually(storage.get(classOf[IPAddrGroup], sg1Id).await())
        val ChainPair(inChain1, outChain1) = getChains(ipg1)

        inChain1.getRuleIdsCount should be(0)
        outChain1.getRuleIdsCount should be(2)
        val outChain1Rules = storage.getAll(
            classOf[Rule], outChain1.getRuleIdsList.asScala).await()
        outChain1Rules(0).getId should be(toProto(rule1Id))
        outChain1Rules(0).getCondition.getTpDst.getStart should be(15000)
        outChain1Rules(0).getCondition.getTpDst.getEnd should be(15500)
        outChain1Rules(0).getCondition.getNwSrcIp.getAddress should be("10.0.0.1")
        outChain1Rules(0).getCondition.getNwSrcIp.getPrefixLength should be(24)

        outChain1Rules(1).getId should be(toProto(rule2Id))
        outChain1Rules(1).getCondition.getDlType should be(EtherType.IPV6_VALUE)
        outChain1Rules(1).getCondition.getIpAddrGroupIdSrc should be(toProto(sg2Id))

        val sg1aJson = sgJson(name = "sg1-updated", id = sg1Id,
                              desc = "Security group", tenantId = "tenant")
        insertUpdateTask(4, SecurityGroupType, sg1aJson, sg1Id)
        insertDeleteTask(5, SecurityGroupType, sg2Id)
        eventually {
            val ipg1a = storage.get(classOf[IPAddrGroup], sg1Id).await()
            ipg1a.getName shouldBe "sg1-updated"

            // Neutron doesn't include the rules on update, so update should not
            // delete the rules just because the rule list in the update request
            // is empty.
            val ChainPair(inChain1a, outChain1a) = getChains(ipg1a)
            inChain1a.getRuleIdsCount shouldBe 0

            outChain1a.getRuleIdsCount shouldBe 2
            outChain1a.getRuleIds(0) shouldBe toProto(rule1Id)
            outChain1a.getRuleIds(1) shouldBe toProto(rule2Id)

            // The Neutron security group should also keep its rules.
            val nsg = storage.get(classOf[SecurityGroup], sg1Id).await()
            nsg.getSecurityGroupRulesCount shouldBe 2

            // SG2 should deleted.
            List(storage.exists(classOf[SecurityGroup], sg2Id),
                 storage.exists(classOf[IPAddrGroup], sg2Id))
                .map(_.await()) shouldBe List(false, false)
        }

        insertDeleteTask(6, SecurityGroupType, sg1Id)

        eventually {
            val delFutures = List(
                storage.getAll(classOf[SecurityGroup]),
                storage.getAll(classOf[IPAddrGroup]),
                storage.getAll(classOf[Chain]),
                storage.getAll(classOf[Rule]))
            val delResults = delFutures.map(_.await())
            delResults.foreach(r => r should be(empty))
        }
    }

    "SecurityGroupTranslator" should "have an edited rule list" in {
        val sgId = createSecurityGroup(2)

        val r1Id= UUID.randomUUID()
        val r1 = ruleJson(r1Id, sgId, direction = RuleDirection.INGRESS)

        insertCreateTask(3, SecurityGroupRuleType, r1, r1Id)

        eventually {
            val sg = storage.get(classOf[SecurityGroup], sgId).await()
            val sgr1 = storage.get(classOf[SecurityGroupRule], r1Id).await()
            sg.getSecurityGroupRulesList should contain only sgr1
        }

        val r2Id = UUID.randomUUID()
        val r2 = ruleJson(r2Id, sgId, direction = RuleDirection.INGRESS)

        insertCreateTask(4, SecurityGroupRuleType, r2, r2Id)

        eventually {
            val sg = storage.get(classOf[SecurityGroup], sgId).await()
            sg.getSecurityGroupRulesCount shouldBe 2
        }

        eventually {
            val sg = storage.get(classOf[SecurityGroup], sgId).await()
            val sgrs = storage.getAll(classOf[SecurityGroupRule],
                                      Seq(r1Id, r2Id)).await()
            sg.getSecurityGroupRulesList should contain theSameElementsAs sgrs
        }

        insertDeleteTask(5, SecurityGroupRuleType, r2Id)

        eventually {
            val sg = storage.get(classOf[SecurityGroup], sgId).await()
            val sgr1 = storage.get(classOf[SecurityGroupRule], r1Id).await()
            sg.getSecurityGroupRulesList should contain only sgr1
        }
    }

    "SecurityGroupTranslator" should
            "delete a rule added as a separate SG rule" in {
        val sgId = createSecurityGroup(2)

        val r1Id = UUID.randomUUID()
        val r1 = ruleJson(r1Id, sgId, direction = RuleDirection.INGRESS)

        insertCreateTask(3, SecurityGroupRuleType, r1, r1Id)

        eventually {
            val sg = storage.get(classOf[SecurityGroup], sgId).await()
            val sgr1 = storage.get(classOf[SecurityGroupRule], r1Id).await()
            sg.getSecurityGroupRulesList should contain only sgr1
        }

        insertDeleteTask(4, SecurityGroupType, sgId)

        eventually {
            val delFutures = List(
                storage.getAll(classOf[SecurityGroup]),
                storage.getAll(classOf[SecurityGroupRule]),
                storage.getAll(classOf[IPAddrGroup]),
                storage.getAll(classOf[Chain]),
                storage.getAll(classOf[Rule]))
            val delResults = delFutures.map(_.await())
            delResults.foreach(r => r should be(empty))
        }
    }
}
