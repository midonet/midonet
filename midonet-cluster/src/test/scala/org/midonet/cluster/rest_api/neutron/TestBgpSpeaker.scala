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

package org.midonet.cluster.rest_api.neutron

import java.net.URI
import java.util.UUID

import com.sun.jersey.api.client.ClientResponse.Status.METHOD_NOT_ALLOWED

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.rest_api.neutron.models.{BgpSpeaker, Router}


@RunWith(classOf[JUnitRunner])
class TestBgpSpeaker extends NeutronApiTest {

    private def bgpSpeakerUri(id: UUID = UUID.randomUUID()): URI = {
        new URI(getNeutron.bgpSpeakerTemplate.replace("{id}", id.toString))
    }

    scenario("Neutron has BgpSpeaker endpoint") {
        val neutron = getNeutron
        neutron.bgpSpeakers.toString
            .endsWith("/neutron/bgp_speakers") shouldBe true
        neutron.bgpSpeakerTemplate
            .endsWith("/neutron/bgp_speakers/{id}") shouldBe true
    }

    scenario("POST not allowed") {
        val bgpSpeaker = new BgpSpeaker
        bgpSpeaker.id = UUID.randomUUID()
        post(bgpSpeaker).getStatus shouldBe METHOD_NOT_ALLOWED.getStatusCode
    }

    scenario("GET not allowed") {
        methodGetNotAllowed[BgpSpeaker](bgpSpeakerUri())
    }

    scenario("DELETE not allowed") {
        delete(bgpSpeakerUri()).getStatus shouldBe
            METHOD_NOT_ALLOWED.getStatusCode
    }

    scenario("PUT allowed") {
        val router = new Router
        router.id = UUID.randomUUID()
        postAndVerifySuccess(router)

        val bgpSpeaker = new BgpSpeaker
        bgpSpeaker.id = UUID.randomUUID()
        bgpSpeaker.routerId = router.id
        put(bgpSpeaker, bgpSpeaker.id)
    }
}
