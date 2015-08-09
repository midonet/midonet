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
import org.midonet.cluster.data.neutron.NeutronResourceType.{Firewall => FirewallType}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.Chain
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.concurrent.toFutureOps
import org.scalatest.junit.JUnitRunner
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

@RunWith(classOf[JUnitRunner])
class FirewallTranslatorIT extends C3POMinionTestBase with ChainManager {

    it should "handle firewall CRUD with no rule and no router" in {

        val fwId = UUID.randomUUID()
        var fwjson = firewallJson(fwId)

        insertCreateTask(2, FirewallType, fwjson, fwId)

        eventually(validateFirewall(fwId, List()))
    }


    private def validateFirewall(fwId: UUID, inRuleIds: List[UUID]): Unit = {

        val protoFwId = UUIDUtil.toProto(fwId)
        val fwInChainId = inChainId(protoFwId)
        val fwOutChainId = outChainId(protoFwId)

        val inChain = storage.get(classOf[Chain], fwInChainId).await()
        val outChain = storage.get(classOf[Chain], fwOutChainId).await()

        inChain.getName shouldBe FirewallTranslator.preRouteChainName(
            protoFwId)
        outChain.getName shouldBe FirewallTranslator.postRouteChainName(
            protoFwId)

        inChain.getRuleIdsCount shouldBe inRuleIds.length + 2
        inChain.getRuleIds(0) shouldBe
            FirewallTranslator.inChainFwReturnRule(inChain.getId)
        inChain.getRuleIds(inChain.getRuleIdsCount-1) shouldBe
            FirewallTranslator.inChainFwDropRule(inChain.getId)

        val inChainRuleIds = inChain.getRuleIdsList.asScala.slice(
            1, inRuleIds.length)
        inRuleIds should contain.theSameElementsInOrderAs(inChainRuleIds)

        outChain.getRuleIdsCount shouldBe 1
        outChain.getRuleIdsList.asScala.head shouldBe
            FirewallTranslator.outChainFwForwardRule(outChain.getId)
    }}
