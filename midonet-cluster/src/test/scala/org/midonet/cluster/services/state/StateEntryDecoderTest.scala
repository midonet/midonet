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

package org.midonet.cluster.services.state

import java.util.UUID

import com.google.protobuf.ByteString

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.packets.{IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class StateEntryDecoderTest extends FlatSpec with Matchers with GivenWhenThen {

    "Default decoder" should "handle any class" in {
        Given("A decoder for any class")
        val decoder = StateEntryDecoder.get(classOf[Any])

        And("A corresponding string")
        val string = "some-encoded-value"

        When("Decoding the string")
        val value = decoder.decode(string)

        Then("The value should be a message with string encoded as binary")
        value.hasData32 shouldBe false
        value.hasData64 shouldBe false
        value.hasDataVariable shouldBe true

        And("The data should match")
        value.getDataVariable shouldBe ByteString.copyFromUtf8(string)
    }

    "MAC decoder" should "handle MAC addresses" in {
        Given("A decoder for a MAC class")
        val decoder = StateEntryDecoder.get(classOf[MAC])

        And("A corresponding encoded MAC")
        val string = "01:02:03:04:05:06"

        When("Decoding the string")
        val value = decoder.decode(string)

        Then("The value should be a message with string encoded as long")
        value.hasData32 shouldBe false
        value.hasData64 shouldBe true
        value.hasDataVariable shouldBe false

        And("The data should match")
        value.getData64 shouldBe MAC.stringToLong(string)
    }

    "IPv4 decoder" should "handle IPv4 addresses" in {
        Given("A decoder for a IPv4 class")
        val decoder = StateEntryDecoder.get(classOf[IPv4Addr])

        And("A corresponding encoded IPv4 address")
        val string = "1.2.3.4"

        When("Decoding the string")
        val value = decoder.decode(string)

        Then("The value should be a message with string encoded as long")
        value.hasData32 shouldBe true
        value.hasData64 shouldBe false
        value.hasDataVariable shouldBe false

        And("The data should match")
        value.getData32 shouldBe IPv4Addr.stringToInt(string)
    }

    "UUID decoder" should "handle UUIDs" in {
        Given("A decoder for an UUID class")
        val decoder = StateEntryDecoder.get(classOf[UUID])

        And("A corresponding encoded IPv4 address")
        val string = "01234567-89AB-CDEF-0123-456789ABCDEF"

        When("Decoding the string")
        val value = decoder.decode(string)

        Then("The value should be a message with string encoded as long")
        value.hasData32 shouldBe false
        value.hasData64 shouldBe false
        value.hasDataVariable shouldBe true

        And("The data should match")
        value.getDataVariable.size() shouldBe 16
        value.getDataVariable.toByteArray shouldBe Array(
            0x01.toByte, 0x23.toByte, 0x45.toByte, 0x67.toByte,
            0x89.toByte, 0xAB.toByte, 0xCD.toByte, 0xEF.toByte,
            0x01.toByte, 0x23.toByte, 0x45.toByte, 0x67.toByte,
            0x89.toByte, 0xAB.toByte, 0xCD.toByte, 0xEF.toByte
        )
    }

}
