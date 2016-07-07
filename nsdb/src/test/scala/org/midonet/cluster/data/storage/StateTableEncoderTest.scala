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

import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID

import com.google.protobuf.ByteString

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.StateTableEncoder.{Ip4ToMacEncoder, MacToIdEncoder, MacToIp4Encoder}
import org.midonet.cluster.rpc.State.KeyValue
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

    "MAC-to-ID encoder" should "encode and decode messages" in {
        Given("A MAC-ID pair")
        val mac = MAC.random()
        val id = UUID.randomUUID()

        And("The corresponding messages")
        val macMessage = KeyValue.newBuilder().setData64(mac.asLong()).build()
        val idMessage = KeyValue.newBuilder()
            .setDataVariable(ByteString.copyFrom(
                ByteBuffer.allocate(16)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(id.getMostSignificantBits)
                    .putLong(id.getLeastSignificantBits)
                    .rewind().asInstanceOf[ByteBuffer]))
            .build()

        And("An encoder")
        val encoder = new MacToIdEncoder {
            def keyOf(kv: KeyValue): MAC = decodeKey(kv)
            def valueOf(kv: KeyValue): UUID = decodeValue(kv)
        }

        Then("The decoded key should match")
        encoder.keyOf(macMessage) shouldBe mac

        And("The decoder value should match")
        encoder.valueOf(idMessage) shouldBe id
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

    "IPv4-to-MAC encoder" should "encode and decode messages" in {
        Given("A IPv4-MAC pair")
        val address = IPv4Addr.random
        val mac = MAC.random()

        And("The corresponding messages")
        val addressMessage = KeyValue.newBuilder().setData32(address.addr).build()
        val macMessage = KeyValue.newBuilder().setData64(mac.asLong()).build()

        And("An encoder")
        val encoder = new Ip4ToMacEncoder {
            def keyOf(kv: KeyValue): IPv4Addr = decodeKey(kv)
            def valueOf(kv: KeyValue): MAC = decodeValue(kv)
        }

        Then("The decoded key should match")
        encoder.keyOf(addressMessage) shouldBe address

        And("The decoder value should match")
        encoder.valueOf(macMessage) shouldBe mac
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

    "MAC-to-IPv4 encoder" should "encode and decode messages" in {
        Given("A MAC-ID pair")
        val mac = MAC.random()
        val address = IPv4Addr.random

        And("The corresponding messages")
        val macMessage = KeyValue.newBuilder().setData64(mac.asLong()).build()
        val addressMessage = KeyValue.newBuilder().setData32(address.addr).build()

        And("An encoder")
        val encoder = new MacToIp4Encoder {
            def keyOf(kv: KeyValue): MAC = decodeKey(kv)
            def valueOf(kv: KeyValue): IPv4Addr = decodeValue(kv)
        }

        Then("The decoded key should match")
        encoder.keyOf(macMessage) shouldBe mac

        And("The decoder value should match")
        encoder.valueOf(addressMessage) shouldBe address
    }
}
