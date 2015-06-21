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

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.Rule.JumpRuleData
import org.midonet.cluster.models.Topology.{Chain => ProtoChain, IPAddrGroup => ProtoIPAddrGroup, Rule => ProtoRule}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.rules.{Rule => SimRule}
import org.midonet.midolman.simulation.{Chain => SimChain}
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPAddr

@RunWith(classOf[JUnitRunner])
class ChainMapperTest extends TestKit(ActorSystem("ChainMapperTest"))
                      with MidolmanSpec
                      with TopologyBuilder
                      with TopologyMatchers {

    import TopologyBuilder._

    private var vt: VirtualTopology = _
    private implicit var store: Storage = _
    private val timeout = 5 second

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    private def subscribeToChain(count: Int, chainId: UUID) = {
        val mapper = new ChainMapper(chainId, vt)
        val obs = new DeviceObserver[SimChain](vt)
        val subscription = Observable.create(mapper).subscribe(obs)
        (subscription, obs)
    }

    feature("Obtaining a chain with its observable") {
        scenario("A chain with one literal rule") {
            Given("A topology with one chain containing one rule")
            val chainId = UUID.randomUUID()
            val rule1 = buildAndStoreLiteralRule(chainId,
                                                 ProtoRule.Action.ACCEPT)
            val chain = buildAndStoreChain(chainId, "test-chain",
                                           Set(rule1.getId))

            When("We subscribe to the chain")
            val (subscription, obs) = subscribeToChain(count = 1, chainId)

            Then("We receive only one update with the chain with the rule")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            val simChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, simChain, List(rule1), null)

            And("When we add a 2nd rule to the chain")
            val rule2 = buildAndStoreLiteralRule(chainId,
                                                 ProtoRule.Action.REJECT)
            var updatedChain = addRuleToChain(rule2, chain)

            Then("We receive the chain with the two rules")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            var updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(updatedChain, updatedSimChain, List(rule1, rule2), null)

            And("When we remove a rule")
            updatedChain = removeRuleFromChain(rule2.getId, updatedChain)

            Then("We receive the chain with only 1 rule")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents should have size 3
            updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(updatedChain, updatedSimChain, List(rule1), null)

            And("When we delete the last rule")
            updatedChain = removeRuleFromChain(rule1.getId, updatedChain)

            Then("We receive the chain with an empty list of rules")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents should have size 4
            updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(updatedChain, updatedSimChain, List.empty, null)
        }

        scenario("Obtaining a chain with get") {
            Given("A topology with one chain containing one rule")
            val chainId = UUID.randomUUID()
            val rule = buildAndStoreLiteralRule(chainId,
                                                ProtoRule.Action.ACCEPT)
            val chain = buildAndStoreChain(chainId, "test-chain",
                                           Set(rule.getId))

            When("We ask for the chain with a get")
            val future = VirtualTopology.get[SimChain](chainId)

            Then("We obtain the chain with its rule")
            val simChain = Await.result[SimChain](future, timeout)
            assertEquals(chain, simChain, List(rule), null)
        }

        scenario("Obtaining a chain with tryGet") {
            Given("A topology with one chain containing one rule")
            val chainId = UUID.randomUUID()
            val rule = buildAndStoreLiteralRule(chainId,
                                                ProtoRule.Action.ACCEPT)
            val chain = buildAndStoreChain(chainId, "test-chain",
                                           Set(rule.getId))

            When("We ask for the chain with tryGet")
            val nye = intercept[NotYetException] {
                VirtualTopology.tryGet[SimChain](chainId)
            }
            val simChain = Await.result(nye.waitFor, timeout).asInstanceOf[SimChain]
            assertEquals(chain, simChain, List(rule), null)
        }

        scenario("Obtaining a chain that does not exist") {
            Given("A non-existing chain")
            val chainId = UUID.randomUUID()

            When("We we ask for a non-existing chain")
            intercept[NotFoundException] {
                Then("A NotFoundException is raised")
                val future = VirtualTopology.get[SimChain](chainId)
                Await.result(future, timeout)
            }
        }

        scenario("Deleting a chain") {
            Given("A topology with one chain")
            val chainId = UUID.randomUUID()
            val chain = buildAndStoreChain(chainId, "test-chain", Set.empty)

            When("We subscribe to the chain")
            val (subscription, obs) = subscribeToChain(count = 1, chainId)

            Then("We receive the chain")
            obs.awaitOnNext(1, timeout) shouldBe true
            val simChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, simChain, List.empty, null)

            And("When we delete the chain")
            store.delete(classOf[ProtoChain], chain.getId)

            Then("The observable completes")
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should have size 1
        }

        scenario("Obtaining a chain with a jump rule") {
            Given("A topology with one chain containing a jump rule")
            val chainId = UUID.randomUUID()
            val jumpChainId = UUID.randomUUID()
            val jumpChain = buildAndStoreChain(jumpChainId, "jump-chain",
                                               Set.empty)
            val jumpRule = buildAndStoreJumpRule(chainId, jumpChainId)
            val chain = buildAndStoreChain(chainId, "test-chain",
                                           Set(jumpRule.getId))

            When("We subscribe to the chain")
            val (_, obs) = subscribeToChain(count = 1, chainId)


            Then("We receive the chain with the jump rule and associated chain")
            obs.awaitOnNext(1, timeout) shouldBe true
            val simChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, simChain, List(jumpRule), jumpChain)

            And("When we make the jump rule point to another chain")
            val newJumpChainId = UUID.randomUUID()
            val newJumpChain = buildAndStoreChain(newJumpChainId, "jump-chain2",
                                                  Set.empty)
            val updatedJumpRule = updateJumpRule(jumpRule, chainId,
                                                 newJumpChain.getId)

            Then("We receive the chain with the new jump rule and associated chain")
            obs.awaitOnNext(2, timeout) shouldBe true
            var updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, updatedSimChain, List(updatedJumpRule),
                         newJumpChain)

            And("When we modify the chain the jump rule points to")
            val updatedJumpChain = createChain(newJumpChainId,
                                               Option("jump-chain3"), Set.empty)
            store.update(updatedJumpChain)

            Then("We receive the chain with the updated jump chain")
            obs.awaitOnNext(3, timeout) shouldBe true
            updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, updatedSimChain, List(updatedJumpRule),
                         updatedJumpChain)

            And("When we remove the jump rule from the chain")
            val updatedChain = removeRuleFromChain(jumpRule.getId, chain)

            Then("We receive the chain without any rules")
            obs.awaitOnNext(4, timeout) shouldBe true
            updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(updatedChain, updatedSimChain, List.empty,
                         jumpChain = null)

            And("When we update the jump rule and the chain")
            store.update(jumpRule.toBuilder
                             .setInvDlDst(true)
                             .build())
            store.update(updatedChain.toBuilder
                             .setName("test-chain2")
                             .build())

            Then("We receive only one update")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents should have size 5

            And("When we update the jump chain and the chain")
            store.update(jumpChain.toBuilder
                             .setName("jump-chain4")
                             .build())
            store.update(updatedChain.toBuilder
                             .setName("test-chain3")
                             .build())

            Then("We receive only one update")
            obs.awaitOnNext(6, timeout) shouldBe true
            obs.getOnNextEvents should have size 6
        }

        scenario("Two jump rules point to the same chain") {
            Given("A topology with one chain containing a jump rule")
            val chainId = UUID.randomUUID()
            val jumpChainId = UUID.randomUUID()
            val jumpChain = buildAndStoreChain(jumpChainId, "jump-chain",
                                               Set.empty)
            val jumpRule1 = buildAndStoreJumpRule(chainId, jumpChainId)
            val chain = buildAndStoreChain(chainId, "test-chain",
                                           Set(jumpRule1.getId))

            When("We subscribe to the chain")
            val (_, obs) = subscribeToChain(count = 1, chainId)

            Then("We receive the chain with the jump rule and associated chain")
            obs.awaitOnNext(1, timeout) shouldBe true
            var simChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, simChain, List(jumpRule1), jumpChain)

            And("When we add a 2nd jump pointing to the same chain")
            val jumpRule2 = buildAndStoreJumpRule(chainId, jumpChainId)
            var updatedChain = addRuleToChain(jumpRule2, chain)

            Then("We receive the chain with the two jump rules")
            obs.awaitOnNext(2, timeout) shouldBe true
            simChain = obs.getOnNextEvents.asScala.last
            assertEquals(updatedChain, simChain, List(jumpRule1, jumpRule2),
                         jumpChain)

            And("When we remove the 1st jump rule and update the jump chain")
            updatedChain = removeRuleFromChain(jumpRule1.getId, updatedChain)

            Then("We receive the updated chain")
            obs.awaitOnNext(3, timeout) shouldBe true
            simChain = obs.getOnNextEvents.asScala.last
            assertEquals(updatedChain, simChain, List(jumpRule2), jumpChain)

            And("When we update the jump chain")
            val updatedJumpChain = jumpChain.toBuilder
                .setName("jump-chain2")
                .build()
            store.update(updatedJumpChain)

            Then("We receive the updated chain")
            obs.awaitOnNext(4, timeout) shouldBe true
            simChain = obs.getOnNextEvents.asScala.last
            assertEquals(updatedChain, simChain, List(jumpRule2),
                         updatedJumpChain)

            And("When we remove the 2nd jump rule")
            updatedChain = removeRuleFromChain(jumpRule2.getId, updatedChain)

            Then("We receive the chain with no rules")
            obs.awaitOnNext(5, timeout) shouldBe true
            simChain = obs.getOnNextEvents.asScala.last
            assertEquals(updatedChain, simChain, List.empty, jumpChain = null)

            And("When we update the jump chain and the main chain")
            store.update(updatedJumpChain.toBuilder
                .setName("jump-chain3")
                .build())
            store.update(updatedChain.toBuilder
                .setName("test-chain2")
                .build())

            Then("We receive only one update")
            obs.awaitOnNext(6, timeout) shouldBe true
            obs.getOnNextEvents should have size 6
        }

        scenario("A chain with a rule that references an IPAddrGroup") {
            Given("A chain with one rule")
            val chainId = UUID.randomUUID()
            val ipAddrGroupSrc = buildAndStoreIPAddrGroup("192.168.0.1",
                                                          "ipAddrGroupSrc")
            val ipAddrGroupDst = buildAndStoreIPAddrGroup("192.168.0.2",
                                                          "ipAddrGroupDst")
            val rule = buildAndStoreLiteralRule(chainId, ProtoRule.Action.ACCEPT,
                                                Some(ipAddrGroupSrc.getId.asJava),
                                                Some(ipAddrGroupDst.getId.asJava))
            val chain = buildAndStoreChain(chainId, "test-chain",
                                           Set(rule.getId.asJava))

            When("We subscribe to the chain")
            val (_, obs) = subscribeToChain(count = 1, chainId)

            Then("We receive the chain with the rule and associated IPAddrGroups")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            val simChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, simChain, List(rule), jumpChain = null,
                         Map(ipAddrGroupSrc.getId.asJava -> ipAddrGroupSrc,
                             ipAddrGroupDst.getId.asJava -> ipAddrGroupDst))

            When("We update IPAddrGroupSrc")
            val updatedIPAddrGrpSrc = ipAddrGroupSrc.toBuilder
                .setName("ipAddrGroupSrc2")
                .build()
            store.update(updatedIPAddrGrpSrc)

            Then("We receive the chain with the updated IPAddrGroupSrc")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            var updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, updatedSimChain, List(rule), jumpChain = null,
                         Map(ipAddrGroupSrc.getId.asJava -> updatedIPAddrGrpSrc,
                             ipAddrGroupDst.getId.asJava -> ipAddrGroupDst))

            When("We remove the rule from the chain")
            val updatedChain = removeRuleFromChain(rule.getId.asJava, chain)

            Then("We receive the chain with no rules")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents should have size 3
            updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(updatedChain, updatedSimChain, rules = List.empty,
                         jumpChain = null, ipAddrGroups = Map.empty)

            When("We update the IPAddrGroup referenced by the rule and the main chain")
            store.update(ipAddrGroupSrc.toBuilder
                             .setName("toto")
                             .build())
            store.update(updatedChain.toBuilder
                             .setName("test-chain2")
                             .build())

            Then("We receive only one update")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents should have size 4

            When("We update the rule and the main chain")
            store.update(rule.toBuilder
                             .setAction(ProtoRule.Action.CONTINUE)
                             .build())
            store.update(updatedChain.toBuilder
                             .setName("test-chain3")
                             .build())

            Then("We receive only one update")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents should have size 5
        }

        scenario("A chain with two rules pointing to the same IPAddrGroup") {
            Given("A chain with two rules")
            val chainId = UUID.randomUUID()
            val ipAddrGroupSrc = buildAndStoreIPAddrGroup("192.168.0.1",
                                                          "ipAddrGroupSrc")
            val ipAddrGroupSrcId = ipAddrGroupSrc.getId.asJava
            val rule1 = buildAndStoreLiteralRule(chainId, ProtoRule.Action.ACCEPT,
                                                 Some(ipAddrGroupSrc.getId.asJava))
            val rule2 = buildAndStoreLiteralRule(chainId, ProtoRule.Action.ACCEPT,
                                                 Some(ipAddrGroupSrc.getId.asJava))
            val chain = buildAndStoreChain(chainId, "test-chain",
                                           Set(rule1.getId.asJava,
                                               rule2.getId.asJava))

            When("We subscribe to the chain")
            val (_, obs) = subscribeToChain(count = 1, chainId)

            Then("We receive the chain with the rules and associated IPAddrGroup")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            val simChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, simChain, List(rule1, rule2),
                         jumpChain = null,
                         Map(ipAddrGroupSrcId -> ipAddrGroupSrc))

            When("We remove rule1 from the chain")
            var updatedChain = removeRuleFromChain(rule1.getId, chain)

            Then("We receive the updated chain")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            var updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, updatedSimChain, List(rule2), jumpChain = null,
                         Map(ipAddrGroupSrcId -> ipAddrGroupSrc))

            When("We update IPAddrGroupSrc")
            val updatedIPAddrGrpSrc = ipAddrGroupSrc.toBuilder
                .setName("ipAddrGroupSrc2")
                .build()
            store.update(updatedIPAddrGrpSrc)

            Then("We receive the chain with the updated IPAddrGroupSrc")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents should have size 3
            updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, updatedSimChain, List(rule2), jumpChain = null,
                         Map(ipAddrGroupSrcId -> updatedIPAddrGrpSrc))

            When("We remove rule2 from the chain")
            updatedChain = removeRuleFromChain(rule2.getId, updatedChain)

            Then("We receive the updated chain")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents should have size 4
            updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(updatedChain, updatedSimChain, rules = List.empty,
                         jumpChain = null, ipAddrGroups = Map.empty)

            When("We update IPAddrGroupSrc and the main chain")
            store.update(ipAddrGroupSrc.toBuilder
                             .setName("ipAddrGroupSrc3")
                             .build())
            store.update(updatedChain.toBuilder
                             .setName("test-chain2")
                             .build())

            Then("We receive only one update")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents should have size 5
        }

        scenario("A chain with a rule that changes its IPAddrGroup reference") {
            Given("A chain with one rule")
            val chainId = UUID.randomUUID()
            val rule = buildAndStoreLiteralRule(chainId, ProtoRule.Action.ACCEPT)
            val chain = buildAndStoreChain(chainId, "test-chain",
                                           Set(rule.getId.asJava))

            When("We subscribe to the chain")
            val (_, obs) = subscribeToChain(count = 1, chainId)

            Then("We receive the chain with the rule")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            val simChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, simChain, List(rule), jumpChain = null)

            And("When we add two IPAddrGroup to the rule")
            var ipAddrGroupSrc = buildAndStoreIPAddrGroup("192.168.0.1",
                                                          "ipAddrGroupSrc")
            val ipAddrGroupDst = buildAndStoreIPAddrGroup("192.168.0.2",
                                                          "ipAddrGroupDst")
            var updatedRule = setCondition(rule.toBuilder,
                ipAddrGroupIdSrc = Some(ipAddrGroupSrc.getId),
                ipAddrGroupIdDst = Some(ipAddrGroupDst.getId)).build()
            store.update(updatedRule)

            Then("We receive the rule with the two IPAddrGroups")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            var updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, updatedSimChain, List(updatedRule),
                         jumpChain = null,
                         Map(ipAddrGroupSrc.getId.asJava -> ipAddrGroupSrc,
                             ipAddrGroupDst.getId.asJava -> ipAddrGroupDst))

            And("When we remove the two IPAddrGroups from the rule")
            updatedRule = rule.toBuilder
                              .clearIpAddrGroupIdSrc()
                              .clearIpAddrGroupIdDst()
                              .build()
            store.update(updatedRule)

            Then("We receive the rule with no IPAddrGroups")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents should have size 3
            updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, updatedSimChain, List(updatedRule),
                         jumpChain = null)

            And("When we add a source IPAddrGroup to the rule")
            ipAddrGroupSrc = buildAndStoreIPAddrGroup("192.168.0.3",
                                                      "ipAddrGroupSrc")
            updatedRule =
                setCondition(updatedRule.toBuilder,
                             ipAddrGroupIdSrc = Some(ipAddrGroupSrc.getId))
                    .build()
            store.update(updatedRule)

            Then("We receive the rule with the new source IPAddrGroup")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents should have size 4
            updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, updatedSimChain, List(updatedRule),
                         jumpChain = null,
                         Map(ipAddrGroupSrc.getId.asJava -> ipAddrGroupSrc))
            updatedSimChain.getRules.get(0)
                .getCondition.ipAddrGroupIdDst shouldBe null
            updatedSimChain.getRules.get(0)
                .getCondition.ipAddrGroupDst shouldBe null

            And("When we change the source IPAddrGroup reference")
            ipAddrGroupSrc = buildAndStoreIPAddrGroup("192.168.0.4",
                                                      "ipAddrGroupSrc")
            updatedRule =
                setCondition(updatedRule.toBuilder,
                             ipAddrGroupIdSrc = Some(ipAddrGroupSrc.getId))
                    .build()
            store.update(updatedRule)

            Then("We receive the rule with the updated source IPAddrGroup")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents should have size 5
            updatedSimChain = obs.getOnNextEvents.asScala.last
            assertEquals(chain, updatedSimChain, List(updatedRule),
                         jumpChain = null,
                         Map(ipAddrGroupSrc.getId.asJava -> ipAddrGroupSrc))
            updatedSimChain.getRules.get(0)
                .getCondition.ipAddrGroupIdDst shouldBe null
            updatedSimChain.getRules.get(0)
                .getCondition.ipAddrGroupDst shouldBe null

            And("When we update the rule's IPAddrGroupIdSrc with the previous id")
            store.update(updatedRule)
            And("We update the rule")
            store.update(updatedRule.toBuilder
                                    .setAction(ProtoRule.Action.CONTINUE)
                                    .build())

            Then("We receive a single notification")
            obs.awaitOnNext(6, timeout) shouldBe true
            obs.getOnNextEvents should have size 6
        }
    }

    private def assertEquals(chain: ProtoChain, simChain: SimChain,
                             rules: List[ProtoRule], jumpChain: ProtoChain,
                             ipAddrGroups: Map[UUID, ProtoIPAddrGroup] = Map.empty)
    : Unit = {
        chain.getId.asJava shouldBe simChain.id
        chain.getName shouldBe simChain.name

        // Checking rules
        simChain.getRules should contain theSameElementsAs
            rules.map(ZoomConvert.fromProto(_, classOf[SimRule]))

        val jumpRules = rules.filter(_.getType == ProtoRule.Type.JUMP_RULE)
        jumpRules.foreach(jmpRule =>
            assertEquals(jumpChain,
                         simChain.getJumpTarget(jmpRule.getJumpRuleData.getJumpTo),
                         List.empty, jumpChain = null)
        )
        if (jumpRules.isEmpty)
            simChain.isJumpTargetsEmpty shouldBe true

        // Checking ipAddrGroups
        val ipAddrGroupIds = new mutable.HashSet[UUID]()
        simChain.getRules.asScala.foreach(rule => {
            val cond = rule.getCondition
            if (cond.ipAddrGroupIdSrc ne null) {
                ipAddrGroupIds += cond.ipAddrGroupIdSrc
                cond.ipAddrGroupSrc shouldBeDeviceOf
                    ipAddrGroups(cond.ipAddrGroupIdSrc)
            }
            if (cond.ipAddrGroupIdDst ne null) {
                ipAddrGroupIds += cond.ipAddrGroupIdDst
                cond.ipAddrGroupDst shouldBeDeviceOf
                    ipAddrGroups(cond.ipAddrGroupIdDst)
            }
        })
        ipAddrGroupIds should have size ipAddrGroups.size
    }

    private def updateJumpRule(oldJmpRule: ProtoRule, chainId: Commons.UUID,
                               newJmpChainId: Commons.UUID)
    : ProtoRule = {
        val updatedJumpRule = oldJmpRule.toBuilder
            .setChainId(chainId)
            .setJumpRuleData(JumpRuleData.newBuilder
                                 .setJumpTo(newJmpChainId)
                                 .build)
            .build()
        store.update(updatedJumpRule)
        updatedJumpRule
    }

    private def buildAndStoreIPAddrGroup(ip: String, name: String)
    : ProtoIPAddrGroup = {
        val ipAddrGroup = createIPAddrGroup(name = Some(name))
            .addIPAddrPort(IPAddr.fromString(ip), Set(UUID.randomUUID))
        store.create(ipAddrGroup)
        ipAddrGroup
    }

    private def buildAndStoreLiteralRule(chainId: UUID,
                                         action: ProtoRule.Action,
                                         ipAddrGroupIdSrc: Option[UUID] = None,
                                         ipAddrGroupIdDst: Option[UUID] = None)
    : ProtoRule = {
        val builder = createLiteralRuleBuilder(UUID.randomUUID(),
                                               chainId = Some(chainId),
                                               action = Some(action))
        val rule = setCondition(builder, ipAddrGroupIdSrc = ipAddrGroupIdSrc,
                                ipAddrGroupIdDst = ipAddrGroupIdDst)
            .build()
        store.create(rule)
        rule
    }

    private def buildAndStoreJumpRule(chainId: UUID, jumpChainId: UUID)
    : ProtoRule = {
        val jumpRule = createJumpRuleBuilder(UUID.randomUUID(),
                                             chainId = Some(chainId),
                                             jumpChainId = Some(jumpChainId))
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
        val index = chain.getRuleIdsList.indexOf(ruleId.asProto)
        val updatedChain = chain.toBuilder.removeRuleIds(index).build()
        store.update(updatedChain)
        updatedChain
    }

    private def buildAndStoreChain(chainId: UUID, name: String,
                                   ruleIds: Set[UUID]): ProtoChain = {
        val chain = createChain(chainId, Some(name), ruleIds)
        store.create(chain)
        chain
    }
}
