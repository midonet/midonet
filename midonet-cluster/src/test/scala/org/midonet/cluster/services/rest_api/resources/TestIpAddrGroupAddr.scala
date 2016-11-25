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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import com.sun.jersey.api.client.ClientResponse
import com.sun.jersey.api.client.WebResource
import com.sun.jersey.api.client.ClientResponse.Status

import org.midonet.cluster.rest_api.ResourceUris
import org.midonet.cluster.rest_api.models.IpAddrGroup
import org.midonet.cluster.rest_api.models.{Ipv4AddrGroupAddr, Ipv6AddrGroupAddr}
import org.midonet.cluster.rest_api.rest_api.FuncJerseyTest
import org.midonet.cluster.rest_api.validation.MessageProperty._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RunWith(classOf[JUnitRunner])
class TestIpAddrGroupAddr extends FeatureSpec
        with Matchers
        with BeforeAndAfter {

    var jerseyTest: FuncJerseyTest = _
    var webResource: WebResource = _
    before {
        jerseyTest = new FuncJerseyTest
        jerseyTest.setUp()

        webResource = jerseyTest.resource()
    }

    after {
        jerseyTest.tearDown()
    }

    def createIpAddrGroup(name: String): IpAddrGroup = {
        val ipg = new IpAddrGroup
        ipg.name = name
        ipg.setBaseUri(webResource.getURI)
        ipg.create()
        ipg
    }

    def createIp4AddrGroupAddr(group: IpAddrGroup,
                               addr: String): Ipv4AddrGroupAddr = {
        val ipga = new Ipv4AddrGroupAddr(group.id, addr)
        ipga.setBaseUri(webResource.getURI)
        ipga
    }

    def createIp6AddrGroupAddr(group: IpAddrGroup,
                               addr: String): Ipv6AddrGroupAddr = {
        val ipga = new Ipv6AddrGroupAddr(group.id, addr)
        ipga.setBaseUri(webResource.getURI)
        ipga
    }

    feature("ip address group addresses") {
        scenario("a single address can only be added once (ipv4)") {
            val ipgResource = webResource.path(ResourceUris.IP_ADDR_GROUPS)

            val ipAddrGroup = createIpAddrGroup("foobar")
            var response = ipgResource.`type`(APPLICATION_IP_ADDR_GROUP_JSON)
                .post(classOf[ClientResponse], ipAddrGroup)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode
            val ipgaResource = webResource.uri(ipAddrGroup.getAddrs())
            val ipAddrGroupAddr = createIp4AddrGroupAddr(ipAddrGroup,
                                                         "10.0.0.1")

            response = ipgaResource.`type`(APPLICATION_IP_ADDR_GROUP_ADDR_JSON)
                .post(classOf[ClientResponse], ipAddrGroupAddr)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode

            response = ipgaResource.`type`(APPLICATION_IP_ADDR_GROUP_ADDR_JSON)
                .post(classOf[ClientResponse], ipAddrGroupAddr)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CONFLICT.getStatusCode

            val ipAddrGroupAddr2 = createIp4AddrGroupAddr(ipAddrGroup,
                                                          "10.0.0.2")

            response = ipgaResource.`type`(APPLICATION_IP_ADDR_GROUP_ADDR_JSON)
                .post(classOf[ClientResponse], ipAddrGroupAddr2)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode
        }

        scenario("a single address can only be added once (ipv6)") {
            val ipgResource = webResource.path(ResourceUris.IP_ADDR_GROUPS)

            val ipAddrGroup = createIpAddrGroup("foobar")
            var response = ipgResource.`type`(APPLICATION_IP_ADDR_GROUP_JSON)
                .post(classOf[ClientResponse], ipAddrGroup)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode
            val ipgaResource = webResource.uri(ipAddrGroup.getAddrs())
            val ipAddrGroupAddr = createIp6AddrGroupAddr(ipAddrGroup,
                                                         "10::1")

            response = ipgaResource.`type`(APPLICATION_IP_ADDR_GROUP_ADDR_JSON)
                .post(classOf[ClientResponse], ipAddrGroupAddr)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode

            response = ipgaResource.`type`(APPLICATION_IP_ADDR_GROUP_ADDR_JSON)
                .post(classOf[ClientResponse], ipAddrGroupAddr)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CONFLICT.getStatusCode

            val ipAddrGroupAddr2 = createIp6AddrGroupAddr(ipAddrGroup,
                                                          "10::2")

            response = ipgaResource.`type`(APPLICATION_IP_ADDR_GROUP_ADDR_JSON)
                .post(classOf[ClientResponse], ipAddrGroupAddr2)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode
        }
    }

}
