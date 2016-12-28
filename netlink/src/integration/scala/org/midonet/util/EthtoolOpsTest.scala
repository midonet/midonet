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

package org.midonet.util

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.netlink.rtnetlink.LinkOps
import org.midonet.packets.MAC

@RunWith(classOf[JUnitRunner])
class EthtoolOpsTest extends FlatSpec with Matchers with GivenWhenThen {

    "Ethtool ops" should "get and set checksum offloading" in {
        Given("A veth pair")
        val vethName = "csumveth0"
        val LinkOps.Veth(dev, peer) = LinkOps.createVethPair(
            vethName, "csumveth1", up = true, MAC.random(), MAC.random())

        When("Enabling the TX checksum")
        EthtoolOps.setTxChecksum(vethName, enabled = true) shouldBe true

        Then("The TX checksum should be enabled")
        EthtoolOps.getTxChecksum(vethName) shouldBe true

        When("Disabling the TX checksum")
        EthtoolOps.setTxChecksum(vethName, enabled = false) shouldBe false

        Then("The TX checksum should be disabled")
        EthtoolOps.getTxChecksum(vethName) shouldBe false

        When("Enabling the RX checksum")
        EthtoolOps.setRxChecksum(vethName, enabled = true) shouldBe true

        Then("The RX checksum should be enabled")
        EthtoolOps.getRxChecksum(vethName) shouldBe true

        When("Disabling the RX checksum")
        EthtoolOps.setRxChecksum(vethName, enabled = false) shouldBe false

        Then("The RX checksum should be disabled")
        EthtoolOps.getRxChecksum(vethName) shouldBe false

        LinkOps.deleteLink(dev)
    }

}
