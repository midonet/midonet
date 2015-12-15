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

import com.sun.jersey.api.client.WebResource
import com.sun.jersey.test.framework.JerseyTest

import org.junit.{Before, Test}
import org.scalatest.ShouldMatchers

import org.midonet.cluster.rest_api.neutron.models.Neutron
import org.midonet.cluster.rest_api.rest_api.{DtoWebResource, FuncTest, Topology}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes.NEUTRON_JSON_V3

class TestVpnService extends JerseyTest(FuncTest.getBuilder.build()) with
                             ShouldMatchers {

    private var topology: Topology = null
    private var webResource: WebResource = null

    @Before
    override def setUp(): Unit = {
        val dtoWebResource = new DtoWebResource(resource())
        topology = new Topology.Builder(dtoWebResource).build()
        webResource = resource().path("/neutron/vpnservices")
    }

    @Test
    def testNeutronHasVPN(): Unit = {
        val neutron = resource().path("/neutron")
            .accept(NEUTRON_JSON_V3)
            .get(classOf[Neutron])
        neutron.vpnServices shouldNot be(null)
        neutron.vpnServiceTemplate shouldNot be(null)
    }

    // TODO: This won't work without setting up the dependencies (router, maybe
    // more).
//    @Test
//    def testCRUD() {
//        val dto = new VpnService()
//        dto.id = UUID.randomUUID
//        dto.name = dto.id + "-name"
//        dto.description = dto.id + "-desc"
//
//        val response = webResource.`type`(NEUTRON_VPN_SERVICE_JSON_V1)
//            .post(classOf[ClientResponse], dto)
//
//        response.getStatus shouldBe Status.CREATED.getStatusCode
//
//        LoggerFactory.getLogger("hello").info(s"$response")
//
//        val createdUri = response.getLocation
//
//        val respDto = client().resource(createdUri)
//            .accept(NEUTRON_VPN_SERVICE_JSON_V1)
//            .get(classOf[VpnService])
//
//        LoggerFactory.getLogger("hello").info(s"${dto.id}")
//        LoggerFactory.getLogger("hello").info(s"${respDto.id}")
//
//        respDto.id shouldBe dto.id
//        respDto.name shouldBe dto.name
//        respDto.description shouldBe dto.description
//
//        response.getLocation shouldBe createdUri
//    }
}
