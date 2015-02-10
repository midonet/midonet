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

package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.Rule.JumpData
import org.midonet.cluster.models.Topology.{Chain => ProtoChain, Rule => ProtoRule}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.{NotYetException, FlowController}
import org.midonet.midolman.rules.{Rule => SimRule, _}
import org.midonet.midolman.simulation.{Chain => SimChain}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class ChainMapperTest extends MidolmanSpec
                      with TopologyBuilder {

    private var vt: VirtualTopology = _
    private implicit var store: Storage = _

    registerActors(FlowController -> (() => new FlowController))

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[Storage])
    }

    feature("Obtaining a chain with its observable") {
        scenario("A chain with one literal rule") {
            Given("A topology with one chain containing one rule")
            val chainId = UUID.randomUUID()
            val rule1 = buildAndStoreLiteralRule(ProtoRule.Action.ACCEPT, chainId)
            val chain = buildAndStoreChain(chainId, List(rule1.getId))

            When("We subscribe to the chain")
            val obs = new AwaitableObserver[SimChain](1)
            val subcription = VirtualTopology.observable[SimChain](chain.getId)
                .subscribe(obs)

            Then("We receive the chain with the rule")
            obs.await(1.second, 1) shouldBe true
            val simChain = obs.getOnNextEvents.last
            assertEquals(chain, simChain, List(rule1), null)

            And("When we add a 2nd rule to the chain")
            val rule2 = buildAndStoreLiteralRule(ProtoRule.Action.REJECT, chainId)
            var updatedChain = addRuleToChain(rule2, chain)

            Then("We receive the chain with the two rules")
            obs.await(1.second, 1) shouldBe true
            var updatedSimChain = obs.getOnNextEvents.last
            assertEquals(updatedChain, updatedSimChain, List(rule1, rule2), null)

            And("When we remove a rule")
            updatedChain = removeRuleFromChain(rule2.getId, updatedChain)

            Then("We receive the chain with only 1 rule")
            obs.await(1.second, 1) shouldBe true
            updatedSimChain = obs.getOnNextEvents.last
            assertEquals(updatedChain, updatedSimChain, List(rule1), null)

            And("When we delete the last rule")
            updatedChain = removeRuleFromChain(rule1.getId, updatedChain)

            Then("We receive the chain with an empty list of rules")
            obs.await(1.second, 1) shouldBe true
            updatedSimChain = obs.getOnNextEvents.last
            assertEquals(updatedChain, updatedSimChain, List.empty, null)

            And("When we unsubscribe from the chain and update it")
            subcription.unsubscribe()
            updatedChain = updatedChain.toBuilder.setName("newname").build()
            store.update(updatedChain)

            Then("We do not receive any updates")
            obs.await(1.second) shouldBe false
        }

        scenario("Obtaining a chain with get") {
            Given("A topology with one chain containing one rule")
            val chainId = UUID.randomUUID()
            val rule = buildAndStoreLiteralRule(ProtoRule.Action.ACCEPT, chainId)
            val chain = buildAndStoreChain(chainId, List(rule.getId))

            When("We ask for the chain with a get")
            val future = VirtualTopology.get[SimChain](chainId)

            Then("We obtain the chain with its rule")
            val simChain = Await.result[SimChain](future, 1.second)
            assertEquals(chain, simChain, List(rule), null)
        }

        scenario("Obtaining a chain with tryGet") {
            Given("A topology with one chain containing one rule")
            val chainId = UUID.randomUUID()
            val rule = buildAndStoreLiteralRule(ProtoRule.Action.ACCEPT, chainId)
            val chain = buildAndStoreChain(chainId, List(rule.getId))

            When("We ask for the chain with tryGet")
            val nye = intercept[NotYetException] {
                VirtualTopology.tryGet[SimChain](chainId)
            }
            val simChain = Await.result(nye.waitFor, 1.second).asInstanceOf[SimChain]
            assertEquals(chain, simChain, List(rule), null)
        }

        scenario("Obtaining a chain that does not exist") {
            Given("A non-existing chain")
            val chainId = UUID.randomUUID()

            When("We we ask for a non-existing chain")
            intercept[NotFoundException] {
                Then("A NotFoundException is raised")
                val future = VirtualTopology.get[SimChain](chainId)
                Await.result(future, 1.second)
            }
        }

        scenario("Deleting a chain") {
            Given("A topology with one chain")
            val chainId = UUID.randomUUID()
            val chain = buildAndStoreChain(chainId, List.empty)

            When("We subscribe to the chain")
            val obs = new AwaitableObserver[SimChain](1)
            VirtualTopology.observable[SimChain](chain.getId).subscribe(obs)

            Then("We receive the chain")
            obs.await(1.second, 1) shouldBe true
            val simChain = obs.getOnNextEvents.last
            assertEquals(chain, simChain, List.empty, null)

            And("When we delete the chain")
            store.delete(classOf[ProtoChain], chain.getId)

            Then("The observable completes")
            obs.await(1.second, 0) shouldBe true
            obs.getOnCompletedEvents should have size 1
        }

        scenario("Obtaining a chain with a jump rule") {
            Given("A topology with one chain containing a jump rule")
            val chainId = UUID.randomUUID()
            val jumpChainId = UUID.randomUUID()
            val jumpRule = buildAndStoreJumpRule(chainId, jumpChainId)
            val chain = buildAndStoreChain(chainId, List(jumpRule.getId))
            val jumpChain = buildAndStoreChain(jumpChainId, List.empty)

            When("We subscribe to the chain")
            val obs = new AwaitableObserver[SimChain](1)
            VirtualTopology.observable[SimChain](chain.getId).subscribe(obs)

            Then("We receive the chain with the jump rule and associated chain")
            obs.await(1.second, 1) shouldBe true
            val simChain = obs.getOnNextEvents.last
            assertEquals(chain, simChain, List(jumpRule), jumpChain)

            And("When we make the jump rule point to another chain")
            val newJumpChainId = UUID.randomUUID()
            val newJumpChain = buildAndStoreChain(newJumpChainId, List.empty)
            val updatedJumpRule = updateJumpRule(jumpRule, newJumpChain.getId)

            Then("We receive the chain with the new jump rule and associated chain")
            obs.await(1.second, 1) shouldBe true
            var updatedSimChain = obs.getOnNextEvents.last
            assertEquals(chain, updatedSimChain, List(updatedJumpRule),
                         newJumpChain)

            And("When we modify the chain the jump rule points to")
            val updatedJumpChain = createChainBuilder(newJumpChainId, "newname",
                                                      List.empty)
                .build()
            store.update(updatedJumpChain)

            Then("We receive the chain with the updated jump chain")
            obs.await(1.second, 1) shouldBe true
            updatedSimChain = obs.getOnNextEvents.last
            assertEquals(chain, updatedSimChain, List(updatedJumpRule),
                         updatedJumpChain)

            And("When we remove the jump rule from the chain")
            val updatedChain = removeRuleFromChain(jumpRule.getId, chain)

            Then("We receive the chain without any rules")
            obs.await(1.second, 0) shouldBe true
            updatedSimChain = obs.getOnNextEvents.last
            assertEquals(updatedChain, updatedSimChain, List.empty, null)
        }
    }

    private def assertChainHasRule(rule: ProtoRule, simRules: List[SimRule]) = {
        if (rule.hasJumpData) {
            val jmpRule = simRules.filter(_.isInstanceOf[JumpRule]).head
                .asInstanceOf[JumpRule]
            jmpRule.jumpToChainID shouldBe rule.getJumpData.getJumpTo.asJava

        } else if (rule.hasLiteralAction) {
            val literalRules = simRules.filter(_.isInstanceOf[LiteralRule])
                .asInstanceOf[List[LiteralRule]]
            literalRules.filter(
                _.action.name == rule.getLiteralAction.name) should not be empty
        } else
            throw new IllegalArgumentException(s"Type of rule $rule not supported")
    }

    // This method assumes that a chain never has two rules with the same action
    private def assertEquals(chain: ProtoChain, simChain: SimChain,
                             rules: List[ProtoRule], jumpChain: ProtoChain)
    : Unit = {
        chain.getId.asJava shouldBe simChain.id
        chain.getName shouldBe simChain.name

        var hasJumpRule = false

        // The two chains should have the same rules, where two rules are equal
        // if they have the same action.
        simChain.getRules.size shouldBe chain.getRuleIdsCount

        rules.foreach(rule => {
            assertChainHasRule(rule, simChain.getRules.toList)
            if (rule.hasJumpData) {
                assertEquals(jumpChain,
                             simChain.getJumpTarget(rule.getJumpData.getJumpTo),
                             List.empty, null)
                hasJumpRule = true
            }
        })
        if (!hasJumpRule)
            simChain.isJumpTargetsEmpty shouldBe true
    }

    private def updateJumpRule(oldJmpRule: ProtoRule, newJmpChainId: Commons.UUID)
    : ProtoRule = {
        val updatedJumpRule = oldJmpRule.toBuilder
            .setJumpData(JumpData.newBuilder
                             .setJumpTo(newJmpChainId)
                             .build)
            .build()
        store.update(updatedJumpRule)
        updatedJumpRule
    }

    private def buildAndStoreLiteralRule(action: ProtoRule.Action, chainId: UUID)
    : ProtoRule = {
        val rule = createLiteralRuleBuilder(UUID.randomUUID(), action, chainId)
            .build()
        store.create(rule)
        rule
    }

    private def buildAndStoreJumpRule(chainId: UUID, jumpChainId: UUID)
    : ProtoRule = {
        val jumpRule = createJumpRuleBuilder(UUID.randomUUID(), jumpChainId,
                                         chainId)
                           .build()
        store.create(jumpRule)
        jumpRule
    }

    private def addRuleToChain(rule: ProtoRule, chain: ProtoChain): ProtoChain = {
        val updatedChain = chain.toBuilder
            .addRuleIds(rule.getId)
            .build()
        store.update(updatedChain)
        updatedChain
    }

    private def removeRuleFromChain(ruleId: UUID, chain: ProtoChain)
    : ProtoChain = {
        val ruleList = new ListBuffer[Commons.UUID]()
        ruleList.addAll(chain.getRuleIdsList)
        ruleList.remove(ruleList.indexOf(ruleId.asProto))
        val updatedChain = chain.toBuilder
            .clearRuleIds()
            .addAllRuleIds(ruleList)
            .build()
        store.update(updatedChain)
        updatedChain
    }

    private def buildAndStoreChain(chainId: UUID, ruleIds: List[Commons.UUID])
    : ProtoChain = {
        val chain = createChainBuilder(chainId, "testChain", ruleIds)
            .build()
        store.create(chain)
        chain
    }
}
