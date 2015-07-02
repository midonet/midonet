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
package org.midonet.midolman

import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.rules.{LiteralRule, Condition, JumpRule, Rule}
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.simulation.{Chain, CustomMatchers}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.{InvalidateFlowsByTag, ChainRequest}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class ChainManagerTest extends TestKit(ActorSystem("ChainManagerTest"))
        with MidolmanSpec
        with ImplicitSender {

    var vta: TestableVTA = null

    registerActors(VirtualTopologyActor -> (() => new TestableVTA))

    protected override def beforeTest() {
        vta = VirtualTopologyActor.as[TestableVTA]
    }

    feature("ChainManager handles chain's rules") {
        scenario("Load chain with two rules") {
            Given("a chain with two rules")
            val chain = newChain("chain1")
            newTcpDstRuleOnChain(chain, 1, 80, Action.DROP)

            When("the VTA receives a request for it")
            vta.self ! ChainRequest(chain)

            Then("it should return the requested chain, including the rule")
            val c = expectMsgType[Chain]
            c.id shouldEqual chain
            c.getRules.size shouldBe 1
            checkTcpDstRule(c.getRules.get(0), 80, Action.DROP)
        }

        scenario("Receive update when a rule is added") {
            Given("a chain with one rule")
            val chain = newChain("chain1")
            newTcpDstRuleOnChain(chain, 1, 80, Action.DROP)

            When("the VTA receives a subscription request for it")
            vta.self ! ChainRequest(chain, update = true)

            And("it returns the first version of the chain")
            expectMsgType[Chain]
            vta.getAndClear()

            And("a new rule is added")
            newTcpDstRuleOnChain(chain, 2, 81, Action.ACCEPT)

            Then("the VTA should send an update")
            val c = expectMsgType[Chain]
            c.id shouldEqual chain
            c.getRules.size shouldBe 2
            checkTcpDstRule(c.getRules.get(1), 81, Action.ACCEPT)

            And("the VTA should receive a flow invalidation")
            vta.getAndClear() should contain (flowInvalidationMsg(c.id))
        }
    }

    feature("ChainManager loads target chains for jump rules") {
        scenario("Load chain with a jump to another chain") {
            Given("a chain with a jump to another chain")
            val chain1 = newChain("chain1")
            val chain2 = newChain("chain2")
            newJumpRuleOnChain(chain1, 1, new Condition(), chain2)

            When("the VTA receives a request for it")
            vta.self ! ChainRequest(chain1)

            Then("the VTA should return the first chain, which " +
                 "should have a reference to the second chain")
            val c = expectMsgType[Chain]
            c.id shouldEqual chain1
            c.getRules.size shouldBe 1
            checkJumpRule(c.getRules.get(0), chain2)
            c.getJumpTarget(chain2) shouldBe a [Chain]
        }

        scenario("Add a second jump rule to a chain") {
            Given("A chain with a jump to a second chain")
            val chain1 = newChain("chain1")
            val chain2 = newChain("chain2")
            newJumpRuleOnChain(chain1, 1, new Condition(), chain2)

            When("the VTA receives a request for it")
            vta.self ! ChainRequest(chain1, true)

            And("it returns the first version of the first chain")
            expectMsgType[Chain]
            vta.getAndClear()

            And("a second jump rule to a third chain is added")
            val chain3 = newChain("chain3")
            newJumpRuleOnChain(chain1, 2, new Condition(), chain3)

            Then("the VTA should send an update with both jumps")
            val c1 = expectMsgType[Chain]
            c1.id shouldEqual chain1
            c1.getRules.size shouldBe 2
            checkJumpRule(c1.getRules.get(0), chain2)
            checkJumpRule(c1.getRules.get(1), chain3)
            c1.getJumpTarget(chain2) should not be null
            c1.getJumpTarget(chain3) should not be null

            And("the VTA should receive a flow invalidation for the first chain")
            vta.getAndClear() should contain (flowInvalidationMsg(c1.id))
        }

        scenario("Add a jump to a third chain on the second chain") {
            Given("a chain with a jump to a second chain")
            val chain1 = newChain("chain1")
            val chain2 = newChain("chain2")
            newJumpRuleOnChain(chain1, 1, new Condition(), chain2)

            When("the VTA receives a request for it")
            vta.self ! ChainRequest(chain1, true)

            And("it returns the first version of the chain")
            expectMsgType[Chain]
            vta.getAndClear()

            And("a jump to a third chain is added to the second chain")
            val chain3 = newChain("chain3")
            newJumpRuleOnChain(chain2, 1, new Condition(), chain3)

            Then("the VTA should send an update with " +
                 "all three chains connected by jumps")
            val c1 = expectMsgType[Chain]
            c1.id shouldEqual chain1
            c1.getRules.size shouldBe 1
            checkJumpRule(c1.getRules.get(0), chain2)

            val c2 = c1.getJumpTarget(chain2)
            c2 should not be null
            c2.id shouldEqual chain2
            c2.getRules.size shouldBe 1
            checkJumpRule(c2.getRules.get(0), chain3)
            c2.getJumpTarget(chain3) should not be null

            And("the VTA should receive flow invalidations " +
                "for the first two chains")
            val msgs = vta.getAndClear()
            msgs should contain (flowInvalidationMsg(c1.id))
            msgs should contain (flowInvalidationMsg(c2.id))
        }

        scenario("Add a rule to a jump target chain") {
            Given("a chain with a jump to a second chain" +
                  "with a jump to a third chain")
            val chain1 = newChain("chain1")
            val chain2 = newChain("chain2")
            val chain3 = newChain("chain3")
            newJumpRuleOnChain(chain1, 1, new Condition(), chain2)
            newJumpRuleOnChain(chain2, 1, new Condition(), chain3)

            When("the VTA receives a request for it")
            vta.self ! ChainRequest(chain1, true)

            And("it returns the first version of the first chain")
            expectMsgType[Chain]

            And("a rule is added to the third chain")
            newTcpDstRuleOnChain(chain3, 1, 80, Action.DROP)

            Then("the VTA should send an update with all three chains " +
                 "connected by jumps and the new rule in the third chain")
            val c1 = expectMsgType[Chain]
            val c2 = c1.getJumpTarget(chain2)
            val c3 = c2.getJumpTarget(chain3)
            c3.getRules.size shouldBe 1
            checkTcpDstRule(c3.getRules.get(0), 80, Action.DROP)

            And("the VTA should receive flow invalidations for all three chains")
            val msgs = vta.getAndClear()
            msgs.contains(flowInvalidationMsg(c1.id))
            msgs.contains(flowInvalidationMsg(c2.id))
            msgs.contains(flowInvalidationMsg(c3.id))
        }
    }

    feature("ChainManager loads IPAddrGroups associated with its chain") {
        scenario("Load chain with a rule with one IPAddrGroup") {
            Given("a chain with a rule with one IPAddrGroup")
            val ipAddrGroup = createIpAddrGroup()
            val addr = "10.0.1.1"
            addAddrToIpAddrGroup(ipAddrGroup.getId, addr)

            val chain = newChain("chain1")
            newIpAddrGroupRuleOnChain(chain, 1, Action.DROP,
                                      Some(ipAddrGroup.getId), None)

            When("the VTA receives a request for it")
            vta.self ! ChainRequest(chain)

            Then("It returns the chain with the IPAddrGroup")
            val c = expectMsgType[Chain]
            c.getRules.size shouldBe 1
            checkIpAddrGroupRule(c.getRules.get(0), Action.DROP,
                                 ipAddrGroup.getId, Set(addr), null, null)
        }

        scenario("Only track IPAddrGroups used in rules") {
            Given("a chain with a rule with one IPAddrGroup")
            val ipAddrGroup = createIpAddrGroup()
            val addr = "10.0.1.1"
            addAddrToIpAddrGroup(ipAddrGroup.getId, addr)

            val chain = newChain("chain1")
            val rule = newIpAddrGroupRuleOnChain(
                chain, 1, Action.DROP, Some(ipAddrGroup.getId), None)

            When("the VTA receives a request for it")
            vta.self ! ChainRequest(chain, update = true)

            Then("It returns the chain with the IPAddrGroup")
            var c = expectMsgType[Chain]
            c.getRules.size shouldBe 1
            checkIpAddrGroupRule(c.getRules.get(0), Action.DROP,
                                 ipAddrGroup.getId, Set(addr), null, null)

            When("The rule is removed and the IP group is modified")
            deleteRule(rule.getId)
            removeIpAddrFromIpAddrGroup(ipAddrGroup.getId, addr)
            c = expectMsgType[Chain]
            c.getRules.size() should be (0)
            c = expectMsgType[Chain]
            c.getRules.size() should be (0)

            Then("We still get chain updates")
            newTcpDstRuleOnChain(chain, 1, 80, Action.DROP)
            c = expectMsgType[Chain]
            c.getRules.size() should be (1)
        }

        scenario("Add an address to an IPAddrGroup") {
            Given("A chain with a rule with one IPAddrGroup")
            val ipAddrGroup = createIpAddrGroup()
            val addr1 = "10.0.1.1"
            addAddrToIpAddrGroup(ipAddrGroup.getId, addr1)

            val chain = newChain("chain1")
            newIpAddrGroupRuleOnChain(chain, 1, Action.DROP,
                Some(ipAddrGroup.getId), None)

            When("the VTA receives a request for it")
            vta.self ! ChainRequest(chain, true)

            And("it returns the first version of the chain")
            expectMsgType[Chain]
            vta.getAndClear()

            And("a second address is added to the IPAddrGroup")
            val addr2 = "10.0.1.2"
            addAddrToIpAddrGroup(ipAddrGroup.getId, addr2)

            Then("the VTA should send an update")
            val c = expectMsgType[Chain]
            c.getRules.size shouldBe 1
            checkIpAddrGroupRule(c.getRules.get(0), Action.DROP,
                                 ipAddrGroup.getId, Set(addr1, addr2),
                                 null, null)

            And("the VTA should receive a flow invalidation for the chain")
            vta.getAndClear() should contain (flowInvalidationMsg(c.id))
        }

        scenario("Remove an address from an IPAddrGroup") {
            Given("A chain with a rule with one IPAddrGroup with two rules")
            val ipAddrGroup = createIpAddrGroup()
            val addr1 = "10.0.1.1"
            val addr2 = "10.0.1.2"
            addAddrToIpAddrGroup(ipAddrGroup.getId, addr1)
            addAddrToIpAddrGroup(ipAddrGroup.getId, addr2)

            val chain = newChain("chain1")
            newIpAddrGroupRuleOnChain(chain, 1, Action.DROP,
                None, Some(ipAddrGroup.getId))

            When("the VTA receives a request for it")
            vta.self ! ChainRequest(chain, true)

            And("it returns the first version of the chain")
            val c1 = expectMsgType[Chain]
            checkIpAddrGroupRule(c1.getRules.get(0), Action.DROP, null, null,
                                 ipAddrGroup.getId, Set(addr1, addr2))
            vta.getAndClear()

            And("an address is removed from the IPAddrGroup")
            removeAddrFromIpAddrGroup(ipAddrGroup.getId, addr1)

            Then("the VTA should send an update")
            val c2 = expectMsgType[Chain]
            c2.id shouldEqual chain
            checkIpAddrGroupRule(c2.getRules.get(0), Action.DROP, null, null,
                                 ipAddrGroup.getId, Set(addr2))

            And("the VTA should receive a flow invalidation for the chain")
            vta.getAndClear() should contain (flowInvalidationMsg(c2.id))
        }
    }

    private def checkIpAddrGroupRule(r: Rule, action: Action,
                                     dstId: UUID, dstAddrs: Set[String],
                                     srcId: UUID, srcAddrs: Set[String]) {
        r shouldBe a [LiteralRule]
        r.action shouldBe action
        val c = r.getCondition
        if (dstId != null) {
            c.ipAddrGroupIdDst shouldEqual dstId
            c.ipAddrGroupDst should not be null
            c.ipAddrGroupDst.id shouldEqual dstId
            c.ipAddrGroupDst.addrs.map(_.toString) shouldEqual dstAddrs
        } else {
            c.ipAddrGroupDst shouldBe null
        }

        if (srcId != null) {
            c.ipAddrGroupIdSrc shouldEqual srcId
            c.ipAddrGroupSrc should not be null
            c.ipAddrGroupSrc.id shouldEqual srcId
            c.ipAddrGroupSrc.addrs.map(_.toString) shouldEqual srcAddrs
        } else {
            c.ipAddrGroupSrc shouldBe null
        }
    }


    private def checkTcpDstRule(r: Rule, tpDst: Int, action: Action) {
        r shouldBe a [LiteralRule]
        val range = r.getCondition.tpDst
        range should not be null
        range.start shouldEqual tpDst
        range.end shouldEqual tpDst
        r.action shouldBe action
    }

    private def checkJumpRule(r: Rule, jumpToId: UUID) {
        r shouldBe a [JumpRule]
        val jr1 = r.asInstanceOf[JumpRule]
        jr1.jumpToChainID shouldEqual jumpToId
    }

    def flowInvalidationMsg(id: UUID) =
        InvalidateFlowsByTag(FlowTagger.tagForDevice(id))
}

class TestableVTA extends VirtualTopologyActor with MessageAccumulator {

}



