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

package org.midonet.cluster.rest_api

import java.net.URI
import java.util.UUID

import javax.ws.rs.core.Response

import com.sun.jersey.api.client.{ClientResponse, WebResource}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.HttpRequestChecks
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

    scenario("Create, Read, Update, Delete QOS Policy") {
        val qosPolicy = new QOSPolicy()
        qosPolicy.id = UUID.randomUUID
        qosPolicy.name = "test-qos"
        qosPolicy.description = "desc"
        qosPolicy.shared = true
        qosPolicy.setBaseUri(baseUri)

        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)
        val qosPolicyResp = getAndAssertOk[QOSPolicy](qosPolicyUri) 

        qosPolicyResp.id shouldBe qosPolicy.id
        qosPolicyResp.name shouldBe qosPolicy.name
        qosPolicyResp.description shouldBe qosPolicy.description
        qosPolicyResp.shared shouldBe qosPolicy.shared
        qosPolicyResp.getUri shouldBe qosPolicyUri

        qosPolicyResp.name = "foobar"
        putAndAssertOk(qosPolicyResp)

        val qosPolicyUpdated = getAndAssertOk[QOSPolicy](qosPolicyUri)
        qosPolicyUpdated.name shouldBe qosPolicyResp.name

        deleteAndAssertGone[QOSPolicy](qosPolicyResp.getUri)
    }

    scenario("Creating BW Limit Rules w/o Policy Should Fail") {
        val bwLimitRule = new QOSRuleBWLimit()
        bwLimitRule.id = UUID.randomUUID()
        bwLimitRule.maxKbps = 100
        bwLimitRule.maxBurstKbps = 1000
        bwLimitRule.setBaseUri(baseUri)

        postAndAssertStatus(bwLimitRule, qosBWLimitRulesResource.getURI,
                            ClientResponse.Status.METHOD_NOT_ALLOWED.getStatusCode)
    }

    scenario("Create, Read, Update, Delete BW Limit Rules") {
        val qosPolicy = new QOSPolicy()
        qosPolicy.id = UUID.randomUUID
        qosPolicy.name = "test-qos"
        qosPolicy.description = "desc"
        qosPolicy.shared = true
        qosPolicy.setBaseUri(baseUri)

        postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val bwLimitRule = new QOSRuleBWLimit()
        bwLimitRule.id = UUID.randomUUID()
        bwLimitRule.maxKbps = 100
        bwLimitRule.maxBurstKbps = 1000
        bwLimitRule.setBaseUri(baseUri)

        val bwRes = jerseyTest.client().resource(qosPolicy.getBwLimitRules)

        val bwLimitRuleUri = postAndAssertOk(bwLimitRule, bwRes.getURI)
        val bwLimitRuleResp = getAndAssertOk[QOSRuleBWLimit](bwLimitRuleUri)

        bwLimitRuleResp.id shouldBe bwLimitRule.id
        bwLimitRuleResp.maxKbps shouldBe bwLimitRule.maxKbps
        bwLimitRuleResp.maxBurstKbps shouldBe bwLimitRule.maxBurstKbps

        val bwLimitRulesResp = listAndAssertOk[QOSRuleBWLimit](bwRes.getURI)
        bwLimitRulesResp.length shouldBe 1
        bwLimitRulesResp.head.id shouldBe bwLimitRule.id

        bwLimitRuleResp.maxKbps = 200
        putAndAssertOk(bwLimitRuleResp)
        val bwLimitRuleUpdated = getAndAssertOk[QOSRuleBWLimit](bwLimitRuleUri)
        bwLimitRuleUpdated.maxKbps shouldBe bwLimitRuleResp.maxKbps

        deleteAndAssertGone[QOSRuleBWLimit](bwLimitRuleUri)

        val bwLimitRulesEmptyResp =
            listAndAssertOk[QOSRuleBWLimit](bwRes.getURI)
        bwLimitRulesEmptyResp shouldBe empty
    }

    scenario("Creating DSCP Rules w/o Policy Should Fail") {
        val dscpRule = new QOSRuleDSCP()
        dscpRule.id = UUID.randomUUID()
        dscpRule.dscpMark = 11
        dscpRule.setBaseUri(baseUri)

        postAndAssertStatus(dscpRule, qosDSCPRulesResource.getURI,
                            ClientResponse.Status.METHOD_NOT_ALLOWED.getStatusCode)
    }

    scenario("Create, Read, Update, Delete DSCP Rules") {
        val qosPolicy = new QOSPolicy()
        qosPolicy.id = UUID.randomUUID
        qosPolicy.name = "test-qos"
        qosPolicy.description = "desc"
        qosPolicy.shared = true
        qosPolicy.setBaseUri(baseUri)
        postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val dscpRule = new QOSRuleDSCP()
        dscpRule.id = UUID.randomUUID()
        dscpRule.dscpMark = 11
        dscpRule.setBaseUri(baseUri)

        val dscpRes = jerseyTest.client().resource(qosPolicy.getDscpRules)
        val dscpRuleUri = postAndAssertOk(dscpRule, dscpRes.getURI)
        val dscpRuleResp = getAndAssertOk[QOSRuleDSCP](dscpRuleUri)

        dscpRuleResp.id shouldBe dscpRule.id
        dscpRuleResp.dscpMark shouldBe dscpRule.dscpMark

        val dscpRulesResp = listAndAssertOk[QOSRuleDSCP](dscpRes.getURI)
        dscpRulesResp.length shouldBe 1
        dscpRulesResp.head.id shouldBe dscpRule.id

        dscpRuleResp.dscpMark = 22
        putAndAssertOk(dscpRuleResp)

        val dscpRuleUpdated = getAndAssertOk[QOSRuleDSCP](dscpRuleUri)
        dscpRuleUpdated.dscpMark shouldBe dscpRuleResp.dscpMark

        deleteAndAssertGone[QOSRuleDSCP](dscpRuleUri)

        val dscpRulesEmptyResp = listAndAssertOk[QOSRuleDSCP](dscpRes.getURI)
        dscpRulesEmptyResp shouldBe empty
    }

    scenario("Test policy <-> rule bindings") {
        val qosPolicy = new QOSPolicy()
        qosPolicy.id = UUID.randomUUID
        qosPolicy.name = "test-qos"
        qosPolicy.description = "desc"
        qosPolicy.shared = true
        qosPolicy.setBaseUri(baseUri)
        val qosPolicyUri = postAndAssertOk(qosPolicy, qosPolicyResource.getURI)

        val dscpRule = new QOSRuleDSCP()
        dscpRule.id = UUID.randomUUID()
        dscpRule.dscpMark = 11
        dscpRule.setBaseUri(baseUri)

        val dscpRule2 = new QOSRuleDSCP()
        dscpRule2.id = UUID.randomUUID()
        dscpRule2.dscpMark = 22
        dscpRule2.setBaseUri(baseUri)

        val bwLimitRule = new QOSRuleBWLimit()
        bwLimitRule.id = UUID.randomUUID()
        bwLimitRule.maxKbps = 100
        bwLimitRule.maxBurstKbps = 1000
        bwLimitRule.setBaseUri(baseUri)

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
