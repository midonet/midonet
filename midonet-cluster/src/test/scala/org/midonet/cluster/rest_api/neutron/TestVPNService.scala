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

import org.midonet.cluster.HttpRequestChecks
import org.midonet.cluster.rest_api.neutron.models.{Neutron, VPNService}
import org.midonet.cluster.rest_api.rest_api.{DtoWebResource, FuncTest, Topology}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes.NEUTRON_VPN_SERVICE_JSON_V1

class TestVPNService extends JerseyTest(FuncTest.getBuilder.build())
                             with HttpRequestChecks {

    private var topology: Topology = null
    private var vpnResource: WebResource = null

    @Before
    override def setUp(): Unit = {
        val dtoWebResource = new DtoWebResource(resource())
        topology = new Topology.Builder(dtoWebResource).build()
        vpnResource = resource().path("/neutron/vpnservices")
    }

    @Test
    def testNeutronHasVPN(): Unit = {
        val neutron = resource().path("/neutron").get(classOf[Neutron])
        neutron.vpnServices shouldNot be(null)
        neutron.vpnServicesTemplate shouldNot be(null)
    }

    @Test
    def testCRUD() {
        val dto = new VPNService()
        dto.id = UUID.randomUUID
        dto.name = dto.id + "-name"
        dto.description = dto.id + "-desc"
        dto.setBaseUri(resource.getURI)
        postAndAssertOk(dto, vpnResource.getURI, NEUTRON_VPN_SERVICE_JSON_V1)
        getAndAssertOk[VPNService](dto.getUri,
                                   NEUTRON_VPN_SERVICE_JSON_V1) shouldBe dto

        dto.name = dto.name + "-updated"

        putAndAssertOk(dto, NEUTRON_VPN_SERVICE_JSON_V1)
        deleteAndAssertOk(dto.getUri)
    }

}
