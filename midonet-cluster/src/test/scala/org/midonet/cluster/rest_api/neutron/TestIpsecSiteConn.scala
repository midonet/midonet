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
import javax.ws.rs.core.Response.Status

import com.sun.jersey.api.client.{ClientResponse, WebResource}
import com.sun.jersey.test.framework.JerseyTest
import org.junit.{Before, Test}
import org.scalatest.ShouldMatchers
import org.slf4j.LoggerFactory

import org.midonet.cluster.rest_api.neutron.models.{Neutron, IPSecSiteConnection}
import org.midonet.cluster.rest_api.rest_api.{DtoWebResource, FuncTest, Topology}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes.NEUTRON_JSON_V4
import org.midonet.cluster.services.rest_api.MidonetMediaTypes.NEUTRON_IPSEC_SITE_CONN_JSON_V1

class TestIpsecSiteConn extends JerseyTest(FuncTest.getBuilder.build()) with
                                ShouldMatchers {

    private var topology: Topology = null
    private var webResource: WebResource = null

    @Before
    override def setUp(): Unit = {
        val dtoWebResource = new DtoWebResource(resource())
        topology = new Topology.Builder(dtoWebResource).build()
        webResource = resource().path("/neutron/ipsec_site_conns")
    }

    @Test
    def testNeutronHasIpsecSiteConn(): Unit = {
        val neutron = resource().path("/neutron")
            .accept(NEUTRON_JSON_V4)
            .get(classOf[Neutron])
        neutron.ipsecSiteConns shouldNot be(null)
        neutron.ipsecSiteConnsTemplate shouldNot be(null)
    }

    @Test
    def testCRUD() {
        val dto = new IPSecSiteConnection()
        dto.id = UUID.randomUUID
        dto.name = dto.id + "-name"
        dto.setBaseUri(getBaseURI)

        val response = webResource.`type`(NEUTRON_IPSEC_SITE_CONN_JSON_V1)
                                  .post(classOf[ClientResponse], dto)

        response.getStatus shouldBe Status.CREATED.getStatusCode

        LoggerFactory.getLogger("hello").info(s"$response")

        val createdUri = response.getLocation

        val respDto = client().resource(createdUri)
            .accept(NEUTRON_IPSEC_SITE_CONN_JSON_V1)
            .get(classOf[IPSecSiteConnection])

        LoggerFactory.getLogger("hello").info(s"${dto.id}")
        LoggerFactory.getLogger("hello").info(s"${respDto.id}")

        respDto.setBaseUri(resource().getURI)
        respDto.id shouldBe dto.id
        respDto.name shouldBe dto.name
        respDto.getUri shouldBe createdUri

        response.getLocation shouldBe createdUri
    }

}
