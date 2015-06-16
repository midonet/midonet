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

package org.midonet.cluster.services.c3po.translators

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

import com.google.protobuf.Message

import org.midonet.cluster.services.c3po.midonet._
import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.SecurityGroup
import org.midonet.cluster.models.Topology.{Chain, IPAddrGroup, Rule}
import org.midonet.util.concurrent.toFutureOps

class SecurityGroupTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[SecurityGroup] with SecurityGroupManager {

    protected override def translateCreate(sg: SecurityGroup)
    : List[MidoOp[_ <: Message]] = {

        val translatedSg = translate(sg)

        val ops = new ListBuffer[MidoOp[_ <: Message]]

        for (rule <- translatedSg.inboundRules) ops += Create(rule)
        for (rule <- translatedSg.outboundRules) ops += Create(rule)
        ops += Create(translatedSg.inboundChain)
        ops += Create(translatedSg.outboundChain)
        ops += Create(translatedSg.ipAddrGroup)
        ops.toList
    }

    protected override def translateUpdate(newSg: SecurityGroup)
    : List[MidoOp[_ <: Message]] = {
        // There's a one-to-many relationship between Neutron security groups
        // and Midonet rules, so an update to a Neutron security group can
        // correspond to creation, update, and/or deletion of Midonet rules.
        // To determine which rules need to be created, updated, and deleted,
        // we need to look at the old version of the SecurityGroup to determine
        // which rules to create, update, delete, or leave alone.
        val oldSg = storage.get(classOf[SecurityGroup], newSg.getId).await()
        val xltOldSg = translate(oldSg)
        val xltNewSg = translate(newSg)

        val ruleChangeOps = getRuleChangeOps(
            xltOldSg.inboundRules ++ xltOldSg.outboundRules,
            xltNewSg.inboundRules ++ xltNewSg.outboundRules)

        val ops = new ListBuffer[MidoOp[_ <: Message]]
        ops ++= ruleChangeOps
        if (xltNewSg.inboundChain != xltOldSg.inboundChain)
            ops += Update(xltNewSg.inboundChain)
        if (xltNewSg.outboundChain != xltOldSg.outboundChain)
            ops += Update(xltNewSg.outboundChain)
        if (xltNewSg.ipAddrGroup != xltOldSg.ipAddrGroup)
            ops += Update(xltNewSg.ipAddrGroup)
        ops.toList
    }

    protected override def translateDelete(sgId: UUID)
    : List[MidoOp[_ <: Message]] = {
        val sg = try storage.get(classOf[SecurityGroup], sgId).await() catch {
            case ex: NotFoundException =>
                return List() // Okay. Delete is idempotent.
        }

        val sgrs = sg.getSecurityGroupRulesList.asScala

        val ops = new ListBuffer[MidoOp[_ <: Message]]
        ops ++= sgrs.map(sgr => Delete(classOf[Rule], sgr.getId))
        ops += Delete(classOf[Chain], ChainManager.inChainId(sgId))
        ops += Delete(classOf[Chain], ChainManager.outChainId(sgId))
        ops += Delete(classOf[IPAddrGroup], sgId)
        ops.toList
    }

    /**
     * Gets the operations necessary to replace oldRules with newRules in the
     * topology store. Will generate operations to:
     *
     * 1. Delete rules which are in oldRules but not in newRules.
     * 2. Update rules which are in both lists but have changed in some way.
     * 3. Create rules which are in newRules but not in oldRules.
     *
     * A rule in one list is considered also to be in the other list if the
     * other list contains a rule with the same ID.
     *
     * No operations are generated for rules which are identical in both lists.
     */
    private def getRuleChangeOps(oldRules: List[Rule], newRules: List[Rule])
    : List[MidoOp[Rule]] = {
        val oldRuleIds = oldRules.map(_.getId)
        val newRuleIds = newRules.map(_.getId)

        val removedIds = oldRuleIds.diff(newRuleIds)
        val addedIds = newRuleIds.diff(oldRuleIds)
        val (addedRules, keptRules) =
            newRules.partition(rule => addedIds.contains(rule.getId))

        // No need to update rules that aren't changing.
        val updatedRules = keptRules.diff(oldRules)

        val ops = new ListBuffer[MidoOp[Rule]]()
        ops.appendAll(removedIds.map(Delete(classOf[Rule], _)))
        ops.appendAll(updatedRules.map(Update(_)))
        ops.appendAll(addedRules.map(Create(_)))
        ops.toList
    }
}
