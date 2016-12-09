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

package org.midonet.midolman.topology.devices

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class DhcpTest extends FlatSpec with Matchers with GivenWhenThen
                       with TopologyBuilder{

    "DHCP" should "convert from Protocol Buffers" in {
        Given("A DHCP message")
        val message = createDhcp(
            dns = List(IPv4Addr.random, IPv4Addr.random),
            opt121routes = List(createOpt121Route(),
                                createOpt121Route()),
            hosts = List(createDhcpHost(options = List(createExtraDhcpOption(),
                                                       createExtraDhcpOption())),
                         createDhcpHost(options = List(createExtraDhcpOption(),
                                                       createExtraDhcpOption()))))

        When("The message converts to a DHCP object")
        val dhcp = ZoomConvert.fromProto(message, classOf[Dhcp])

        Then("The object contains the same fields")
        dhcp.id shouldBe message.getId.asJava
        dhcp.networkId shouldBe message.getNetworkId.asJava
        dhcp.subnetAddress shouldBe message.getSubnetAddress.asJava
        dhcp.serverAddress shouldBe message.getServerAddress.asIPv4Address
        dhcp.dnsServerAddress should contain theSameElementsAs message
            .getDnsServerAddressList.asScala.map(_.asIPv4Address)
        for (index <- 0 until dhcp.opt121Routes.size()) {
            dhcp.opt121Routes.get(index).gateway shouldBe message
                .getOpt121Routes(index).getGateway.asIPv4Address
            dhcp.opt121Routes.get(index).destinationSubnet shouldBe message
                .getOpt121Routes(index).getDstSubnet.asJava

            dhcp.hosts.get(index).mac.toString shouldBe message.getHosts(index)
                .getMac
            dhcp.hosts.get(index).address shouldBe message.getHosts(index)
                .getIpAddress.asIPv4Address
            dhcp.hosts.get(index).name shouldBe message.getHosts(index)
                .getName

            for (opt <- 0 until dhcp.hosts.get(index).extraDhcpOptions.size()) {
                dhcp.hosts.get(index).extraDhcpOptions.get(opt).name shouldBe message
                    .getHosts(index).getExtraDhcpOpts(opt).getName
                dhcp.hosts.get(index).extraDhcpOptions.get(opt).value shouldBe message
                    .getHosts(index).getExtraDhcpOpts(opt).getValue
            }
        }
    }

}
