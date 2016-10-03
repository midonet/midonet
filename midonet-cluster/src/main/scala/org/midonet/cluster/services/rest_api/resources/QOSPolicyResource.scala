/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.rest_api.resources

import java.util.UUID
import javax.ws.rs._
import javax.ws.rs.core.MediaType._

import scala.collection.JavaConverters._
import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.QOSPolicy
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

import scala.collection.mutable

@ApiResource(version = 1, name = "qosPolicies",
             template = "qosPolicyTemplate")
@Path("qos_policies")
@RequestScoped
@AllowCreate(Array(APPLICATION_QOS_POLICY_JSON,
                   APPLICATION_JSON))
@AllowGet(Array(APPLICATION_QOS_POLICY_JSON,
                APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_QOS_POLICY_JSON,
                   APPLICATION_JSON))
@AllowList(Array(APPLICATION_QOS_POLICY_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowDelete
class QOSPolicyResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[QOSPolicy](resContext) {

    @Path("{id}/qos_bw_limit_rules")
    def bw_limit_rules(@PathParam("id") id: UUID): QOSPolicyRuleBWLimitResource = {
        new QOSPolicyRuleBWLimitResource(id, resContext)
    }

    @Path("{id}/qos_dscp_rules")
    def dscp_rules(@PathParam("id") id: UUID): QOSPolicyRuleDSCPResource = {
        new QOSPolicyRuleDSCPResource(id, resContext)
    }

    private def convertRuleToTopLevelObj(t: QOSPolicy,
                                         rule: QOSPolicy.QOSRule,
                                         tx: ResourceTransaction): Unit = {
        if (rule.maxKbps != null || rule.maxBurstKbps != null) {
            // Create the rule
            val newRule = new QOSRuleBWLimit
            newRule.id = rule.id
            if(rule.maxKbps != null) newRule.maxKbps = rule.maxKbps
            if(rule.maxBurstKbps != null)
                newRule.maxBurstKbps = rule.maxBurstKbps
            newRule.policyId = t.id
            tx.create(newRule)
        }
        if (rule.dscpMark != null) {
            // Create the rule
            val newRule = new QOSRuleDSCP
            newRule.id = rule.id
            newRule.dscpMark = rule.dscpMark
            newRule.policyId = t.id
            tx.create(newRule)
        }
    }

    private def convertAllRulesOnPolicy(t: QOSPolicy,
                                        tx: ResourceTransaction): Unit = {
        for (rule <- t.rules.asScala) {
            convertRuleToTopLevelObj(t, rule, tx)
        }
    }

    private def checkAndUpdateTopLevelRule(oldRule: QOSPolicy.QOSRule,
                                           newRule: QOSPolicy.QOSRule,
                                           tx: ResourceTransaction): Unit = {
        if (newRule.maxKbps != oldRule.maxKbps ||
            newRule.maxBurstKbps != oldRule.maxBurstKbps ||
            newRule.dscpMark != oldRule.dscpMark) {
            if (newRule.maxKbps != null || newRule.maxBurstKbps != null) {
                val zoomRule = tx.get(classOf[QOSRuleBWLimit], oldRule.id)
                if(newRule.maxKbps != null) zoomRule.maxKbps = newRule.maxKbps
                if(newRule.maxBurstKbps != null)
                    zoomRule.maxBurstKbps = newRule.maxBurstKbps
                tx.update(zoomRule)
            }
            if (newRule.dscpMark != null) {
                val zoomRule = tx.get(classOf[QOSRuleDSCP], oldRule.id)
                zoomRule.dscpMark = newRule.dscpMark
                tx.update(zoomRule)
            }
        }
    }

    private def deleteTopLevelRule(rule: QOSPolicy.QOSRule,
                                   tx: ResourceTransaction): Unit = {
        if (rule.maxKbps != null || rule.maxBurstKbps != null) {
            tx.delete(classOf[QOSRuleBWLimit], rule.id)
        }
        if (rule.dscpMark != null) {
            tx.delete(classOf[QOSRuleDSCP], rule.id)
        }

    }

    protected override def createFilter(t: QOSPolicy,
                                        tx: ResourceTransaction): Unit = {
        tx.create(t)
        convertAllRulesOnPolicy(t, tx)
    }

    protected override def updateFilter(to: QOSPolicy,
                                        from: QOSPolicy,
                                        tx: ResourceTransaction): Unit = {
        // Update non-JSON data to new policy object so those will carry
        // through the update.
        to.update(from)
        tx.update(to)

        if (from.rules.isEmpty) {
            // New update has rules, old policy has no rules.  This means
            // every rule in the new policy object is new and needs to
            // have its top level object created and linked to the new
            // policy via the various rule ID lists.
            convertAllRulesOnPolicy(to, tx)
        } else {
            // Rules exist on both, but they could be different.  Hash
            // each set of rules based on their IDs and then compare.
            // Rules that are on the new and not on the old need to be
            // created.  Rules on the old and not on the new must be
            // deleted.  Rules that are on both should have their
            // parameters checked, and updated if they are changed.
            var oldRuleHash = new mutable.HashMap[UUID, QOSPolicy.QOSRule]
            var newRuleHash = new mutable.HashMap[UUID, QOSPolicy.QOSRule]

            for (rule <- from.rules.asScala) {
                oldRuleHash += (rule.id -> rule)
            }
            for (rule <- to.rules.asScala) {
                if (!oldRuleHash.contains(rule.id)) {
                    // The new rule set has a rule that doesn't exist in
                    // the old rule list.
                    convertRuleToTopLevelObj(to, rule, tx)
                } else {
                    // The new policy has a rule that existed on the old
                    // policy, but we need to check if the rule's type and
                    // parameters have changed.
                    val oldRule = oldRuleHash(rule.id)
                    checkAndUpdateTopLevelRule(oldRule, rule, tx)
                }

                newRuleHash += (rule.id -> rule)
            }
            for (rule <- from.rules.asScala
                 if !newRuleHash.contains(rule.id)) {
                // Run a final check to look for any rules on the old
                // policy that no longer exist on the new policy.  These
                // rules will have to be deleted.
                deleteTopLevelRule(rule, tx)
            }
        }
    }
}
