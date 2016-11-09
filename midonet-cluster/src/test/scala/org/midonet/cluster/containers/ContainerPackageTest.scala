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

package org.midonet.cluster.containers

import scala.collection.mutable

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.containers
import org.midonet.packets.IPv4Subnet

@RunWith(classOf[JUnitRunner])
class ContainerPackageTest extends FlatSpec with Matchers
                                            with GivenWhenThen
                                            with TopologyBuilder {

    "Container subnet allocation" should "select a free subnet" in {
        Given("A list of mocked ports before and after the container subnet")
        var ports = mutable.MutableList(
            createRouterPort(portSubnet = new IPv4Subnet("1.1.0.0", 16)),
            createRouterPort(portSubnet = new IPv4Subnet("200.200.0.0", 16)))
        And("A port within the container range already in the port list")
        ports += createRouterPort(portSubnet = new IPv4Subnet("169.254.0.4", 30))

        Then("A subnet within the container range is returned")
        val subnet1 = containers.findLocalSubnet(ports)
        val expected1 = new IPv4Subnet("169.254.0.0", 30)
        subnet1 shouldBe expected1

        When("Updating the list of ports")
        ports += createRouterPort(portSubnet = expected1)

        And("Getting another free local subnet")
        val subnet2 = containers.findLocalSubnet(ports)
        val expected2 = new IPv4Subnet("169.254.0.8", 30)

        Then("The next available should be provided, not the same")
        subnet2 shouldBe expected2

        When("Adding the whole container range into the list")
        ports += createRouterPort(portSubnet = new IPv4Subnet("169.254.0.0", 16))

        And("Getting another free local subnet")
        Then("No such element exception should be thrown")
        intercept[NoSuchElementException] {
            val subnet3 = containers.findLocalSubnet(ports)
        }
    }
}
