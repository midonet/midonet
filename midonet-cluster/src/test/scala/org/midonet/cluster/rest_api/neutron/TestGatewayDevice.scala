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

import java.util
import java.util.UUID

import com.sun.jersey.api.client.{ClientResponse, WebResource}
import com.sun.jersey.api.client.ClientResponse.Status

import org.junit.runner.RunWith
import org.midonet.packets.IPv4Addr
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.rest_api.neutron.models.{GatewayDevice, Neutron}
import org.midonet.cluster.rest_api.rest_api.FuncJerseyTest
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RunWith(classOf[JUnitRunner])
class TestGatewayDevice extends NeutronApiTest {

    scenario("Neutron has L2 Gateway Connection") {
        val neutron = getNeutron
        neutron.l2GatewayConns shouldNot be(null)
        neutron.l2GatewayConnTemplate shouldNot be(null)
    }

    scenario("Create, Read, Delete") {
        val gatewayDevice = new GatewayDevice()
        gatewayDevice.id = UUID.randomUUID
        gatewayDevice.tunnelIps = new util.ArrayList()
        gatewayDevice.managementIp = IPv4Addr.fromString("1.1.1.1")

        val gatewayDeviceUri = postAndVerifySuccess(gatewayDevice)

        get[GatewayDevice](gatewayDeviceUri) shouldBe gatewayDevice

        deleteAndVerifyNoContent(gatewayDeviceUri)
    }
}
