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
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{Chain => ProtoChain, Rule => ProtoRule}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.FlowController
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
            val rule1 = buildAndStoreRule(ProtoRule.Action.ACCEPT, chainId)
            val chain = buildAndStoreChain(chainId, List(rule1.getId))

            When("We subscribe to the chain")
            val obs = new AwaitableObserver[SimChain](1)
            VirtualTopology.observable[SimChain](chain.getId).subscribe(obs)

            Then("We receive the chain with the rule")
            obs.await(1.second, 0) shouldBe true
            val simChain = obs.getOnNextEvents.last
            assertEquals(chain, simChain, List(rule1))

            And("When we add a 2nd rule to the chain")
            val rule2 = buildAndStoreRule(ProtoRule.Action.REJECT, chainId)
            var updatedChain = addRuleToChain(rule2, chain)

            Then("We receive the chain with the two rules")
            obs.await(1.second, 0) shouldBe true
            var updatedSimChain = obs.getOnNextEvents.last
            assertEquals(updatedChain, updatedSimChain, List(rule1, rule2))

            And("When we remove a rule")
            updatedChain = removeRuleFromChain(rule2.getId, updatedChain)

            Then("We receive the chain with only 1 rule")
            obs.await(1.second, 0) shouldBe true
            updatedSimChain = obs.getOnNextEvents.last
            assertEquals(updatedChain, updatedSimChain, List(rule1))

            And("When we delete the last rule")
            updatedChain = removeRuleFromChain(rule1.getId, updatedChain)

            Then("We receive the chain with an empty list of rules")
            obs.await(1.second, 0) shouldBe true
            updatedSimChain = obs.getOnNextEvents.last
            assertEquals(updatedChain, updatedSimChain, List.empty)
        }

        scenario("Deleting a chain") {
            Given("A topology with one chain")
            val chainId = UUID.randomUUID()
            val chain = buildAndStoreChain(chainId, List.empty)

            When("We subscribe to the chain")
            val obs = new AwaitableObserver[SimChain](1)
            VirtualTopology.observable[SimChain](chain.getId).subscribe(obs)

            Then("We receive the chain")
            obs.await(1.second, 0) shouldBe true
            val simChain = obs.getOnNextEvents.last
            assertEquals(chain, simChain, List.empty)

            And("When we delete the chain")
            store.delete(classOf[ProtoChain], chain.getId)

            Then("The observable completes")
            obs.await(1.second, 0) shouldBe true
            obs.getOnCompletedEvents should have size 1
        }
    }

    // This method assumes that a chain never has two rules with the same action
    private def assertEquals(chain: ProtoChain, simChain: SimChain,
                             rules: List[ProtoRule]) = {
        chain.getId.asJava shouldBe simChain.id
        chain.getName shouldBe simChain.name

        simChain.getRules.size shouldBe chain.getRuleIdsCount

        //TODO(nicolas): Should we check something else
        // The two chains have the same rules, where two rules are equal if they
        // have the same action.
        rules.foreach(rule => {
            simChain.getRules.filter(_.action.name == rule.getAction.name) should have size 1
        })
    }

    private def buildAndStoreRule(action: ProtoRule.Action, chainId: UUID): ProtoRule = {
        val rule = createRuleBuilder(UUID.randomUUID(), action, chainId)
            .build()
        store.create(rule)
        rule
    }

    private def addRuleToChain(rule: ProtoRule, chain: ProtoChain): ProtoChain = {
        val ruleList = new ListBuffer[Commons.UUID]()
        ruleList.add(rule.getId)
        ruleList.addAll(chain.getRuleIdsList)
        val updatedChain = createChainBuilder(chain.getId, chain.getName,
                                              ruleList.toList)
            .build()
        store.update(updatedChain)
        updatedChain
    }

    private def removeRuleFromChain(ruleId: UUID, chain: ProtoChain): ProtoChain = {
        val ruleList = new ListBuffer[Commons.UUID]()
        ruleList.addAll(chain.getRuleIdsList)
        ruleList.remove(ruleList.indexOf(ruleId.asProto))
        val updatedChain = createChainBuilder(chain.getId, chain.getName,
                                              ruleList.toList)
            .build()
        store.update(updatedChain)
        updatedChain
    }

    private def buildAndStoreChain(chainId: UUID, ruleIds: List[Commons.UUID]): ProtoChain = {
        val chain = createChainBuilder(chainId, "testChain", ruleIds)
            .build()
        store.create(chain)
        chain
    }
}
