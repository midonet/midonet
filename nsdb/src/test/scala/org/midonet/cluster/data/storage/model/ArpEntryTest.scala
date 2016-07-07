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

package org.midonet.cluster.data.storage.model

import org.apache.commons.lang.SerializationException
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import org.midonet.packets.MAC
import org.midonet.packets.MAC.InvalidMacException

@RunWith(classOf[JUnitRunner])
class ArpEntryTest extends FlatSpec with GivenWhenThen with Matchers {

    "ARP entry" should "decode from correct string" in {
        Given("A correct ARP entry string")
        val string = "00:01:02:03:04:05;1;2;3"

        Then("The entry can decode the string")
        val entry = ArpEntry.decode(string)
        entry.mac shouldBe MAC.fromString("00:01:02:03:04:05")
        entry.expiry shouldBe 1L
        entry.stale shouldBe 2L
        entry.lastArp shouldBe 3L
    }

    "ARP entry" should "decode null MAC" in {
        Given("A correct ARP entry string")
        val string = "null;1;2;3"

        Then("The entry can decode the string")
        val entry = ArpEntry.decode(string)
        entry.mac shouldBe null
        entry.expiry shouldBe 1L
        entry.stale shouldBe 2L
        entry.lastArp shouldBe 3L
    }

    "ARP entry" should "throw on incorrect string tokens" in {
        Given("An correct ARP entry string")
        val string = "0;1;2"

        Then("Decoding the string throws")
        intercept[SerializationException] {
            ArpEntry.decode(string)
        }
    }

    "ARP entry" should "throw on incorrect MAC" in {
        Given("An correct ARP entry string")
        val string = "invalid-mac;1;2;3"

        Then("Decoding the string throws")
        intercept[InvalidMacException] {
            ArpEntry.decode(string)
        }
    }

    "ARP entry" should "throw on incorrect numbers" in {
        Given("An correct ARP entry string")
        val string = "00:01:02:03:04:05;1;invalid-stale;3"

        Then("Decoding the string throws")
        intercept[NumberFormatException] {
            ArpEntry.decode(string)
        }
    }

    "ARP entry" should "encode with non-null MAC" in {
        Given("An ARP entry with non-null MAC")
        val entry = ArpEntry(MAC.fromString("00:01:02:03:04:05"), 1L, 2L, 3L)

        Then("Encoding the entry returns the correct string")
        entry.encode shouldBe "00:01:02:03:04:05;1;2;3"
    }

    "ARP entry" should "encode with null MAC" in {
        Given("An ARP entry with null MAC")
        val entry = ArpEntry(null, 1L, 2L, 3L)

        Then("Encoding the entry returns the correct string")
        entry.encode shouldBe "null;1;2;3"
    }

    "ARP entry" should "implement toString" in {
        Given("An ARP entry")
        val entry = ArpEntry(MAC.fromString("00:01:02:03:04:05"), 1L, 2L, 3L)

        Then("The ARP entry should convert to a string")
        entry.toString should not be null
    }

}
