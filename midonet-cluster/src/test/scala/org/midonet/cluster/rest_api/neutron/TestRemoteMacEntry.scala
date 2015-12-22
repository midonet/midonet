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

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.rest_api.neutron.models.{RemoteMacEntry, Neutron}
import org.midonet.cluster.rest_api.rest_api.FuncJerseyTest
import org.midonet.cluster.services.rest_api.MidonetMediaTypes.{NEUTRON_JSON_V3, NEUTRON_REMOTE_MAC_ENTRY_JSON_V1}
import org.midonet.packets.{IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class TestRemoteMacEntry extends FeatureSpec
                                 with Matchers
                                 with BeforeAndAfter {

    private var remoteMacEntryResource: WebResource = _
    private var jerseyTest: FuncJerseyTest = _

    before {
        jerseyTest = new FuncJerseyTest
        jerseyTest.setUp()
        remoteMacEntryResource =
            jerseyTest.resource().path("/neutron/remote_mac_entries")
    }

    after {
        jerseyTest.tearDown()
    }

    scenario("Neutron has RemoteMacEntries endpoint") {
        val neutron = jerseyTest.resource().path("/neutron")
            .accept(NEUTRON_JSON_V3)
            .get(classOf[Neutron])
        neutron.remoteMacEntries.toString
            .endsWith("/neutron/remote_mac_entries") shouldBe true
        neutron.remoteMacEntryTemplate
            .endsWith("/neutron/remote_mac_entries/{id}") shouldBe true
    }

    scenario("Create, read, delete") {
        val rm =  new RemoteMacEntry()
        rm.id = UUID.randomUUID()
        rm.deviceId = UUID.randomUUID()
        rm.macAddress = MAC.random()
        rm.segmentationId = 100
        rm.tenantId = "tenant"
        rm.vtepAddress = IPv4Addr.fromString("10.0.0.1")

        val response = remoteMacEntryResource
            .`type`(NEUTRON_REMOTE_MAC_ENTRY_JSON_V1)
            .post(classOf[ClientResponse], rm)
        response.getStatus shouldBe Status.CREATED.getStatusCode

        val createdUri = response.getLocation

        val getResp = remoteMacEntryResource.uri(createdUri)
            .accept(NEUTRON_REMOTE_MAC_ENTRY_JSON_V1)
            .get(classOf[RemoteMacEntry])
        getResp shouldBe rm

        val delResp = remoteMacEntryResource.uri(createdUri)
            .delete(classOf[ClientResponse])
        delResp.getStatusInfo.getStatusCode shouldBe Status.NO_CONTENT.getStatusCode
    }
}
