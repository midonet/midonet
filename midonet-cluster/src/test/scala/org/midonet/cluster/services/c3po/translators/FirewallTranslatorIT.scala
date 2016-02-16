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

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Firewall => FirewallType}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.{Chain, Router, Rule}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class FirewallTranslatorIT extends C3POMinionTestBase with ChainManager {
    import FirewallTranslator._

    it should "handle firewall create with admin state down" in {
        val fwId = UUID.randomUUID()
        val fwjson = firewallJson(fwId, adminStateUp = false)

        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = false))
    }

    it should "handle firewall create with admin state up" in {
        val fwId = UUID.randomUUID()
        val fwjson = firewallJson(fwId, adminStateUp = true)

        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = true))
    }

    it should "handle firewall create with rules" in {
        val rule1Id = UUID.randomUUID()
        val rule1json = firewallRuleJson(rule1Id, enabled = true,
                                         position = 1)

        val rule2Id = UUID.randomUUID()
        val rule2json = firewallRuleJson(rule2Id, enabled = false,
                                         position = 2)

        val fwId = UUID.randomUUID()
        val fwjson = firewallJson(fwId,
                                  firewallRuleList=List(rule1json, rule2json))

        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))
    }

    it should "handle firewall create with router assoc/disassoc" in {
        val rtr1Id = UUID.randomUUID()
        createRouter(2, rtr1Id)

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId, addRouterIds = List(rtr1Id))

        insertCreateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(rtr1Id),
                                    delRtrIds = List()))

        fwjson = firewallUpdateJson(fwId, delRouterIds = List(rtr1Id))
        insertUpdateTask(4, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(),
                                    delRtrIds = List(rtr1Id)))
    }

    it should "handle firewall router assoc that's already associated" in {
        val rtr1Id = UUID.randomUUID()
        createRouter(2, rtr1Id)

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId, addRouterIds = List(rtr1Id))

        insertCreateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(rtr1Id),
                                    delRtrIds = List()))

        fwjson = firewallUpdateJson(fwId, addRouterIds = List(rtr1Id))
        insertUpdateTask(4, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(rtr1Id),
                                    delRtrIds = List()))
    }

    it should "handle firewall router disassoc that's not associated" in {
        val rtr1Id = UUID.randomUUID()
        createRouter(2, rtr1Id)

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId)

        insertCreateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(),
                                    delRtrIds = List()))

        fwjson = firewallUpdateJson(fwId, delRouterIds = List(rtr1Id))
        insertUpdateTask(4, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(),
                                    delRtrIds = List()))
    }

    it should "handle firewall update from admin state down to up" in {
        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId, adminStateUp = false)

        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = false))

        fwjson = firewallUpdateJson(fwId, adminStateUp = true)
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = true))
    }

    it should "handle firewall update from admin state up to down" in {
        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId, adminStateUp = true)

        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = true))

        fwjson = firewallUpdateJson(fwId, adminStateUp = false)
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = false))
    }

    it should "handle firewall update with rules added" in {
        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId, firewallRuleList=List())

        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId))

        val rule1Id = UUID.randomUUID()
        val rule1json = firewallRuleJson(rule1Id, enabled = true,
                                         position = 1)

        val rule2Id = UUID.randomUUID()
        val rule2json = firewallRuleJson(rule2Id, enabled = false,
                                         position = 2)

        fwjson = firewallUpdateJson(fwId,
                                    firewallRuleList = List(rule1json,
                                                            rule2json))

        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))
    }

    it should "handle firewall update with rules removed" in {
        val rule1Id = UUID.randomUUID()
        val rule1json = firewallRuleJson(rule1Id)

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId,
                                  firewallRuleList=List(rule1json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))

        fwjson = firewallUpdateJson(fwId, firewallRuleList = List())
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId))
    }

    it should "handle firewall update with rule order changed" in {
        val rule1Id = UUID.randomUUID()
        var rule1json = firewallRuleJson(rule1Id, position = 1)

        val rule2Id = UUID.randomUUID()
        var rule2json = firewallRuleJson(rule2Id, position = 2)

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId,
                                  firewallRuleList=List(rule1json, rule2json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id, rule2Id)))

        // Update the position values and reverse the order
        rule1json = firewallRuleJson(rule1Id, position = 2)
        rule2json = firewallRuleJson(rule2Id, position = 1)
        fwjson = firewallUpdateJson(fwId,
                                    firewallRuleList=List(rule2json,
                                                          rule1json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule2Id, rule1Id)))
    }

    it should "handle firewall update with rule update" in {
        // This test does a light verification that an update took place.  It
        // is not meant to check the validity of the updated values.  Tests
        // check for the rule translation exist in a different test class.
        val rule1Id = UUID.randomUUID()
        var rule1json = firewallRuleJson(rule1Id, action="deny")

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId,
                                  firewallRuleList=List(rule1json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually {
            validateFirewall(fwId, List(rule1Id))
            val r = storage.get(classOf[Rule], rule1Id).await()
            r.getAction shouldBe Action.DROP
        }

        rule1json = firewallRuleJson(rule1Id, action = "allow")

        fwjson = firewallUpdateJson(fwId, firewallRuleList = List(rule1json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually {
            validateFirewall(fwId, List(rule1Id))
            val r = storage.get(classOf[Rule], rule1Id).await()
            r.getAction shouldBe Action.RETURN
        }
    }

    it should "handle firewall update with rule replaced" in {
        val rule1Id = UUID.randomUUID()
        val rule1json = firewallRuleJson(rule1Id, position = 1)

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId, firewallRuleList=List(rule1json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))

        val rule2Id = UUID.randomUUID()
        val rule2json = firewallRuleJson(rule2Id, position = 1)
        fwjson = firewallUpdateJson(fwId, firewallRuleList=List(rule2json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule2Id)))
    }

    it should "handle firewall update with rule disabled" in {
        val rule1Id = UUID.randomUUID()
        var rule1json = firewallRuleJson(rule1Id, enabled = true)

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId, firewallRuleList=List(rule1json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))

        rule1json = firewallRuleJson(rule1Id, enabled = false)
        fwjson = firewallUpdateJson(fwId, firewallRuleList=List(rule1json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId))
    }

    it should "handle firewall update with rule enabled" in {
        val rule1Id = UUID.randomUUID()
        var rule1json = firewallRuleJson(rule1Id, enabled = false)

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId, firewallRuleList=List(rule1json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId))

        rule1json = firewallRuleJson(rule1Id, enabled = true)
        fwjson = firewallUpdateJson(fwId, firewallRuleList=List(rule1json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))
    }

    it should "handle firewall update without create" in {
        val rule1Id = UUID.randomUUID()
        val rule1json = firewallRuleJson(rule1Id)
        val fwId = UUID.randomUUID()
        val fwjson = firewallUpdateJson(fwId, firewallRuleList=List(rule1json))

        insertUpdateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))
    }

    it should "handle firewall update with last-router=true" in {
        val rtr1Id = UUID.randomUUID()
        createRouter(2, rtr1Id)

        val rule1Id = UUID.randomUUID()
        val rule1json = firewallRuleJson(rule1Id)

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId, firewallRuleList=List(rule1json),
                                  addRouterIds = List(rtr1Id))
        insertCreateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id),
                                    addRtrIds = List(rtr1Id)))
        fwjson = firewallUpdateJson(fwId, firewallRuleList=List(rule1json),
                                    lastRouter=true)
        insertUpdateTask(4, FirewallType, fwjson, fwId)
        eventually(validateFirewallNotExist(fwId, routerIds = List(rtr1Id)))
    }

    it should "handle firewall deletion" in {
        val rtr1Id = UUID.randomUUID()
        createRouter(2, rtr1Id)

        val rule1Id = UUID.randomUUID()
        val rule1json = firewallRuleJson(rule1Id)

        val fwId = UUID.randomUUID()
        val fwjson = firewallJson(fwId, firewallRuleList=List(rule1json),
                                  addRouterIds = List(rtr1Id))
        insertCreateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id),
                                    addRtrIds = List(rtr1Id)))

        insertDeleteTask(4, FirewallType, fwId)
        eventually(validateFirewallNotExist(fwId, routerIds = List(rtr1Id)))
    }

    private def validateFirewallNotExist(fwId: UUID,
                                         routerIds: List[UUID] = List()): Unit = {
        val protoFwId = UUIDUtil.toProto(fwId)
        val fwFwdChainId = fwdChainId(protoFwId)

        // Verify that the chains are gone
        storage.exists(classOf[Chain], fwFwdChainId).await() shouldBe false

        // Verify that the router associations are gone
        routerIds.foreach { rId =>
            val fwdJumpRuleId = fwdChainFwJumpRuleId(UUIDUtil.toProto(rId))
            storage.exists(classOf[Rule], fwdJumpRuleId).await() shouldBe false
        }
    }

    private def validateFirewall(fwId: UUID, ruleIds: List[UUID] = List(),
                                 adminStateUp: Boolean = true,
                                 addRtrIds: List[UUID] = List(),
                                 delRtrIds: List[UUID] = List()): Unit = {
        val protoFwId = UUIDUtil.toProto(fwId)
        val fwFwdChainId = fwdChainId(protoFwId)

        val fwdChain = storage.get(classOf[Chain], fwFwdChainId).await()

        // Verify that the chain names are correct
        fwdChain.getName shouldBe fwdChainName(protoFwId)

        val rules = storage.getAll(classOf[Rule], ruleIds).await()
        validateForwardRuleOrder(fwdChain, rules, adminStateUp)

        validateRouterAssociations(fwId, addRtrIds, delRtrIds)
    }

    private def validateForwardRuleOrder(chain: Chain, rules: Seq[Rule],
                                         adminStateUp: Boolean = true) : Unit = {
        // Verify that forward chain is correct.
        val expectedNum = if (adminStateUp) rules.length + 2 else
            rules.length + 3

        chain.getRuleIdsCount shouldBe expectedNum

        var index = 0
        var r: Rule = null
        if (!adminStateUp) {
            r = storage.get(classOf[Rule], chain.getRuleIds(index)).await()
            r.getAction shouldBe Action.DROP
            index += 1
        }

        r = storage.get(classOf[Rule], chain.getRuleIds(index)).await()
        r.getCondition.getMatchReturnFlow shouldBe true
        index += 1

        val inChainRuleIds = chain.getRuleIdsList.asScala.slice(
            index, index + rules.length)
        inChainRuleIds should contain.theSameElementsInOrderAs(
            rules.map(_.getId))
        index += rules.length

        r = storage.get(classOf[Rule], chain.getRuleIds(index)).await()
        r.getAction shouldBe Action.DROP
    }

    private def validateRouterJumpRule(chainId: Commons.UUID,
                                       jumpRuleId: Commons.UUID,
                                       jumpChainId: Commons.UUID): Unit = {
        val chain = storage.get(classOf[Chain], chainId).await()
        chain.getRuleIdsCount shouldBe > (0)

        chain.getRuleIds(0) shouldBe jumpRuleId

        val fwJumpRule = storage.get(classOf[Rule], jumpRuleId).await()
        fwJumpRule.getAction shouldBe Action.JUMP
        fwJumpRule.getJumpRuleData.getJumpChainId shouldBe jumpChainId
    }

    private def validateNoRouterJumpRule(chainId: Commons.UUID,
                                         jumpRuleId: Commons.UUID): Unit = {
        val chain = storage.get(classOf[Chain], chainId).await()
        chain.getRuleIdsList should not contain jumpRuleId
    }

    private def validateRouterAssociations(fwId: UUID,
                                           addIds: List[UUID],
                                           delIds: List[UUID]): Unit = {
        val fwIdProto = UUIDUtil.toProto(fwId)

        val addRouters = storage.getAll(classOf[Router], addIds).await()
        addRouters.foreach { r =>
            val fwdRtrChainId = fwdChainId(r.getId)

            // Check that the filter chains are set
            r.hasInboundFilterId shouldBe true
            r.hasOutboundFilterId shouldBe true

            // Check that the chains are jump rules to the firewall chains
            validateRouterJumpRule(fwdRtrChainId, fwdChainFwJumpRuleId(r.getId),
                                   fwdChainId(fwIdProto))
        }

        val delRouters = storage.getAll(classOf[Router], delIds).await()
        delRouters.foreach { r =>
            // Verify that the jump rules are gone
            validateNoRouterJumpRule(fwdChainId(r.getId),
                                     fwdChainFwJumpRuleId(r.getId))
        }
    }
}
