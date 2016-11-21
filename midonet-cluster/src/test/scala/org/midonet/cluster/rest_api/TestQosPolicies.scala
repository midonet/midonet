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
import org.midonet.cluster.rest_api.models.QosPolicy.QosRule
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.rest_api.FuncJerseyTest


@RunWith(classOf[JUnitRunner])
class TestQosPolicies extends FeatureSpec
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
    : QosPolicy = {
        val qosPolicy = new QosPolicy
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
                                 id: UUID = UUID.randomUUID())
    : QosRule = {
        val rule = new QosRule
        rule.id = id
        rule.`type` = `type`
        rule.maxKbps = maxKbps
        rule.maxBurstKbps = maxBurst
        rule.dscpMark = dscpMark
        rule
    }

    private def createTopLevelBwLimitRule(maxKbps: Integer, maxBurst: Integer,
                                          id: UUID = UUID.randomUUID())
    : QosRuleBandwidthLimit = {
        val bwLimitRule = new QosRuleBandwidthLimit()
        bwLimitRule.id = id
        bwLimitRule.maxKbps = maxKbps
        bwLimitRule.maxBurstKbps = maxBurst
        bwLimitRule.setBaseUri(baseUri)
        bwLimitRule
    }

    private def createTopLevelDSCPRule(dscpMark: Integer,
                                       id: UUID = UUID.randomUUID())
    : QosRuleDscp = {
        val dscpRule = new QosRuleDscp()
        dscpRule.id = id
        dscpRule.dscpMark = dscpMark
        dscpRule.setBaseUri(baseUri)
        dscpRule
    }

    private def createTopLevelRuleListsFromPolicy(qosPolicy: QosPolicy)
    : (List[QosRuleBandwidthLimit], List[QosRuleDscp]) = {
        val bwLimitRuleList = new util.ArrayList[QosRuleBandwidthLimit]
        val dscpRuleList = new util.ArrayList[QosRuleDscp]
        if (qosPolicy.rules != null) {
            for (rule <- qosPolicy.rules.asScala) {
                rule.`type` match {
                    case QosRule.QOS_RULE_TYPE_BW_LIMIT =>
                        bwLimitRuleList.add(createTopLevelBwLimitRule(
                            rule.maxKbps, rule.maxBurstKbps, rule.id))
                    case QosRule.QOS_RULE_TYPE_DSCP =>
                        dscpRuleList.add(createTopLevelDSCPRule(
                            rule.dscpMark, rule.id))
                }
            }
        }
        (bwLimitRuleList.asScala.toList, dscpRuleList.asScala.toList)
    }

    private def checkDscpRule(testRule: QosRuleDscp, controlRule: QosRuleDscp)
    : Unit = {
        testRule.dscpMark shouldBe controlRule.dscpMark
        testRule.id shouldBe controlRule.id
    }

    private def checkBwLimitRule(testRule: QosRuleBandwidthLimit,
                                 controlRule: QosRuleBandwidthLimit)
    : Unit = {
        testRule.maxKbps shouldBe controlRule.maxKbps
        testRule.maxBurstKbps shouldBe controlRule.maxBurstKbps
        testRule.id shouldBe controlRule.id
    }

    private def checkRulesOnPolicy(qosPolicy: QosPolicy,
                                   expectedBWList: List[QosRuleBandwidthLimit],
                                   expectedDSCPList: List[QosRuleDscp])
    : Unit = {
        val bwRes = jerseyTest.client().resource(qosPolicy.getBwLimitRules)
        val dscpRes = jerseyTest.client().resource(qosPolicy.getDscpRules)

        val bwLimitRules = listAndAssertOk[QosRuleBandwidthLimit](bwRes.getURI).sortBy(_.id)
        val bwLimitRulesExpected = expectedBWList.sortBy(_.id)
        bwLimitRules.size shouldBe expectedBWList.size
        for ((testRule, expectedRule) <- bwLimitRules zip bwLimitRulesExpected) {
            checkBwLimitRule(testRule, expectedRule)
        }

        val dscpRules = listAndAssertOk[QosRuleDscp](dscpRes.getURI).sortBy(_.id)
        val dscpRulesExpected = expectedDSCPList.sortBy(_.id)
        dscpRules.size shouldBe expectedDSCPList.size
        for ((testRule, expectedRule) <- dscpRules zip dscpRulesExpected) {
            checkDscpRule(testRule, expectedRule)
        }
    }

    private def checkPolicy(testPolicy: QosPolicy, controlPolicy: QosPolicy,
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
        val qosPolicyResp = getAndAssertOk[QosPolicy](qosPolicyUri)

        checkPolicy(qosPolicyResp, qosPolicy, qosPolicyUri)

        qosPolicyResp.name = "foobar"
        putAndAssertOk(qosPolicyResp)

        val qosPolicyUpdated = getAndAssertOk[QosPolicy](qosPolicyUri)
        qosPolicyUpdated.name shouldBe qosPolicyResp.name

        deleteAndAssertGone[QosPolicy](qosPolicyResp.getUri)
    }

    scenario("Create QOS Policy With Rules Attached") {
        val qosPolicy = createPolicy("test-qos")
        val bwLimitRule = createPolicyRule(
            QosRule.QOS_RULE_TYPE_BW_LIMIT,
            maxKbps = 100, maxBurst = 1000)

        val dscpRule = createPolicyRule(
            QosRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 11)

        qosPolicy.rules = new util.ArrayList[QosRule]
        qosPolicy.rules.add(bwLimitRule)
        qosPolicy.rules.add(dscpRule)

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val qosPolicyResp = getAndAssertOk[QosPolicy](qosPolicyUri)
        checkPolicy(qosPolicyResp, qosPolicy, qosPolicyUri)

        deleteAndAssertGone[QosPolicy](qosPolicyResp.getUri)
    }

    scenario("Create/Update QOS Policy With Rules on Create (But Not Update)") {
        val qosPolicy = createPolicy("test-qos")
        val bwLimitRule = createPolicyRule(
            QosRule.QOS_RULE_TYPE_BW_LIMIT,
            maxKbps = 100, maxBurst = 1000)

        val dscpRule = createPolicyRule(
            QosRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 11)

        qosPolicy.rules = new util.ArrayList[QosRule]
        qosPolicy.rules.add(bwLimitRule)
        qosPolicy.rules.add(dscpRule)

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val qosPolicyResp = getAndAssertOk[QosPolicy](qosPolicyUri)
        checkPolicy(qosPolicyResp, qosPolicy, qosPolicyUri)

        // Null rules should mean no change to rules
        qosPolicy.rules = null
        qosPolicy.name = "foobar"
        putAndAssertOk(qosPolicy)

        val qosPolicyUpdated = getAndAssertOk[QosPolicy](qosPolicyUri)

        // Re-add just for the sake of the check
        qosPolicy.rules = new util.ArrayList[QosRule]
        qosPolicy.rules.add(bwLimitRule)
        qosPolicy.rules.add(dscpRule)

        checkPolicy(qosPolicyUpdated, qosPolicy, qosPolicyUri)

        deleteAndAssertGone[QosPolicy](qosPolicyResp.getUri)
    }

    scenario("Update QOS Policy With New, Changed, and Deleted Rules") {
        val qosPolicy = createPolicy("test-qos")

        val bwLimitRule = createPolicyRule(
            QosRule.QOS_RULE_TYPE_BW_LIMIT,
            maxKbps = 100, maxBurst = 1000)

        val dscpRule = createPolicyRule(
            QosRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 11)

        val dscpRule2 = createPolicyRule(
            QosRule.QOS_RULE_TYPE_DSCP,
            dscpMark = 22)

        qosPolicy.rules = new util.ArrayList[QosRule]
        qosPolicy.rules.add(bwLimitRule)
        qosPolicy.rules.add(dscpRule)
        qosPolicy.rules.add(dscpRule2)

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)
        val qosPolicyCreated = getAndAssertOk[QosPolicy](qosPolicyUri)

        checkPolicy(qosPolicyCreated, qosPolicy, qosPolicyUri)

        val bwLimitRule2 = createPolicyRule(
            QosRule.QOS_RULE_TYPE_BW_LIMIT,
            maxKbps = 50, maxBurst = 500)

        dscpRule.dscpMark = 33

        qosPolicy.rules.clear()
        qosPolicy.rules.add(dscpRule)
        qosPolicy.rules.add(dscpRule2)
        qosPolicy.rules.add(bwLimitRule2)

        putAndAssertOk(qosPolicy)

        val qosPolicyUpdated = getAndAssertOk[QosPolicy](qosPolicyUri)
        checkPolicy(qosPolicyUpdated, qosPolicy, qosPolicyUri)

        deleteAndAssertGone[QosPolicy](qosPolicyUpdated.getUri)
    }

    scenario("Creating BW Limit Rules w/o Policy Should Fail") {
        val bwLimitRule = createTopLevelBwLimitRule(100, 1000)

        postAndAssertStatus(bwLimitRule, qosBWLimitRulesResource.getURI,
                            ClientResponse.Status.METHOD_NOT_ALLOWED.getStatusCode)
    }

    scenario("Bandwidth rule's burst remains unset when created as such.") {
        val qosPolicy = createPolicy("test-qos")
        postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val bwRes = jerseyTest.client().resource(qosPolicy.getBwLimitRules)
        val bwRule = createTopLevelBwLimitRule(1000, null)
        val bwRuleUri = postAndAssertOk(bwRule, bwRes.getURI)

        val bwRuleResult = getAndAssertOk[QosRuleBandwidthLimit](bwRuleUri)
        bwRuleResult.maxBurstKbps shouldBe null
    }

    scenario("Create, Read, Update, Delete BW Limit Rules") {
        val qosPolicy = createPolicy("test-qos")

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val bwLimitRule = createTopLevelBwLimitRule(100, 1000)

        val bwRes = jerseyTest.client().resource(qosPolicy.getBwLimitRules)

        val bwLimitRuleUri = postAndAssertOk(bwLimitRule, bwRes.getURI)
        val bwLimitRuleResp = getAndAssertOk[QosRuleBandwidthLimit](bwLimitRuleUri)
        checkBwLimitRule(bwLimitRuleResp, bwLimitRule)

        val qosPolicyUpdated = getAndAssertOk[QosPolicy](qosPolicyUri)
        qosPolicy.rules = new util.ArrayList[QosRule]
        qosPolicy.rules.add(
            createPolicyRule(
                QosRule.QOS_RULE_TYPE_BW_LIMIT,
                maxKbps = 100, maxBurst = 1000,
                id = bwLimitRule.id))

        checkPolicy(qosPolicyUpdated, qosPolicy, qosPolicyUri)

        bwLimitRuleResp.maxKbps = 200
        putAndAssertOk(bwLimitRuleResp)
        val bwLimitRuleUpdated = getAndAssertOk[QosRuleBandwidthLimit](bwLimitRuleUri)
        checkBwLimitRule(bwLimitRuleUpdated, bwLimitRuleResp)

        val qosPolicyUpdated2 = getAndAssertOk[QosPolicy](qosPolicyUri)
        qosPolicy.rules.clear()
        qosPolicy.rules.add(
            createPolicyRule(
                QosRule.QOS_RULE_TYPE_BW_LIMIT,
                maxKbps = 200, maxBurst = 1000,
                id = bwLimitRule.id))

        checkPolicy(qosPolicyUpdated2, qosPolicy, qosPolicyUri)

        deleteAndAssertGone[QosRuleBandwidthLimit](bwLimitRuleUri)

        val bwLimitRulesEmptyResp =
            listAndAssertOk[QosRuleBandwidthLimit](bwRes.getURI)
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
        val dscpRuleResp = getAndAssertOk[QosRuleDscp](dscpRuleUri)

        val qosPolicyUpdated = getAndAssertOk[QosPolicy](qosPolicyUri)
        qosPolicy.rules = new util.ArrayList[QosRule]
        qosPolicy.rules.add(
            createPolicyRule(
                QosRule.QOS_RULE_TYPE_DSCP,
                dscpMark = 11,
                id = dscpRule.id))
        checkPolicy(qosPolicyUpdated, qosPolicy, qosPolicyUri)

        dscpRuleResp.dscpMark = 22
        putAndAssertOk(dscpRuleResp)

        val dscpRuleUpdated = getAndAssertOk[QosRuleDscp](dscpRuleUri)
        checkDscpRule(dscpRuleUpdated, dscpRuleResp)

        val qosPolicyUpdated2 = getAndAssertOk[QosPolicy](qosPolicyUri)
        qosPolicy.rules.clear()
        qosPolicy.rules.add(
            createPolicyRule(
                QosRule.QOS_RULE_TYPE_DSCP,
                dscpMark = 22,
                id = dscpRule.id))

        checkPolicy(qosPolicyUpdated2, qosPolicy, qosPolicyUri)

        deleteAndAssertGone[QosRuleDscp](dscpRuleUri)

        val dscpRulesEmptyResp = listAndAssertOk[QosRuleDscp](dscpRes.getURI)
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

        val dscpRulesBothResp = listAndAssertOk[QosRuleDscp](dscpRes.getURI)
        dscpRulesBothResp.size shouldBe 2

        deleteAndAssertGone[QosRuleDscp](dscpRuleUri)

        val dscpRulesJustOneResp = listAndAssertOk[QosRuleDscp](dscpRes.getURI)
        dscpRulesJustOneResp.size shouldBe 1
        dscpRulesJustOneResp.head.id shouldBe dscpRule2.id

        // Deleting policy should cascade deletion to remaining rules.
        deleteAndAssertGone[QosPolicy](qosPolicyUri)
        getAndAssertStatus[QosRuleBandwidthLimit](bwLimitRuleUri,
                                                  Response.Status.NOT_FOUND)
        getAndAssertStatus[QosRuleDscp](dscpRule2Uri, Response.Status.NOT_FOUND)
    }
}
