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

package org.midonet.cluster.rest_api

import java.net.URI
import java.util
import java.util.UUID
import javax.ws.rs.core.Response

import scala.collection.JavaConverters._
import com.sun.jersey.api.client.{ClientResponse, WebResource}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}
import org.midonet.cluster.HttpRequestChecks
import org.midonet.cluster.rest_api.models.QOSPolicy.QOSRule
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.rest_api.FuncJerseyTest


@RunWith(classOf[JUnitRunner])
class TestQOSPolicies extends FeatureSpec
                              with Matchers
                              with BeforeAndAfter
                              with HttpRequestChecks {

    override def resource: WebResource = jerseyTest.resource()

    private var qosPolicyResource: WebResource = _
    private var qosBWLimitRulesResource: WebResource = _
    private var qosDSCPRulesResource: WebResource = _

    var jerseyTest: FuncJerseyTest = _
    var baseUri: URI = _
    before {
        jerseyTest = new FuncJerseyTest
        jerseyTest.setUp()
        baseUri = jerseyTest.resource().getURI

        qosPolicyResource = jerseyTest.resource().path("/qos_policies")
        qosBWLimitRulesResource = jerseyTest.resource().path("/qos_bw_limit_rules")
        qosDSCPRulesResource = jerseyTest.resource().path("/qos_dscp_rules")
    }

    after {
        jerseyTest.tearDown()
    }

    private def createPolicy(name: String)
    : QOSPolicy = {
        val qosPolicy = new QOSPolicy
        qosPolicy.id = UUID.randomUUID
        qosPolicy.name = name
        qosPolicy.description = "test-description"
        qosPolicy.shared = true
        qosPolicy.setBaseUri(baseUri)
        qosPolicy
    }

    private def createPolicyRule(`type`: String,
                                 maxKbps: Integer = null,
                                 maxBurst: Integer = null,
                                 dscpMark: Integer = null,
                                 id: UUID = null)
    : QOSRule = {
        val rule = new QOSRule
        rule.id = if (id == null) UUID.randomUUID else id
        rule.`type` = `type`
        rule.maxKbps = maxKbps
        rule.maxBurstKbps = maxBurst
        rule.dscpMark = dscpMark
        rule
    }

    private def createTopLevelBwLimitRule(maxKbps: Integer, maxBurst: Integer,
                                          id: UUID = null)
    : QOSRuleBWLimit = {
        val bwLimitRule = new QOSRuleBWLimit()
        bwLimitRule.id = if (id == null) UUID.randomUUID() else id
        bwLimitRule.maxKbps = maxKbps
        bwLimitRule.maxBurstKbps = maxBurst
        bwLimitRule.setBaseUri(baseUri)
        bwLimitRule
    }

    private def createTopLevelDSCPRule(dscpMark: Integer,
                                       id: UUID = null)
    : QOSRuleDSCP = {
        val dscpRule = new QOSRuleDSCP()
        dscpRule.id = if (id == null) UUID.randomUUID() else id
        dscpRule.dscpMark = dscpMark
        dscpRule.setBaseUri(baseUri)
        dscpRule
    }

    private def createTopLevelRuleListsFromPolicy(qosPolicy: QOSPolicy)
    : (List[QOSRuleBWLimit], List[QOSRuleDSCP]) = {
        val bwLimitRuleList = new util.ArrayList[QOSRuleBWLimit]
        val dscpRuleList = new util.ArrayList[QOSRuleDSCP]
        if (qosPolicy.rules != null) {
            for (rule <- qosPolicy.rules.asScala) {
                if (rule.`type` == QOSRule.QOS_RULE_TYPE_BW_LIMIT) {
                    bwLimitRuleList.add(createTopLevelBwLimitRule(
                        rule.maxKbps, rule.maxBurstKbps, rule.id))
                } else if (rule.`type` == QOSRule.QOS_RULE_TYPE_DSCP) {
                    dscpRuleList.add(createTopLevelDSCPRule(
                        rule.dscpMark, rule.id))
                }
            }
        }
        (bwLimitRuleList.asScala.toList, dscpRuleList.asScala.toList)
    }

    private def checkDscpRule(testRule: QOSRuleDSCP, controlRule: QOSRuleDSCP)
    : Unit = {
        testRule.dscpMark shouldBe controlRule.dscpMark
        testRule.id shouldBe controlRule.id
    }

    private def checkBwLimitRule(testRule: QOSRuleBWLimit,
                                 controlRule: QOSRuleBWLimit)
    : Unit = {
        testRule.maxKbps shouldBe controlRule.maxKbps
        testRule.maxBurstKbps shouldBe controlRule.maxBurstKbps
        testRule.id shouldBe controlRule.id
    }

    private def checkRulesOnPolicy(qosPolicy: QOSPolicy,
                                   expectedBWList: List[QOSRuleBWLimit],
                                   expectedDSCPList: List[QOSRuleDSCP])
    : Unit = {
        val bwRes = jerseyTest.client().resource(qosPolicy.getBwLimitRules)
        val dscpRes = jerseyTest.client().resource(qosPolicy.getDscpRules)

        val bwLimitRules = listAndAssertOk[QOSRuleBWLimit](bwRes.getURI)
        bwLimitRules.size shouldBe expectedBWList.size
        for ((testRule, expectedRule) <- bwLimitRules zip expectedBWList) {
            checkBwLimitRule(testRule, expectedRule)
        }

        val dscpRules = listAndAssertOk[QOSRuleDSCP](dscpRes.getURI)
        dscpRules.size shouldBe expectedDSCPList.size
        for ((testRule, expectedRule) <- dscpRules zip expectedDSCPList) {
            checkDscpRule(testRule, expectedRule)
        }
    }

    private def checkPolicy(testPolicy: QOSPolicy, controlPolicy: QOSPolicy,
                            qosPolicyUri: URI)
    : Unit = {
        testPolicy.id shouldBe controlPolicy.id
        testPolicy.name shouldBe controlPolicy.name
        testPolicy.description shouldBe controlPolicy.description
        testPolicy.shared shouldBe controlPolicy.shared
        testPolicy.getUri shouldBe qosPolicyUri

        val testRules = createTopLevelRuleListsFromPolicy(controlPolicy)
        checkRulesOnPolicy(testPolicy, testRules._1, testRules._2)
    }

    scenario("Create, Read, Update, Delete QOS Policy") {
        val qosPolicy = createPolicy("test-qos")

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)
        val qosPolicyResp = getAndAssertOk[QOSPolicy](qosPolicyUri)

        checkPolicy(qosPolicyResp, qosPolicy, qosPolicyUri)

        qosPolicyResp.name = "foobar"
        putAndAssertOk(qosPolicyResp)

        val qosPolicyUpdated = getAndAssertOk[QOSPolicy](qosPolicyUri)
        qosPolicyUpdated.name shouldBe qosPolicyResp.name

        deleteAndAssertGone[QOSPolicy](qosPolicyResp.getUri)
    }

    scenario("Create QOS Policy With Rules Attached") {
        val qosPolicy = createPolicy("test-qos")
        val bwLimitRule = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_BW_LIMIT,
            maxKbps = 100, maxBurst = 1000)

        val dscpRule = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 11)

        qosPolicy.rules = new util.ArrayList[QOSRule]
        qosPolicy.rules.add(bwLimitRule)
        qosPolicy.rules.add(dscpRule)

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val qosPolicyResp = getAndAssertOk[QOSPolicy](qosPolicyUri)
        checkPolicy(qosPolicyResp, qosPolicy, qosPolicyUri)

        deleteAndAssertGone[QOSPolicy](qosPolicyResp.getUri)
    }

    scenario("Update QOS Policy With New, Changed, and Deleted Rules") {
        val qosPolicy = createPolicy("test-qos")

        val bwLimitRule = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_BW_LIMIT,
            maxKbps = 100, maxBurst = 1000)

        val dscpRule = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 11)

        val dscpRule2 = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 22)

        qosPolicy.rules = new util.ArrayList[QOSRule]
        qosPolicy.rules.add(bwLimitRule)
        qosPolicy.rules.add(dscpRule)
        qosPolicy.rules.add(dscpRule2)

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)
        val qosPolicyCreated = getAndAssertOk[QOSPolicy](qosPolicyUri)

        checkPolicy(qosPolicyCreated, qosPolicy, qosPolicyUri)

        val bwLimitRule2 = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_BW_LIMIT,
            maxKbps = 50,maxBurst = 500)

        dscpRule.dscpMark = 33

        qosPolicy.rules.clear()
        qosPolicy.rules.add(dscpRule)
        qosPolicy.rules.add(dscpRule2)
        qosPolicy.rules.add(bwLimitRule2)

        putAndAssertOk(qosPolicy)

        val qosPolicyUpdated = getAndAssertOk[QOSPolicy](qosPolicyUri)
        checkPolicy(qosPolicyUpdated, qosPolicy, qosPolicyUri)

        deleteAndAssertGone[QOSPolicy](qosPolicyUpdated.getUri)
    }

    scenario("Creating BW Limit Rules w/o Policy Should Fail") {
        val bwLimitRule = createTopLevelBwLimitRule(100, 1000)

        postAndAssertStatus(bwLimitRule, qosBWLimitRulesResource.getURI,
                            ClientResponse.Status.METHOD_NOT_ALLOWED.getStatusCode)
    }

    scenario("Create, Read, Update, Delete BW Limit Rules") {
        val qosPolicy = createPolicy("test-qos")

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val bwLimitRule = createTopLevelBwLimitRule(100, 1000)

        val bwRes = jerseyTest.client().resource(qosPolicy.getBwLimitRules)

        val bwLimitRuleUri = postAndAssertOk(bwLimitRule, bwRes.getURI)
        val bwLimitRuleResp = getAndAssertOk[QOSRuleBWLimit](bwLimitRuleUri)
        checkBwLimitRule(bwLimitRuleResp, bwLimitRule)

        val qosPolicyUpdated = getAndAssertOk[QOSPolicy](qosPolicyUri)
        qosPolicy.rules = new util.ArrayList[QOSRule]
        qosPolicy.rules.add(
            createPolicyRule(
                QOSRule.QOS_RULE_TYPE_BW_LIMIT,
                maxKbps = 100, maxBurst = 1000,
                id = bwLimitRule.id))

        checkPolicy(qosPolicyUpdated, qosPolicy, qosPolicyUri)

        bwLimitRuleResp.maxKbps = 200
        putAndAssertOk(bwLimitRuleResp)
        val bwLimitRuleUpdated = getAndAssertOk[QOSRuleBWLimit](bwLimitRuleUri)
        checkBwLimitRule(bwLimitRuleUpdated, bwLimitRuleResp)

        val qosPolicyUpdated2 = getAndAssertOk[QOSPolicy](qosPolicyUri)
        qosPolicy.rules.clear()
        qosPolicy.rules.add(
            createPolicyRule(
                QOSRule.QOS_RULE_TYPE_BW_LIMIT,
                maxKbps = 200, maxBurst = 1000,
                id = bwLimitRule.id))

        checkPolicy(qosPolicyUpdated2, qosPolicy, qosPolicyUri)

        deleteAndAssertGone[QOSRuleBWLimit](bwLimitRuleUri)

        val bwLimitRulesEmptyResp =
            listAndAssertOk[QOSRuleBWLimit](bwRes.getURI)
        bwLimitRulesEmptyResp shouldBe empty
    }

    scenario("Creating DSCP Rules w/o Policy Should Fail") {
        val dscpRule = createTopLevelDSCPRule(11)

        postAndAssertStatus(dscpRule, qosDSCPRulesResource.getURI,
                            ClientResponse.Status.METHOD_NOT_ALLOWED.getStatusCode)
    }

    scenario("Create, Read, Update, Delete DSCP Rules") {
        val qosPolicy = createPolicy("test-qos")
        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val dscpRule = createTopLevelDSCPRule(11)

        val dscpRes = jerseyTest.client().resource(qosPolicy.getDscpRules)
        val dscpRuleUri = postAndAssertOk(dscpRule, dscpRes.getURI)
        val dscpRuleResp = getAndAssertOk[QOSRuleDSCP](dscpRuleUri)

        val qosPolicyUpdated = getAndAssertOk[QOSPolicy](qosPolicyUri)
        qosPolicy.rules = new util.ArrayList[QOSRule]
        qosPolicy.rules.add(
            createPolicyRule(
                QOSRule.QOS_RULE_TYPE_DSCP,
                dscpMark = 11,
                id = dscpRule.id))
        checkPolicy(qosPolicyUpdated, qosPolicy, qosPolicyUri)

        dscpRuleResp.dscpMark = 22
        putAndAssertOk(dscpRuleResp)

        val dscpRuleUpdated = getAndAssertOk[QOSRuleDSCP](dscpRuleUri)
        checkDscpRule(dscpRuleUpdated, dscpRuleResp)

        val qosPolicyUpdated2 = getAndAssertOk[QOSPolicy](qosPolicyUri)
        qosPolicy.rules.clear()
        qosPolicy.rules.add(
            createPolicyRule(
                QOSRule.QOS_RULE_TYPE_DSCP,
                dscpMark = 22,
                id = dscpRule.id))

        checkPolicy(qosPolicyUpdated2, qosPolicy, qosPolicyUri)

        deleteAndAssertGone[QOSRuleDSCP](dscpRuleUri)

        val dscpRulesEmptyResp = listAndAssertOk[QOSRuleDSCP](dscpRes.getURI)
        dscpRulesEmptyResp shouldBe empty
    }

    scenario("Test policy <-> rule bindings") {
        val qosPolicy = createPolicy("test-qos")
        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val dscpRule = createTopLevelDSCPRule(11)
        val dscpRule2 = createTopLevelDSCPRule(22)
        val bwLimitRule = createTopLevelBwLimitRule(100, 1000)

        val dscpRes = jerseyTest.client().resource(qosPolicy.getDscpRules)
        val bwRes = jerseyTest.client().resource(qosPolicy.getBwLimitRules)

        val dscpRuleUri = postAndAssertOk(dscpRule, dscpRes.getURI)
        val dscpRule2Uri = postAndAssertOk(dscpRule2, dscpRes.getURI)
        val bwLimitRuleUri = postAndAssertOk(bwLimitRule, bwRes.getURI)

        val dscpRulesBothResp = listAndAssertOk[QOSRuleDSCP](dscpRes.getURI)
        dscpRulesBothResp.size shouldBe 2

        deleteAndAssertGone[QOSRuleDSCP](dscpRuleUri)

        val dscpRulesJustOneResp = listAndAssertOk[QOSRuleDSCP](dscpRes.getURI)
        dscpRulesJustOneResp.size shouldBe 1
        dscpRulesJustOneResp.head.id shouldBe dscpRule2.id

        // Deleting policy should cascade deletion to remaining rules.
        deleteAndAssertGone[QOSPolicy](qosPolicyUri)
        getAndAssertStatus[QOSRuleBWLimit](bwLimitRuleUri,
                                           Response.Status.NOT_FOUND)
        getAndAssertStatus[QOSRuleDSCP](dscpRule2Uri, Response.Status.NOT_FOUND)
    }
}
