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

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, Matchers}

import org.midonet.cluster.rest_api.neutron.models._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class TestL2GatewayConnection extends NeutronApiTest {

    scenario("Neutron has L2 Gateway Connection") {
        val neutron = getNeutron
        neutron.l2GatewayConns shouldNot be(null)
        neutron.l2GatewayConnTemplate shouldNot be(null)
    }

    scenario("Create, Read, Delete") {
        // Dependencies: Router, network, and gateway device.
        val router = new Router
        router.id = UUID.randomUUID()
        postAndVerifySuccess(router)

        val network = new Network
        network.id = UUID.randomUUID()
        postAndVerifySuccess(network)

        val gatewayDev = new GatewayDevice
        gatewayDev.id = UUID.randomUUID()
        gatewayDev.resourceId = router.id
        gatewayDev.tunnelIps = List(IPv4Addr.fromString("30.0.0.1"))
        postAndVerifySuccess(gatewayDev)

        val l2GatewayDev = new L2GatewayDevice
        l2GatewayDev.deviceId = gatewayDev.id
        l2GatewayDev.segmentationId = 100

        val cnxn = new L2GatewayConnection()
        cnxn.id = UUID.randomUUID
        cnxn.networkId = network.id
        cnxn.segmentationId = 100
        cnxn.l2Gateway = new L2Gateway
        cnxn.l2Gateway.devices = List(l2GatewayDev)

        val cnxnUri = postAndVerifySuccess(cnxn)

        get[L2GatewayConnection](cnxnUri) shouldBe cnxn

        deleteAndVerifyNoContent(cnxnUri)
    }
}
