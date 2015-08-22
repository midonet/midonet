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

package org.midonet.cluster.models

import java.util.UUID

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons.Condition
import org.midonet.cluster.models.L2InsertionTranslation.ensureRedirectChains
import org.midonet.cluster.models.Topology.{Port, Rule, PortVlanBinding}
import org.midonet.cluster.models.Topology.Rule.RedirRuleData
import org.midonet.cluster.util.UUIDUtil._

object PortVlanBindingTranslation {

    private final val Timeout = 5 seconds

    final class FutureOps[T](val future: Future[T]) extends AnyVal {

        def getOrThrow: T = Await.result(future, Timeout)
    }

    implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    def setConditions(ruleBuilder: Rule.Builder,
                      fn: (Condition.Builder) => Unit): Rule.Builder = {
        fn(ruleBuilder.getConditionBuilder)
        ruleBuilder
    }

    def updateBinding(store: Storage, op: PersistenceOp): Boolean = {
        // Get the insertion in question
        var removed: Option[PortVlanBinding] = None
        var added: Option[PortVlanBinding] = None
        var ops = Seq.empty[PersistenceOp]
        val (vlanPortId, trunkPortId) = op match {
            case CreateOp(i) =>
                added = Some(i.asInstanceOf[PortVlanBinding])
                // CreateOp: so the vlan port should not already be bound
                val vpId = added.get.getVlanPortId
                val vp = store.get(classOf[Port], vpId).getOrThrow
                if (vp.hasPortVlanBinding)
                    return false
                // Don't add the create operation yet... it needs references
                // to the corresponding Jump rules for the service port.
                (added.get.getVlanPortId, added.get.getTrunkPortId)
            case UpdateOp(i, _) =>
                added = Some(i.asInstanceOf[PortVlanBinding])
                // Don't add this create operation yet... it needs modification
                removed = Some(store.get(
                    classOf[PortVlanBinding], added.get.getId).getOrThrow)
                // Add the delete operation for this old object. Zoom will do
                // the right thing for the delete/add with identical UUID
                ops = ops :+ DeleteOp(classOf[PortVlanBinding], added.get.getId)
                // Don't allow changing the ports
                if (removed.get.getVlanPortId != added.get.getVlanPortId ||
                    removed.get.getTrunkPortId != added.get.getTrunkPortId) {
                    return false
                }
                (added.get.getVlanPortId, added.get.getTrunkPortId)
            case DeleteOp(_, id, ignoreMissing) =>
                try {
                    removed = Some(
                        store.get(classOf[PortVlanBinding], id).getOrThrow)
                }
                catch {
                    case e: Exception =>
                        ignoreMissing match {
                            case true => return true
                            case false => return false
                        }
                }
                // Add the delete operation for this insertion.
                ops = ops :+ op
                (removed.get.getVlanPortId, removed.get.getTrunkPortId)
            case _ =>
                return false
        }

        var trunkInChain = ensureRedirectChains("InboundTrunk", store, trunkPortId)
        var vlanPortOutChain = ensureRedirectChains("VlanPortOut", store, vlanPortId, false)

        // Get the trunk and vlan port
        var vlanPort: Port = store.get(classOf[Port], vlanPortId).getOrThrow
        var trunkPort: Port = store.get(classOf[Port], trunkPortId).getOrThrow

        // The trunk port cannot be a vlan port
        // The vlan port cannot be a trunk port
        if (trunkPort.hasPortVlanBinding || vlanPort.getTrunkPortBindingsCount > 0) {
            return false
        }

        // If a binding is being created/updated, the vlan must be unused
        if (added.isDefined) {
            trunkPort.getTrunkPortBindingsList.asScala.foreach( x => {
                val bind: PortVlanBinding = store
                    .get(classOf[PortVlanBinding], x).getOrThrow
                if (bind.getVlan == added.get.getVlan)
                    return false
            })
        }

        // Delete the two port rules
        if (removed.isDefined) {
            ops = ops ++ vlanPortOutChain.getRuleIdsList.asScala.map {
                DeleteOp(classOf[Rule], _)
            }
            vlanPortOutChain = vlanPortOutChain.toBuilder.clearRuleIds().build
            ops = ops :+ DeleteOp(classOf[Rule], removed.get.getTrunkRule)
            val index = vlanPortOutChain.getRuleIdsList.indexOf(removed.get.getTrunkRule)
            trunkInChain = trunkInChain.toBuilder.removeRuleIds(index).build
        }
        // Add the new port rules
        if (added.isDefined) {
            // Any packet egressing the vlan port gets the specified vlan tag
            // and is redirected *out* the trunk port
            var vlanPortRule = Rule.newBuilder()
                .setId(UUID.randomUUID.asProto)
                .setChainId(vlanPortOutChain.getId)
                .setType(Rule.Type.REDIRECT_RULE)
                .setAction(Rule.Action.REDIRECT)
                .setPushVlan(added.get.getVlan)
                .setRedirRuleData(
                    RedirRuleData.newBuilder
                        .setTargetPort(trunkPortId)
                        .setIngress(false)
                        .setFailOpen(false) // because the trunk is not a VNF
                        .build)
                .build
            // Any packet with the specified vlan ingressing the trunk port
            // gets the vlan popped and is redirected *in* the vlan port
            var trunkPortRule = setConditions(Rule.newBuilder(),
            { c => c.setVlan(added.get.getVlan)})
                .setId(UUID.randomUUID.asProto)
                .setChainId(trunkInChain.getId)
                .setType(Rule.Type.REDIRECT_RULE)
                .setAction(Rule.Action.REDIRECT)
                .setPopVlan(true)
                .setRedirRuleData(
                    RedirRuleData.newBuilder
                        .setTargetPort(vlanPortId)
                        .setIngress(true)
                        .setFailOpen(false) // doesn't matter for ingress anyway
                        .build)
                .build
            ops = ops :+ CreateOp(vlanPortRule)
            ops = ops :+ CreateOp(trunkPortRule)
            // TODO: do we really need to update the references by hand???
            vlanPortOutChain = vlanPortOutChain.toBuilder.addRuleIds(vlanPortRule.getId).build
            trunkInChain = trunkInChain.toBuilder.addRuleIds(trunkPortRule.getId).build
            // Now that we've set the references, add the ops
            ops = ops :+ CreateOp(added.get)
        }
        ops = ops ++ Seq(UpdateOp(vlanPortOutChain), UpdateOp(trunkInChain))
        store.multi(ops)
        true
    }
}
