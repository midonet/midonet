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

import org.scalatest.junit.JUnitRunner
import scala.collection.JavaConverters._

import java.util.UUID
import org.junit.runner.RunWith

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{SecurityGroup => SecurityGroupType, SecurityGroupRule => SecurityGroupRuleType}
import org.midonet.cluster.models.Commons._
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.{IPv4Subnet, UDP}
import org.midonet.util.concurrent.toFutureOps


/**
 * An integration tests for Neutron Importer / C3PO CRUD operations on Network
 * objects.
 */
@RunWith(classOf[JUnitRunner])
class SecurityGroupIT extends C3POMinionTestBase with ChainManager {

    "SecurityGroupRuleTranslator" should "create rules associated with " +
    "security group" in {
        val sgId = UUID.randomUUID()

        val existRuleId = UUID.randomUUID()
        val existRule = ruleJson(existRuleId, sgId,
                                 direction = RuleDirection.INGRESS)

        val newRuleId = UUID.randomUUID()
        val newRuleJson = ruleJson(newRuleId, sgId,
                                   direction = RuleDirection.EGRESS)

        val sg1Json = sgJson(name = "sg1", id = sgId, desc = "Security group",
                             tenantId = "tenant", rules = List(existRule))

        insertCreateTask(2, SecurityGroupType, sg1Json.toString, sgId)

        insertCreateTask(3, SecurityGroupRuleType, newRuleJson.toString,
                         newRuleId)

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

        val sg1Json = sgJson(name = "sg1", id = sgId, desc = "Security group",
                             tenantId = "tenant", rules = List(existRule))

        insertCreateTask(2, SecurityGroupType, sg1Json.toString, sgId)

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

        val sg1Json = sgJson(name = "sg1", id = sg1Id,
                             desc = "Security group", tenantId = "tenant",
                             rules = List(rule1Json, rule2Json))
        val sg2Json = sgJson(name ="sg2", id = sg2Id, tenantId = "tenant",
                             rules = List())
        insertCreateTask(2, SecurityGroupType, sg1Json.toString, sg1Id)
        insertCreateTask(3, SecurityGroupType, sg2Json.toString, sg2Id)

        val ipg1 = eventually(storage.get(classOf[IPAddrGroup], sg1Id).await())
        val ChainPair(inChain1, outChain1) = getChains(ipg1)

        inChain1.getRuleIdsCount should be(0)
        outChain1.getRuleIdsCount should be(2)
        val outChain1Rules = storage.getAll(
            classOf[Rule], outChain1.getRuleIdsList.asScala).await()
        outChain1Rules(0).getId should be(toProto(rule1Id))
        outChain1Rules(0).getTpDst.getStart should be(15000)
        outChain1Rules(0).getTpDst.getEnd should be(15500)
        outChain1Rules(0).getNwSrcIp.getAddress should be("10.0.0.1")
        outChain1Rules(0).getNwSrcIp.getPrefixLength should be(24)

        outChain1Rules(1).getId should be(toProto(rule2Id))
        outChain1Rules(1).getDlType should be(EtherType.IPV6_VALUE)
        outChain1Rules(1).getIpAddrGroupIdSrc should be(toProto(sg2Id))

        val rule1aJson = ruleJson(rule1Id, sg1Id,
                                  direction = RuleDirection.EGRESS,
                                  protocol = Protocol.UDP)
        val rule3Id = UUID.randomUUID()
        val rule3Json = ruleJson(rule3Id, sg1Id)
        val sg1aJson = sgJson(name = "sg1-updated", id = sg1Id,
                              desc = "Security group", tenantId = "tenant",
                              rules = List(rule1aJson, rule3Json))
        insertUpdateTask(4, SecurityGroupType, sg1aJson.toString, sg1Id)
        insertDeleteTask(5, SecurityGroupType, sg2Id)
        eventually {
            val ipg1a = storage.get(classOf[IPAddrGroup], sg1Id).await()
            val ChainPair(inChain1a, outChain1a) = getChains(ipg1a)

            inChain1a.getRuleIdsCount should be(1)
            inChain1a.getRuleIds(0) should be(toProto(rule1Id))

            outChain1a.getRuleIdsCount should be(1)
            outChain1a.getRuleIds(0) should be(toProto(rule3Id))
        }

        val ipg1aRules = storage.getAll(
            classOf[Rule], List(rule1Id, rule3Id)).await()

        val inChain1aRule1 = ipg1aRules(0)
        inChain1aRule1.getId should be(toProto(rule1Id))
        inChain1aRule1.getNwProto should be(UDP.PROTOCOL_NUMBER)

        val outChain1aRule1 = ipg1aRules(1)
        outChain1aRule1.getId should be(toProto(rule3Id))

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
}
