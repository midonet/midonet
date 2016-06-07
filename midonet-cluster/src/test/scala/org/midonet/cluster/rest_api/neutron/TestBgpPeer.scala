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

import java.util
import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.rest_api.neutron.models.{BgpPeer, BgpSpeaker, Router}


@RunWith(classOf[JUnitRunner])
class TestBgpPeer extends NeutronApiTest {

    scenario("Neutron has BgpPeer endpoint") {
        val neutron = getNeutron
        neutron.bgpPeers.toString
            .endsWith("/neutron/bgp_peers") shouldBe true
        neutron.bgpPeerTemplate
            .endsWith("/neutron/bgp_peers/{id}") shouldBe true
    }

    scenario("Create, read, delete") {
        val router = new Router
        router.id = UUID.randomUUID()
        postAndVerifySuccess(router)

        val bgpSpeaker = new BgpSpeaker
        bgpSpeaker.id = UUID.randomUUID()
        bgpSpeaker.logicalRouter = router.id
        bgpSpeaker.delBgpPeerIds = new util.ArrayList[UUID]()

        val bgpPeer = new BgpPeer
        bgpPeer.id = UUID.randomUUID()
        bgpPeer.bgpSpeaker = bgpSpeaker
        bgpPeer.peerIp = "20.0.0.1"
        val bgpPeerUri = postAndVerifySuccess(bgpPeer)

        get[BgpPeer](bgpPeerUri) shouldBe bgpPeer

        deleteAndVerifyNoContent(bgpPeerUri)
    }
}
