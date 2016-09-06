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

import org.junit.runner.RunWith
import com.sun.jersey.api.client.{ClientResponse, WebResource}
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.rest_api.FuncJerseyTest
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}


@RunWith(classOf[JUnitRunner])
class TestQOSPolicies extends FeatureSpec
        with Matchers
        with BeforeAndAfter {

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

        val response = qosPolicyResource.`type`(APPLICATION_QOS_POLICY_JSON)
            .post(classOf[ClientResponse], qosPolicy)
        response.getStatus shouldBe ClientResponse.Status.CREATED.getStatusCode

        val createdUri = response.getLocation
        val respDto = qosPolicyResource.uri(createdUri)
            .accept(APPLICATION_QOS_POLICY_JSON)
            .get(classOf[QOSPolicy])

        respDto.id shouldBe qosPolicy.id
        respDto.name shouldBe qosPolicy.name
        respDto.description shouldBe qosPolicy.description
        respDto.shared shouldBe qosPolicy.shared
        respDto.setBaseUri(baseUri)

        response.getLocation shouldBe createdUri

        respDto.name = "foobar"
        val response2 = qosPolicyResource.uri(createdUri)
            .`type`(APPLICATION_QOS_POLICY_JSON)
            .put(classOf[ClientResponse], respDto)

        response2.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NO_CONTENT.getStatusCode

        val respDto2 = qosPolicyResource.uri(createdUri)
            .accept(APPLICATION_QOS_POLICY_JSON)
            .get(classOf[QOSPolicy])
        respDto2.name shouldBe respDto.name

        val response3 = qosPolicyResource.uri(createdUri)
            .delete(classOf[ClientResponse])
        response3.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NO_CONTENT.getStatusCode

        val respDto3 = qosPolicyResource.uri(createdUri)
            .accept(APPLICATION_QOS_POLICY_JSON)
            .get(classOf[ClientResponse])
        respDto3.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NOT_FOUND.getStatusCode
    }

    scenario("Creating BW Limit Rules w/o Policy Should Fail") {
        val bwLimitRule = new QOSRuleBWLimit()
        bwLimitRule.id = UUID.randomUUID()
        bwLimitRule.maxKbps = 100
        bwLimitRule.maxBurstKbps = 1000
        bwLimitRule.setBaseUri(baseUri)

        val response = qosBWLimitRulesResource.`type`(APPLICATION_QOS_RULE_BW_LIMIT_JSON)
            .post(classOf[ClientResponse], bwLimitRule)
        response.getStatus shouldBe ClientResponse.Status.METHOD_NOT_ALLOWED.getStatusCode
    }

    scenario("Create, Read, Update, Delete BW Limit Rules") {
        val qosPolicy = new QOSPolicy()
        qosPolicy.id = UUID.randomUUID
        qosPolicy.name = "test-qos"
        qosPolicy.description = "desc"
        qosPolicy.shared = true
        qosPolicy.setBaseUri(baseUri)

        val response0 = qosPolicyResource.`type`(APPLICATION_QOS_POLICY_JSON)
            .post(classOf[ClientResponse], qosPolicy)
        response0.getStatus shouldBe ClientResponse.Status.CREATED.getStatusCode
        val qosPolicyUri = response0.getLocation

        val bwLimitRule = new QOSRuleBWLimit()
        bwLimitRule.id = UUID.randomUUID()
        bwLimitRule.maxKbps = 100
        bwLimitRule.maxBurstKbps = 1000
        bwLimitRule.setBaseUri(baseUri)

        val bwRes = jerseyTest.client().resource(qosPolicy.getBwLimitRules)
        val response = bwRes.`type`(APPLICATION_QOS_RULE_BW_LIMIT_JSON)
            .post(classOf[ClientResponse], bwLimitRule)

        response.getStatus shouldBe ClientResponse.Status.CREATED.getStatusCode
        val createdUri = response.getLocation
        val respDto = bwRes.uri(createdUri)
            .accept(APPLICATION_QOS_RULE_BW_LIMIT_JSON)
            .get(classOf[QOSRuleBWLimit])

        respDto.id shouldBe bwLimitRule.id
        respDto.maxKbps shouldBe bwLimitRule.maxKbps
        respDto.maxBurstKbps shouldBe bwLimitRule.maxBurstKbps
        respDto.setBaseUri(qosPolicy.getUri)

        val response1b = bwRes
            .accept(APPLICATION_QOS_RULE_BW_LIMIT_COLLECTION_JSON)
            .get(classOf[Array[QOSRuleBWLimit]])
        response1b.length shouldBe 1
        response1b(0).id shouldBe bwLimitRule.id

        response.getLocation shouldBe createdUri

        respDto.maxKbps = 200
        val response2 = bwRes.uri(createdUri)
            .`type`(APPLICATION_QOS_RULE_BW_LIMIT_JSON)
            .put(classOf[ClientResponse], respDto)

        response2.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NO_CONTENT.getStatusCode

        val respDto2 = bwRes.uri(createdUri)
            .accept(APPLICATION_QOS_RULE_BW_LIMIT_JSON)
            .get(classOf[QOSRuleBWLimit])
        respDto2.maxKbps shouldBe respDto.maxKbps

        val response3 = bwRes.uri(createdUri)
            .delete(classOf[ClientResponse])
        response3.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NO_CONTENT.getStatusCode

        val respDto3 = bwRes.uri(createdUri)
            .accept(APPLICATION_QOS_RULE_BW_LIMIT_JSON)
            .get(classOf[ClientResponse])

        respDto3.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NOT_FOUND.getStatusCode

        val response3b = bwRes
            .accept(APPLICATION_QOS_RULE_BW_LIMIT_COLLECTION_JSON)
            .get(classOf[Array[QOSRuleBWLimit]])
        response3b shouldBe empty
    }

    scenario("Creating DSCP Rules w/o Policy Should Fail") {
        val dscpRule = new QOSRuleDSCP()
        dscpRule.id = UUID.randomUUID()
        dscpRule.dscpMark = 11
        dscpRule.setBaseUri(baseUri)

        val response = qosDSCPRulesResource.`type`(APPLICATION_QOS_RULE_DSCP_JSON)
            .post(classOf[ClientResponse], dscpRule)
        response.getStatus shouldBe ClientResponse.Status.METHOD_NOT_ALLOWED.getStatusCode
    }

    scenario("Create, Read, Update, Delete DSCP Rules") {
        val qosPolicy = new QOSPolicy()
        qosPolicy.id = UUID.randomUUID
        qosPolicy.name = "test-qos"
        qosPolicy.description = "desc"
        qosPolicy.shared = true
        qosPolicy.setBaseUri(baseUri)

        val response0 = qosPolicyResource.`type`(APPLICATION_QOS_POLICY_JSON)
            .post(classOf[ClientResponse], qosPolicy)
        response0.getStatus shouldBe ClientResponse.Status.CREATED.getStatusCode
        val qosPolicyUri = response0.getLocation

        val dscpRule = new QOSRuleDSCP()
        dscpRule.id = UUID.randomUUID()
        dscpRule.dscpMark = 11
        dscpRule.setBaseUri(baseUri)

        val absoluteUri = qosPolicy.getDscpRules.toString
        val bwRes = jerseyTest.client().resource(absoluteUri)

        val response = bwRes.`type`(APPLICATION_QOS_RULE_DSCP_JSON)
            .post(classOf[ClientResponse], dscpRule)
        response.getStatus shouldBe ClientResponse.Status.CREATED.getStatusCode
        val createdUri = response.getLocation
        val respDto = bwRes.uri(createdUri)
            .accept(APPLICATION_QOS_RULE_DSCP_JSON)
            .get(classOf[QOSRuleDSCP])

        respDto.id shouldBe dscpRule.id
        respDto.dscpMark shouldBe dscpRule.dscpMark
        respDto.setBaseUri(qosPolicy.getUri)

        val response1b = bwRes
            .accept(APPLICATION_QOS_RULE_DSCP_COLLECTION_JSON)
            .get(classOf[Array[QOSRuleDSCP]])
        response1b.length shouldBe 1
        response1b(0).id shouldBe dscpRule.id

        respDto.dscpMark = 22
        val response2 = bwRes.uri(createdUri)
            .`type`(APPLICATION_QOS_RULE_DSCP_JSON)
            .put(classOf[ClientResponse], respDto)

        response2.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NO_CONTENT.getStatusCode

        val respDto2 = bwRes.uri(createdUri)
            .accept(APPLICATION_QOS_RULE_DSCP_JSON)
            .get(classOf[QOSRuleDSCP])
        respDto2.dscpMark shouldBe respDto.dscpMark

        val response3 = bwRes.uri(createdUri)
            .delete(classOf[ClientResponse])
        response3.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NO_CONTENT.getStatusCode

        val respDto3 = bwRes.uri(createdUri)
            .accept(APPLICATION_QOS_RULE_DSCP_JSON)
            .get(classOf[ClientResponse])

        respDto3.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NOT_FOUND.getStatusCode

        val response3b = bwRes
            .accept(APPLICATION_QOS_RULE_DSCP_COLLECTION_JSON)
            .get(classOf[Array[QOSRuleDSCP]])
        response3b shouldBe empty
    }

    scenario("Test policy <-> rule bindings") {
        val qosPolicy = new QOSPolicy()
        qosPolicy.id = UUID.randomUUID
        qosPolicy.name = "test-qos"
        qosPolicy.description = "desc"
        qosPolicy.shared = true
        qosPolicy.setBaseUri(baseUri)

        val response0 = qosPolicyResource.`type`(APPLICATION_QOS_POLICY_JSON)
            .post(classOf[ClientResponse], qosPolicy)
        response0.getStatus shouldBe ClientResponse.Status.CREATED.getStatusCode
        val qosPolicyUri = response0.getLocation

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

        val absoluteUri1 = qosPolicy.getDscpRules.toString
        val absoluteUri2 = qosPolicy.getBwLimitRules.toString
        val dscpRes = jerseyTest.client().resource(absoluteUri1)
        val bwRes = jerseyTest.client().resource(absoluteUri2)

        val response = dscpRes.`type`(APPLICATION_QOS_RULE_DSCP_JSON)
            .post(classOf[ClientResponse], dscpRule)
        response.getStatus shouldBe ClientResponse.Status.CREATED.getStatusCode

        val response2 = dscpRes.`type`(APPLICATION_QOS_RULE_DSCP_JSON)
            .post(classOf[ClientResponse], dscpRule2)
        response2.getStatus shouldBe ClientResponse.Status.CREATED.getStatusCode

        val response3 = bwRes.`type`(APPLICATION_QOS_RULE_BW_LIMIT_JSON)
            .post(classOf[ClientResponse], bwLimitRule)
        response3.getStatus shouldBe ClientResponse.Status.CREATED.getStatusCode

        val response4 = dscpRes.uri(response.getLocation)
            .delete(classOf[ClientResponse])
        response4.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NO_CONTENT.getStatusCode

        val response4b = dscpRes
            .accept(APPLICATION_QOS_RULE_DSCP_COLLECTION_JSON)
            .get(classOf[Array[QOSRuleDSCP]])
        response4b shouldNot contain(dscpRule)

        val response4c = qosPolicyResource
            .uri(qosPolicyUri)
            .delete(classOf[ClientResponse])
        response4c.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NO_CONTENT.getStatusCode

        val response4d = qosBWLimitRulesResource
            .path(bwLimitRule.id.toString)
            .accept(APPLICATION_QOS_RULE_BW_LIMIT_JSON)
            .get(classOf[ClientResponse])
        response4d.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NOT_FOUND.getStatusCode

        val response4e = qosDSCPRulesResource
            .path(dscpRule2.id.toString)
            .accept(APPLICATION_QOS_RULE_DSCP_JSON)
            .get(classOf[ClientResponse])
        response4e.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NOT_FOUND.getStatusCode

        val response5 = qosPolicyResource
            .uri(qosPolicyUri)
            .accept(APPLICATION_QOS_POLICY_JSON)
            .get(classOf[ClientResponse])
        response5.getStatusInfo
            .getStatusCode shouldBe ClientResponse.Status.NOT_FOUND.getStatusCode
    }
}


