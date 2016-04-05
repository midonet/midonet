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

import java.util.UUID

import org.junit.runner.RunWith
import org.midonet.cluster.rest_api.neutron.models.BgpSpeaker
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class TestBgpSpeaker extends NeutronApiTest {

    scenario("Neutron has BgpSpeaker endpoint") {
        val neutron = getNeutron
        neutron.bgpSpeakers.toString
            .endsWith("/neutron/bgp_speakers") shouldBe true
        neutron.bgpSpeakerTemplate
            .endsWith("/neutron/bgp_speakers/{id}") shouldBe true
    }

    scenario("Create, read, delete") {
        val bgpSpeaker = new BgpSpeaker
        bgpSpeaker.id = UUID.randomUUID()
        val bgpSpeakerUri = postAndVerifySuccess(bgpSpeaker)

        get[BgpSpeaker](bgpSpeakerUri) shouldBe bgpSpeaker

        deleteAndVerifyNoContent(bgpSpeakerUri)
    }
}
