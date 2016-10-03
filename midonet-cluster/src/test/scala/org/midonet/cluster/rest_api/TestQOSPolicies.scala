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

    private def createPolicy(name: String, baseURI: URI)
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
                                          baseUri: URI)
    : QOSRuleBWLimit = {
        val bwLimitRule = new QOSRuleBWLimit()
        bwLimitRule.id = UUID.randomUUID()
        bwLimitRule.maxKbps = maxKbps
        bwLimitRule.maxBurstKbps = maxBurst
        bwLimitRule.setBaseUri(baseUri)
        bwLimitRule
    }

    private def createTopLevelDSCPRule(dscpMark: Integer, baseUri: URI)
    : QOSRuleDSCP = {
        val dscpRule = new QOSRuleDSCP()
        dscpRule.id = UUID.randomUUID()
        dscpRule.dscpMark = dscpMark
        dscpRule.setBaseUri(baseUri)
        dscpRule
    }

    private def checkPolicy(testPolicy: QOSPolicy, controlPolicy: QOSPolicy,
                            qosPolicyUri: URI)
    : Unit = {
        testPolicy.id shouldBe controlPolicy.id
        testPolicy.name shouldBe controlPolicy.name
        testPolicy.description shouldBe controlPolicy.description
        testPolicy.shared shouldBe controlPolicy.shared
        testPolicy.getUri shouldBe qosPolicyUri
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

    private def checkJSONRuleList(ruleList: util.List[QOSRule],
                                  expectedList: util.List[QOSRule])
    : Unit = {
        ruleList.size shouldBe expectedList.size
        for ((testRule, expectedRule) <-
             ruleList.asScala zip expectedList.asScala) {
            testRule.id shouldBe expectedRule.id
            testRule.`type` shouldBe expectedRule.`type`
            testRule.maxKbps shouldBe expectedRule.maxKbps
            testRule.maxBurstKbps shouldBe expectedRule.maxBurstKbps
            testRule.dscpMark shouldBe expectedRule.dscpMark
        }
    }

    private def checkRulesMatchJSONRulesOnPolicy(qosPolicy: QOSPolicy,
                                                 expectedBWList: List[QOSRule],
                                                 expectedDSCPList: List[QOSRule])
    : Unit = {
        val bwRes = jerseyTest.client().resource(qosPolicy.getBwLimitRules)
        val dscpRes = jerseyTest.client().resource(qosPolicy.getDscpRules)

        val bwLimitRules = listAndAssertOk[QOSRuleBWLimit](bwRes.getURI)
        bwLimitRules.size shouldBe expectedBWList.size
        for ((testRule, expectedRule) <- bwLimitRules zip expectedBWList) {
            testRule.id shouldBe expectedRule.id
            testRule.maxKbps shouldBe expectedRule.maxKbps
            testRule.maxBurstKbps shouldBe expectedRule.maxBurstKbps
        }

        val dscpRules = listAndAssertOk[QOSRuleDSCP](dscpRes.getURI)
        dscpRules.size shouldBe expectedDSCPList.size
        for ((testRule, expectedRule) <- dscpRules zip expectedDSCPList) {
            testRule.id shouldBe expectedRule.id
            testRule.dscpMark shouldBe expectedRule.dscpMark
        }
    }

    scenario("Create, Read, Update, Delete QOS Policy") {
        val qosPolicy = createPolicy("test-qos", baseUri)

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
        val qosPolicy = createPolicy("test-qos", baseUri)

        val bwLimitRule = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_BW_LIMIT,
            maxKbps = 100,maxBurst = 1000)

        val dscpRule = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 11)

        qosPolicy.rules.add(bwLimitRule)
        qosPolicy.rules.add(dscpRule)

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)
        val qosPolicyResp = getAndAssertOk[QOSPolicy](qosPolicyUri)

        checkPolicy(qosPolicyResp, qosPolicy, qosPolicyUri)

        checkJSONRuleList(qosPolicyResp.rules, qosPolicy.rules)
        checkRulesMatchJSONRulesOnPolicy(qosPolicyResp,
            List(bwLimitRule),
            List(dscpRule))

        deleteAndAssertGone[QOSPolicy](qosPolicyResp.getUri)
    }

    scenario("Update QOS Policy With New, Changed, and Deleted Rules") {
        val qosPolicy = createPolicy("test-qos", baseUri)

        val bwLimitRule = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_BW_LIMIT,
            maxKbps = 100,maxBurst = 1000)

        val dscpRule = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 11)

        val dscpRule2 = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 22)

        qosPolicy.rules.add(bwLimitRule)
        qosPolicy.rules.add(dscpRule)
        qosPolicy.rules.add(dscpRule2)

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)
        val qosPolicyCreated = getAndAssertOk[QOSPolicy](qosPolicyUri)

        checkJSONRuleList(qosPolicyCreated.rules, qosPolicy.rules)
        checkRulesMatchJSONRulesOnPolicy(qosPolicyCreated,
            List(bwLimitRule),
            List(dscpRule, dscpRule2))

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

        checkJSONRuleList(qosPolicyUpdated.rules, qosPolicy.rules)
        checkRulesMatchJSONRulesOnPolicy(qosPolicyUpdated,
            List(bwLimitRule2),
            List(dscpRule, dscpRule2))

        deleteAndAssertGone[QOSPolicy](qosPolicyUpdated.getUri)
    }

    scenario("Creating BW Limit Rules w/o Policy Should Fail") {
        val bwLimitRule = createTopLevelBwLimitRule(100, 1000, baseUri)

        postAndAssertStatus(bwLimitRule, qosBWLimitRulesResource.getURI,
                            ClientResponse.Status.METHOD_NOT_ALLOWED.getStatusCode)
    }

    scenario("Create, Read, Update, Delete BW Limit Rules") {
        val qosPolicy = createPolicy("test-qos", baseUri)

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val bwLimitRule = createTopLevelBwLimitRule(100, 1000, baseUri)

        val bwRes = jerseyTest.client().resource(qosPolicy.getBwLimitRules)

        val bwLimitRuleUri = postAndAssertOk(bwLimitRule, bwRes.getURI)
        val bwLimitRuleResp = getAndAssertOk[QOSRuleBWLimit](bwLimitRuleUri)
        checkBwLimitRule(bwLimitRuleResp, bwLimitRule)

        val qosPolicyUpdated = getAndAssertOk[QOSPolicy](qosPolicyUri)
        val checkRule = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_BW_LIMIT,
            maxKbps = 100, maxBurst = 1000,
            id = bwLimitRule.id)

        checkRulesMatchJSONRulesOnPolicy(qosPolicyUpdated,
            List(checkRule), List())

        bwLimitRuleResp.maxKbps = 200
        putAndAssertOk(bwLimitRuleResp)
        val bwLimitRuleUpdated = getAndAssertOk[QOSRuleBWLimit](bwLimitRuleUri)
        checkBwLimitRule(bwLimitRuleUpdated, bwLimitRuleResp)

        val qosPolicyUpdated2 = getAndAssertOk[QOSPolicy](qosPolicyUri)
        checkRule.maxKbps = 200

        checkRulesMatchJSONRulesOnPolicy(qosPolicyUpdated2,
            List(checkRule), List())

        deleteAndAssertGone[QOSRuleBWLimit](bwLimitRuleUri)

        val bwLimitRulesEmptyResp =
            listAndAssertOk[QOSRuleBWLimit](bwRes.getURI)
        bwLimitRulesEmptyResp shouldBe empty
    }

    scenario("Creating DSCP Rules w/o Policy Should Fail") {
        val dscpRule = createTopLevelDSCPRule(11, baseUri)

        postAndAssertStatus(dscpRule, qosDSCPRulesResource.getURI,
                            ClientResponse.Status.METHOD_NOT_ALLOWED.getStatusCode)
    }

    scenario("Create, Read, Update, Delete DSCP Rules") {
        val qosPolicy = createPolicy("test-qos", baseUri)
        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val dscpRule = createTopLevelDSCPRule(11, baseUri)

        val dscpRes = jerseyTest.client().resource(qosPolicy.getDscpRules)
        val dscpRuleUri = postAndAssertOk(dscpRule, dscpRes.getURI)
        val dscpRuleResp = getAndAssertOk[QOSRuleDSCP](dscpRuleUri)

        checkDscpRule(dscpRuleResp, dscpRule)

        val qosPolicyUpdated = getAndAssertOk[QOSPolicy](qosPolicyUri)
        val checkRule = createPolicyRule(
            QOSRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 11,
            id = dscpRule.id)

        checkRulesMatchJSONRulesOnPolicy(qosPolicyUpdated,
            List(), List(checkRule))

        dscpRuleResp.dscpMark = 22
        putAndAssertOk(dscpRuleResp)

        val dscpRuleUpdated = getAndAssertOk[QOSRuleDSCP](dscpRuleUri)
        checkDscpRule(dscpRuleUpdated, dscpRuleResp)

        val qosPolicyUpdated2 = getAndAssertOk[QOSPolicy](qosPolicyUri)
        checkRule.dscpMark = 22
        checkRulesMatchJSONRulesOnPolicy(qosPolicyUpdated2,
            List(), List(checkRule))

        deleteAndAssertGone[QOSRuleDSCP](dscpRuleUri)

        val dscpRulesEmptyResp = listAndAssertOk[QOSRuleDSCP](dscpRes.getURI)
        dscpRulesEmptyResp shouldBe empty
    }

    scenario("Test policy <-> rule bindings") {
        val qosPolicy = createPolicy("test-qos", baseUri)
        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val dscpRule = createTopLevelDSCPRule(11, baseUri)
        val dscpRule2 = createTopLevelDSCPRule(22, baseUri)
        val bwLimitRule = createTopLevelBwLimitRule(100, 1000, baseUri)

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
