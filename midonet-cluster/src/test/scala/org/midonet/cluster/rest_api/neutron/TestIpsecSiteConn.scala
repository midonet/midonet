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

import com.sun.jersey.api.client.WebResource
import com.sun.jersey.test.framework.JerseyTest
import org.junit.{Before, Test}
import org.scalatest.ShouldMatchers

import org.midonet.cluster.HttpRequestChecks
import org.midonet.cluster.rest_api.neutron.models.{IPSecSiteConnection, Neutron}
import org.midonet.cluster.rest_api.rest_api.{DtoWebResource, FuncTest, Topology}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

class TestIpsecSiteConn extends JerseyTest(FuncTest.getBuilder.build()) with
                                ShouldMatchers with HttpRequestChecks {

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
            .accept(NEUTRON_JSON_V3)
            .get(classOf[Neutron])
        neutron.ipsecSiteConns shouldNot be(null)
        neutron.ipsecSiteConnsTemplate shouldNot be(null)
    }

    @Test
    def testCRUD() {
        val dto = new IPSecSiteConnection()
        dto.id = UUID.randomUUID
        dto.name = dto.id + "-name"
        dto.setBaseUri(resource.getURI)

        val createdUri = postAndAssertOk(dto, webResource.getURI,
                                         NEUTRON_IPSEC_SITE_CONN_JSON_V1)

        val respDto = getAndAssertOk[IPSecSiteConnection](createdUri,
                                              NEUTRON_IPSEC_SITE_CONN_JSON_V1)
        respDto.id shouldBe dto.id
        respDto.name shouldBe dto.name

        val l = listAndAssertOk[IPSecSiteConnection](webResource.getURI,
                                             NEUTRON_IPSEC_SITE_CONNS_JSON_V1)
        l should have size 1
        l.head.id shouldBe dto.id

        deleteAndAssertOk(createdUri)

        listAndAssertOk[IPSecSiteConnection](
            webResource.getURI, NEUTRON_IPSEC_SITE_CONNS_JSON_V1) shouldBe empty
    }

}
