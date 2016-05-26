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

import org.midonet.cluster.data.storage.StateTableEncoder.{Ip4ToMacEncoder, MacToIdEncoder, MacToIp4Encoder}
import org.midonet.packets.{IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class StateTableEncoderTest extends FlatSpec with Matchers with GivenWhenThen {

    def testEncode[K, V](encoder: StateTableEncoder[K, V],
                         key: K, value: V): Unit = {
        When("Encoding the pair with version 1")
        val path = encoder.encodePath(key, value, 1)

        Then("The string should container the 3-tuple separated by comma")
        path shouldBe s"/$key,$value,0000000001"

        When("Decoding the path")
        val (k, v, version) = encoder.decodePath(path)

        Then("The key, value and versions should match")
        k shouldBe key
        v shouldBe value
        version shouldBe 1
    }

    def testEncodePersistent[K, V](encoder: StateTableEncoder[K, V],
                                   key: K, value: V): Unit = {
        When("Encoding the persistent pair")
        val path = encoder.encodePersistentPath(key, value)

        Then("The string should container the 3-tuple separated by comma")
        path shouldBe s"/$key,$value,${Int.MaxValue}"

        When("Decoding the path")
        val (k, v, version) = encoder.decodePath(path)

        Then("The key, value and versions should match")
        k shouldBe key
        v shouldBe value
        version shouldBe Int.MaxValue
    }

    def testBadInput[K, V](encoder: StateTableEncoder[K, V],
                           key: K, value: V): Unit = {
        Then("The encoder should handle an invalid key")
        encoder.decodePath(s"invalid-key,$value,0000000001") shouldBe null

        And("The encoder should handle an invalid value")
        encoder.decodePath(s"$key,invalid-value,0000000001") shouldBe null

        And("The encoder should handle an invalid version")
        encoder.decodePath(s"$key,$value,invalid-version") shouldBe null

        And("The encoder should handle wrong token numbers")
        encoder.decodePath(s"$key,$value") shouldBe null
    }

    "Persistent version" should "be maximum integer value" in {
        StateTableEncoder.PersistentVersion shouldBe Int.MaxValue
    }

    "MAC-to-ID encoder" should "encode and decode versioned values" in {
        Given("A MAC-ID pair")
        testEncode[MAC, UUID](MacToIdEncoder, MAC.random(), UUID.randomUUID())
    }

    "MAC-to-ID encoder" should "encode and decode persistent values" in {
        Given("A MAC-ID pair")
        testEncodePersistent[MAC, UUID](MacToIdEncoder, MAC.random(),
                                        UUID.randomUUID())
    }

    "MAC-to-ID encoder" should "handle invalid input" in {
        Given("A MAC-ID pair")
        testBadInput[MAC, UUID](MacToIdEncoder, MAC.random(), UUID.randomUUID())
    }

    "IPv4-to-MAC encoder" should "encode and decode versioned values" in {
        Given("An IPv4-MAC pair")
        testEncode[IPv4Addr, MAC](Ip4ToMacEncoder, IPv4Addr.random, MAC.random())
    }

    "IPv4-to-MAC encoder" should "encode and decode persistent values" in {
        Given("An IPv4-MAC pair")
        testEncodePersistent[IPv4Addr, MAC](Ip4ToMacEncoder, IPv4Addr.random,
                                            MAC.random())
    }

    "IPv4-to-MAC encoder" should "handle invalid input" in {
        Given("An IPv4-MAC pair")
        testBadInput[IPv4Addr, MAC](Ip4ToMacEncoder, IPv4Addr.random,
                                    MAC.random())
    }

    "MAC-to-IPv4 encoder" should "encode and decode versioned values" in {
        Given("A MAC-IPv4 pair")
        testEncode[MAC, IPv4Addr](MacToIp4Encoder, MAC.random(), IPv4Addr.random)
    }

    "MAC-to-IPv4 encoder" should "encode and decode persistent values" in {
        Given("A MAC-IPv4 pair")
        testEncodePersistent[MAC, IPv4Addr](MacToIp4Encoder, MAC.random(),
                                            IPv4Addr.random)
    }

    "MAC-to-IPv4 encoder" should "handle invalid input" in {
        Given("A MAC-IPv4 pair")
        testBadInput[MAC, IPv4Addr](MacToIp4Encoder, MAC.random(),
                                    IPv4Addr.random)
    }
}
