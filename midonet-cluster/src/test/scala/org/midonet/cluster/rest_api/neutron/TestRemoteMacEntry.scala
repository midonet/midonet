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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.rest_api.neutron.models.{GatewayDevice, RemoteMacEntry, Router}
import org.midonet.packets.{IPv4Addr, MAC}
import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class TestRemoteMacEntry extends NeutronApiTest {

    scenario("Neutron has RemoteMacEntries endpoint") {
        val neutron = getNeutron
        neutron.remoteMacEntries.toString
            .endsWith("/neutron/remote_mac_entries") shouldBe true
        neutron.remoteMacEntryTemplate
            .endsWith("/neutron/remote_mac_entries/{id}") shouldBe true
    }

    scenario("Create, read, delete") {
        // Dependencies: Router and gateway device
        val router = new Router
        router.id = UUID.randomUUID()
        postAndVerifySuccess(router)

        val gatewayDev = new GatewayDevice
        gatewayDev.id = UUID.randomUUID()
        gatewayDev.resourceId = router.id
        gatewayDev.tunnelIps = List(IPv4Addr.fromString("30.0.0.1"))
        postAndVerifySuccess(gatewayDev)

        val rm =  new RemoteMacEntry()
        rm.id = UUID.randomUUID()
        rm.deviceId = gatewayDev.id
        rm.macAddress = MAC.random()
        rm.segmentationId = 100
        rm.vtepAddress = IPv4Addr.fromString("10.0.0.1")

        val rmUri = postAndVerifySuccess(rm)

        get[RemoteMacEntry](rmUri) shouldBe rm

        deleteAndVerifyNoContent(rmUri)
    }
}
