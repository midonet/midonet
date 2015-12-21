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

package org.midonet.cluster.rest_api.neutron

import java.util.UUID

import com.sun.jersey.api.client.{ClientResponse, WebResource}
import com.sun.jersey.api.client.ClientResponse.Status

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.rest_api.neutron.models.L2GatewayConnection
import org.midonet.cluster.rest_api.neutron.models.Neutron
import org.midonet.cluster.rest_api.rest_api.FuncJerseyTest
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RunWith(classOf[JUnitRunner])
class TestL2GatewayConnection extends FeatureSpec
        with Matchers
        with BeforeAndAfter {

    private var l2GatewayConnectionResource: WebResource = _

    var jerseyTest: FuncJerseyTest = _

    before {
        jerseyTest = new FuncJerseyTest
        jerseyTest.setUp()
        l2GatewayConnectionResource = jerseyTest.resource().path("/neutron/l2_gateway_connections")
    }

    after {
        jerseyTest.tearDown()
    }

    scenario("Neutron has L2 Gateway Connection") {

        val neutron = jerseyTest.resource().path("/neutron")
            .accept(NEUTRON_JSON_V3)
            .get(classOf[Neutron])
        neutron.l2GatewayConns shouldNot be(null)
        neutron.l2GatewayConnTemplate shouldNot be(null)
    }

    scenario("Create, Read, Delete") {
        val l2GwConn = new L2GatewayConnection()
        l2GwConn.id = UUID.randomUUID
        l2GwConn.routerId = UUID.randomUUID
        l2GwConn.networkId = UUID.randomUUID
        l2GwConn.segmentationId = 100

        val response = l2GatewayConnectionResource.`type`(NEUTRON_L2_GATEWAY_CONNECTION_JSON_V1)
            .post(classOf[ClientResponse], l2GwConn)
        response.getStatus shouldBe Status.CREATED.getStatusCode

        val createdUri = response.getLocation

        val respDto = l2GatewayConnectionResource.uri(createdUri)
            .accept(NEUTRON_L2_GATEWAY_CONNECTION_JSON_V1)
            .get(classOf[L2GatewayConnection])
        respDto shouldBe l2GwConn

        val response2 = l2GatewayConnectionResource.uri(createdUri)
            .delete(classOf[ClientResponse])
        response2.getStatusInfo
            .getStatusCode shouldBe Status.NO_CONTENT.getStatusCode
    }
}
