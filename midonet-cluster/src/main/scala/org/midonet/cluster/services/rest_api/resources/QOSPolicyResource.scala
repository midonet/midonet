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

    private def createTopLevelRuleForPolicy(pol: QOSPolicy,
                                            rule: QOSPolicy.QOSRule,
                                            tx: ResourceTransaction): Unit = {
        rule.`type` match {
            case QOSPolicy.QOSRule.QOS_RULE_TYPE_BW_LIMIT =>
                // Create the rule
                val newRule = new QOSRuleBWLimit
                newRule.id = rule.id
                if (rule.maxKbps != null) newRule.maxKbps = rule.maxKbps
                if (rule.maxBurstKbps != null)
                    newRule.maxBurstKbps = rule.maxBurstKbps
                newRule.policyId = pol.id
                tx.create(newRule)
            case QOSPolicy.QOSRule.QOS_RULE_TYPE_DSCP =>
                // Create the rule
                val newRule = new QOSRuleDSCP
                newRule.id = rule.id
                newRule.dscpMark = rule.dscpMark
                newRule.policyId = pol.id
                tx.create(newRule)
        }
    }

    private def checkAndUpdateTopLevelRule(ruleId: UUID,
                                           newRule: QOSPolicy.QOSRule,
                                           tx: ResourceTransaction): Unit = {
        newRule.`type` match {
            case QOSPolicy.QOSRule.QOS_RULE_TYPE_BW_LIMIT =>
                val zoomRule = tx.get(classOf[QOSRuleBWLimit], ruleId)
                if (zoomRule.maxKbps != newRule.maxKbps
                  || zoomRule.maxBurstKbps != newRule.maxBurstKbps) {
                    zoomRule.maxKbps = newRule.maxKbps
                    zoomRule.maxBurstKbps = newRule.maxBurstKbps
                    tx.update(zoomRule)
                }
            case QOSPolicy.QOSRule.QOS_RULE_TYPE_DSCP =>
                val zoomRule = tx.get(classOf[QOSRuleDSCP], ruleId)
                if (zoomRule.dscpMark != newRule.dscpMark) {
                    zoomRule.dscpMark = newRule.dscpMark
                    tx.update(zoomRule)
                }
        }
    }

    private def deleteTopLevelRule(ruleId: UUID,
                                   ruleType: String,
                                   tx: ResourceTransaction): Unit = {
        ruleType match {
            case QOSPolicy.QOSRule.QOS_RULE_TYPE_BW_LIMIT =>
                tx.delete(classOf[QOSRuleBWLimit], ruleId)
            case QOSPolicy.QOSRule.QOS_RULE_TYPE_DSCP =>
                tx.delete(classOf[QOSRuleDSCP], ruleId)
        }
    }

    protected override def createFilter(pol: QOSPolicy,
                                        tx: ResourceTransaction): Unit = {
        tx.create(pol)
        if (pol.rules != null) {
            for (rule <- pol.rules.asScala) {
                createTopLevelRuleForPolicy(pol, rule, tx)
            }
        }
    }

    protected override def updateFilter(to: QOSPolicy,
                                        from: QOSPolicy,
                                        tx: ResourceTransaction): Unit = {
        // Update non-JSON data to new policy object so those will carry
        // through the update.
        to.update(from)
        tx.update(to)

        // Only update the rules lists if the "rules" parameter was present
        // in the updating JSON.  If it was set and empty, it would appear as
        // a valid ArrayList, just with no elements, and in that case, we
        // will want to erase all rules on the top-level policy object.
        if (to.rules == null) {
            return
        }

        // Map each set of rules based on their IDs and then compare.
        // Rules that are on the new and not on the old need to be
        // created.  Rules on the old and not on the new must be
        // deleted.  Rules that are on both should have their
        // parameters checked, and updated if they are changed.
        val oldBWRuleSet = from.bandwidthLimitRuleIds.asScala.toSet
        val oldDSCPRuleSet = from.dscpMarkingRuleIds.asScala.toSet
        val oldRuleSet = oldBWRuleSet ++ oldDSCPRuleSet
        val newRuleMap = to.rules.asScala.map(
            rule => rule.id -> rule).toMap

        val addedRules = newRuleMap.keySet -- oldRuleSet
        val deletedRules = oldRuleSet -- newRuleMap.keySet
        val updatedRules = newRuleMap.keySet intersect oldRuleSet

        for (ruleId <- addedRules) {
            createTopLevelRuleForPolicy(to, newRuleMap(ruleId), tx)
        }
        for (ruleId <- deletedRules) {
            val ruleType =
                if (oldBWRuleSet contains ruleId) QOSPolicy.QOSRule.QOS_RULE_TYPE_BW_LIMIT
                else QOSPolicy.QOSRule.QOS_RULE_TYPE_DSCP
            deleteTopLevelRule(ruleId, ruleType, tx)
        }
        for (ruleId <- updatedRules) {
            checkAndUpdateTopLevelRule(ruleId, newRuleMap(ruleId), tx)
        }
    }
}
