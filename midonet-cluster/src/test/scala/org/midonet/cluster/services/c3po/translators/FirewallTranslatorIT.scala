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
import org.midonet.cluster.data.neutron.NeutronResourceType.{Firewall => FirewallType, Router => RouterType}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.{NeutronFirewall, NeutronRouter}
import org.midonet.cluster.models.Topology.{Port, Chain, Router, Rule}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class FirewallTranslatorIT extends C3POMinionTestBase with ChainManager {
    import FirewallTranslator._

    private val fwId = UUID.randomUUID()
    private val rtrId = UUID.randomUUID()
    private val rule1Id = UUID.randomUUID()
    private val rule2Id = UUID.randomUUID()
    private val netId = UUID.randomUUID()
    private val subId = UUID.randomUUID()
    private val gwPortId = UUID.randomUUID()
    private val gwIp = "10.0.0.1"
    private val subCidr = "10.0.0.0/24"
    private val gwMac = "ab:cd:ef:01:02:03"

    private def setupGw(noPort: Boolean = false) = {
        createTenantNetwork(2, netId, external = true)
        createSubnet(3, subId, netId, subCidr, gwIp)
        if (noPort) {
            createRouter(4, rtrId)
        } else {
            createRouterGatewayPort(4, gwPortId, netId, rtrId, gwIp, gwMac,
                                    subId)
            createRouter(5, rtrId, gwPortId = gwPortId)
        }
    }

    it should "handle firewall create with admin state down" in {
        val fwjson = firewallJson(fwId, adminStateUp = false)
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = false))
    }

    it should "handle firewall create with admin state up" in {
        val fwjson = firewallJson(fwId, adminStateUp = true)
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = true))
    }

    it should "handle firewall create with rules" in {
        val rule1json = firewallRuleJson(rule1Id, enabled = true,
                                         position = 1)
        val rule2json = firewallRuleJson(rule2Id, enabled = false,
                                         position = 2)
        val fwjson = firewallJson(fwId,
                                  firewallRuleList=List(rule1json, rule2json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))
    }

    it should "handle firewall create with router assoc/disassoc" in {
        setupGw()

        var fwjson = firewallJson(fwId, addRouterIds = List(rtrId))
        insertCreateTask(6, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(rtrId),
                                    delRtrIds = List()))

        fwjson = firewallJson(fwId, delRouterIds = List(rtrId))
        insertUpdateTask(7, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(),
                                    delRtrIds = List(rtrId)))
    }

    it should "handle firewall router assoc that's already associated" in {
        setupGw()

        var fwjson = firewallJson(fwId, addRouterIds = List(rtrId))
        insertCreateTask(6, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(rtrId),
                                    delRtrIds = List()))

        fwjson = firewallJson(fwId, addRouterIds = List(rtrId))
        insertUpdateTask(7, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(rtrId),
                                    delRtrIds = List()))
    }

    it should "handle firewall router disassoc that's not associated" in {
        setupGw()

        var fwjson = firewallJson(fwId)
        insertCreateTask(6, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(),
                                    delRtrIds = List()))

        fwjson = firewallJson(fwId, delRouterIds = List(rtrId))
        insertUpdateTask(7, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, addRtrIds = List(),
                                    delRtrIds = List()))
    }

    it should "handle firewall router assoc before gateway port creation" in {
        setupGw(noPort = true)

        // Create a firewall with router assoc before gateway port is created
        val fwjson = firewallJson(fwId, addRouterIds = List(rtrId))
        insertCreateTask(5, FirewallType, fwjson, fwId)

        // Now create the gateway port
        createRouterGatewayPort(6, gwPortId, netId, rtrId, gwIp, gwMac, subId)
        val rtrJson = routerJson(rtrId, name = "add-gw", gwPortId = gwPortId)
        insertUpdateTask(7, RouterType, rtrJson, rtrId)

        // Validate that the router association took place
        eventually(validateFirewall(fwId, addRtrIds = List(rtrId),
                                    delRtrIds = List()))
    }

    it should "handle firewall update from admin state down to up" in {
        var fwjson = firewallJson(fwId, adminStateUp = false)
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = false))

        fwjson = firewallJson(fwId, adminStateUp = true)
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = true))
    }

    it should "handle firewall update from admin state up to down" in {
        var fwjson = firewallJson(fwId, adminStateUp = true)
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = true))

        fwjson = firewallJson(fwId, adminStateUp = false)
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, adminStateUp = false))
    }

    it should "handle firewall update with rules added" in {
        var fwjson = firewallJson(fwId, firewallRuleList=List())
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId))

        val rule1json = firewallRuleJson(rule1Id, enabled = true,
                                         position = 1)
        val rule2json = firewallRuleJson(rule2Id, enabled = false,
                                         position = 2)
        fwjson = firewallJson(fwId,
                              firewallRuleList = List(rule1json, rule2json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))
    }

    it should "handle firewall update with rules removed" in {
        val rule1json = firewallRuleJson(rule1Id)
        var fwjson = firewallJson(fwId,
                                  firewallRuleList=List(rule1json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))

        fwjson = firewallJson(fwId, firewallRuleList = List())
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId))
    }

    it should "handle firewall update with rule order changed" in {
        var rule1json = firewallRuleJson(rule1Id, position = 1)
        var rule2json = firewallRuleJson(rule2Id, position = 2)
        var fwjson = firewallJson(fwId,
                                  firewallRuleList=List(rule1json, rule2json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id, rule2Id)))

        // Update the position values and reverse the order
        rule1json = firewallRuleJson(rule1Id, position = 2)
        rule2json = firewallRuleJson(rule2Id, position = 1)
        fwjson = firewallJson(fwId,
                              firewallRuleList=List(rule2json, rule1json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule2Id, rule1Id)))
    }

    it should "handle firewall update with rule update" in {
        // This test does a light verification that an update took place.  It
        // is not meant to check the validity of the updated values.  Tests
        // check for the rule translation exist in a different test class.
        var rule1json = firewallRuleJson(rule1Id, action="deny")
        var fwjson = firewallJson(fwId,
                                  firewallRuleList=List(rule1json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually {
            validateFirewall(fwId, List(rule1Id))
            val r = storage.get(classOf[Rule], rule1Id).await()
            r.getAction shouldBe Action.DROP
        }

        rule1json = firewallRuleJson(rule1Id, action = "allow")
        fwjson = firewallJson(fwId, firewallRuleList = List(rule1json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually {
            validateFirewall(fwId, List(rule1Id))
            val r = storage.get(classOf[Rule], rule1Id).await()
            r.getAction shouldBe Action.RETURN
        }
    }

    it should "handle firewall update with rule replaced" in {
        val rule1json = firewallRuleJson(rule1Id, position = 1)
        var fwjson = firewallJson(fwId, firewallRuleList=List(rule1json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))

        val rule2json = firewallRuleJson(rule2Id, position = 1)
        fwjson = firewallJson(fwId, firewallRuleList=List(rule2json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule2Id)))
    }

    it should "handle firewall update with rule disabled" in {
        var rule1json = firewallRuleJson(rule1Id, enabled = true)
        var fwjson = firewallJson(fwId, firewallRuleList=List(rule1json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))

        rule1json = firewallRuleJson(rule1Id, enabled = false)
        fwjson = firewallJson(fwId, firewallRuleList=List(rule1json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId))
    }

    it should "handle firewall update with rule enabled" in {
        var rule1json = firewallRuleJson(rule1Id, enabled = false)
        var fwjson = firewallJson(fwId, firewallRuleList=List(rule1json))
        insertCreateTask(2, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId))

        rule1json = firewallRuleJson(rule1Id, enabled = true)
        fwjson = firewallJson(fwId, firewallRuleList=List(rule1json))
        insertUpdateTask(3, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id)))
    }

    it should "handle firewall deletion" in {
        setupGw()

        val rule1json = firewallRuleJson(rule1Id)
        val fwjson = firewallJson(fwId, firewallRuleList=List(rule1json),
                                  addRouterIds = List(rtrId))
        insertCreateTask(6, FirewallType, fwjson, fwId)
        eventually(validateFirewall(fwId, List(rule1Id),
                                    addRtrIds = List(rtrId)))

        insertDeleteTask(7, FirewallType, fwId)
        eventually(validateFirewallNotExist(fwId, routerIds = List(rtrId)))
    }

    private def validateFirewallNotExist(fwId: UUID,
                                         routerIds: List[UUID] = List()): Unit = {
        val protoFwId = UUIDUtil.toProto(fwId)
        val fwInChainId = inChainId(protoFwId)
        val fwOutChainId = outChainId(protoFwId)

        // Verify that the chains are gone
        storage.exists(classOf[Chain], fwInChainId).await() shouldBe false
        storage.exists(classOf[Chain], fwOutChainId).await() shouldBe false

        // Verify that the router associations are gone
        routerIds.foreach { rId =>
            val inJumpRuleId = inChainFwJumpRuleId(UUIDUtil.toProto(rId))
            storage.exists(classOf[Rule], inJumpRuleId).await() shouldBe false
            val outJumpRuleId = outChainFwJumpRuleId(UUIDUtil.toProto(rId))
            storage.exists(classOf[Rule], outJumpRuleId).await() shouldBe false
        }
    }

    private def validateFirewall(fwId: UUID, inRuleIds: List[UUID] = List(),
                                 adminStateUp: Boolean = true,
                                 addRtrIds: List[UUID] = List(),
                                 delRtrIds: List[UUID] = List()): Unit = {
        val protoFwId = UUIDUtil.toProto(fwId)
        val fwInChainId = inChainId(protoFwId)
        val fwOutChainId = outChainId(protoFwId)

        val inChain = storage.get(classOf[Chain], fwInChainId).await()
        val outChain = storage.get(classOf[Chain], fwOutChainId).await()

        // Verify that the chain names are correct
        inChain.getName shouldBe ingressChainName(protoFwId)
        outChain.getName shouldBe egressChainName(protoFwId)

        val inRules = storage.getAll(classOf[Rule], inRuleIds).await()
        validateIngressRuleOrder(inChain, inRules, adminStateUp)

        validateEgressRuleOrder(outChain, adminStateUp)

        validateRouterAssociations(fwId, addRtrIds, delRtrIds)
    }

    private def validateIngressRuleOrder(chain: Chain, rules: Seq[Rule],
                                         adminStateUp: Boolean = true) : Unit = {
        // Verify that pre routing chain is correct.
        val expectedNum = if (adminStateUp) rules.length + 2 else
            rules.length + 3

        chain.getRuleIdsCount shouldBe expectedNum

        var index = 0
        var r: Rule = null
        if (!adminStateUp) {
            r = storage.get(classOf[Rule], chain.getRuleIds(index)).await()
            r.getAction shouldBe Action.RETURN
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

    private def validateEgressRuleOrder(chain: Chain,
                                        adminStateUp: Boolean = true): Unit = {
        // Verify that post routing chain is correct
        val expectedNum = if (adminStateUp) 1 else 2
        chain.getRuleIdsCount shouldBe expectedNum

        if (!adminStateUp) {
            val r = storage.get(classOf[Rule], chain.getRuleIds(0)).await()
            r.getAction shouldBe Action.RETURN
        }

        val r = storage.get(classOf[Rule],
                            chain.getRuleIds(expectedNum - 1)).await()
        r.getCondition.getMatchForwardFlow shouldBe true
    }

    private def validateRouterJumpRule(chainId: Commons.UUID,
                                       jumpRuleId: Commons.UUID,
                                       jumpChainId: Commons.UUID,
                                       isIngress: Boolean): Unit = {
        val chain = storage.get(classOf[Chain], chainId).await()
        chain.getRuleIdsCount shouldBe > (0)

        if (isIngress)
            chain.getRuleIds(0) shouldBe jumpRuleId
        else
            chain.getRuleIds(chain.getRuleIdsCount - 1) shouldBe jumpRuleId

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

        val addRouters = storage.getAll(classOf[NeutronRouter], addIds).await()
        addRouters.foreach { r =>
            val inGwChainId = inChainId(r.getGwPortId)
            val outGwChainId = outChainId(r.getGwPortId)

            // Check that the filter chains are set
            val gwPort = storage.get(classOf[Port], r.getGwPortId).await()
            gwPort.hasInboundFilterId shouldBe true
            gwPort.hasOutboundFilterId shouldBe true

            // Check that the chains are jump rules to the firewall chains
            validateRouterJumpRule(inGwChainId, inChainFwJumpRuleId(r.getId),
                                   inChainId(fwIdProto),
                                   isIngress = true)
            validateRouterJumpRule(outGwChainId, outChainFwJumpRuleId(r.getId),
                                   outChainId(fwIdProto),
                                   isIngress = false)
        }

        val delRouters = storage.getAll(classOf[Router], delIds).await()
        delRouters.foreach { r =>
            // Verify that the jump rules are gone
            validateNoRouterJumpRule(inChainId(r.getId),
                                     inChainFwJumpRuleId(r.getId))
            validateNoRouterJumpRule(outChainId(r.getId),
                                     outChainFwJumpRuleId(r.getId))
        }
    }
}
