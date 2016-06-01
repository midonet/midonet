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

package org.midonet.cluster.data.storage

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Topology.{Network, Router}
import org.midonet.cluster.services.MidonetBackend._

@RunWith(classOf[JUnitRunner])
class LegacyStateTableStorageTest extends FlatSpec with GivenWhenThen
                                  with Matchers {

    private val root = "/root"
    private object TestStorage extends LegacyStateTableStorage {
        protected override val rootPath = root
    }

    import TestStorage.legacyTablePath

    "Legacy storage" should "handle paths for bridge MAC-port tables" in {
        Given("A bridge identifier")
        val id = UUID.randomUUID()

        Then("Storage returns the correct paths for a MAC-port table")
        legacyTablePath(classOf[Network], id, MacTable) shouldBe
            Some(s"$root/bridges/$id/mac_ports")
        legacyTablePath(classOf[Network], id, MacTable, 0.toShort) shouldBe
            Some(s"$root/bridges/$id/mac_ports")
        legacyTablePath(classOf[Network], id, MacTable, 1.toShort) shouldBe
            Some(s"$root/bridges/$id/vlans/1/mac_ports")
    }

    "Legacy storage" should "handle paths for bridge IPv4-MAC tables" in {
        Given("A bridge identifier")
        val id = UUID.randomUUID()

        Then("Storage returns the correct paths for a IPv4-MAC table")
        legacyTablePath(classOf[Network], id, Ip4MacTable) shouldBe
            Some(s"$root/bridges/$id/ip4_mac_map")
    }

    "Legacy storage" should "handle paths for router ARP tables" in {
        Given("A router identifier")
        val id = UUID.randomUUID()

        Then("Storage returns the correct paths for an ARP table")
        legacyTablePath(classOf[Router], id, ArpTable) shouldBe
            Some(s"$root/routers/$id/arp_table")
    }

}
