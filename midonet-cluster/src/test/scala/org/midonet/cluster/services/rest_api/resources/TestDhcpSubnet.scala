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
package org.midonet.cluster.services.rest_api.resources

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import com.sun.jersey.api.client.ClientResponse
import com.sun.jersey.api.client.ClientResponse.Status

import org.midonet.client.dto.DtoBridge
import org.midonet.cluster.rest_api.models.{Bridge, DhcpSubnet}
import org.midonet.cluster.rest_api.rest_api.{DtoWebResource, FuncJerseyTest}
import org.midonet.cluster.rest_api.rest_api.Topology
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.packets.IPv4Subnet

@RunWith(classOf[JUnitRunner])
class TestDhcpSubnet extends FeatureSpec
        with Matchers
        with BeforeAndAfter {

    var topology: Topology = _

    var jerseyTest: FuncJerseyTest = _

    val Bridge0 = "BRIDGE0"

    before {
        jerseyTest = new FuncJerseyTest
        jerseyTest.setUp()

        val dtoWebResource = new DtoWebResource(jerseyTest.resource())
        val builder = new Topology.Builder(dtoWebResource)

        val bridge = new DtoBridge()
        bridge.setName(Bridge0)
        bridge.setTenantId("dummyTenant")
        builder.create(Bridge0, bridge)

        topology = builder.build()
    }

    after {
        jerseyTest.tearDown()
    }

    def createSubnet(bridgeId: UUID, cidr: IPv4Subnet): DhcpSubnet = {
        val subnet = new DhcpSubnet()
        subnet.subnetPrefix = cidr.getAddress.toString
        subnet.subnetLength = cidr.getPrefixLen
        subnet.create(bridgeId)
        subnet.setBaseUri(jerseyTest.getBaseURI())
        subnet
    }

    feature("dhcp subnet") {
        scenario("two subnets cannot be added with the same CIDR") {
            val bridge = topology.getBridge(Bridge0)

            val subnetResource = jerseyTest.resource().uri(
                bridge.getDhcpSubnets())

            val cidr = IPv4Subnet.fromCidr("192.168.0.0/24")
            var response = subnetResource.`type`(APPLICATION_DHCP_SUBNET_JSON_V2)
                .post(classOf[ClientResponse], createSubnet(bridge.getId, cidr))
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode

            response = subnetResource.`type`(APPLICATION_DHCP_SUBNET_JSON_V2)
                .post(classOf[ClientResponse], createSubnet(bridge.getId, cidr))
            response.getStatusInfo
                .getStatusCode shouldBe Status.CONFLICT.getStatusCode
        }
    }

}
