/*
 * Copyright 2015 Midokura SARL
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

import org.apache.kafka.common.serialization.StringSerializer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.data.storage.state_table.MacTableMergedMap
import MacTableMergedMap.{MacMergedMapSerialization, PortTS}
import org.midonet.packets.{IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class MacMergedMapSerializationTest extends FeatureSpec with BeforeAndAfter
                                    with Matchers {

    var serialization: KafkaSerialization[MAC, PortTS] = _

    before {
        serialization = new MacMergedMapSerialization()
    }

    feature("Serialization") {
        scenario("serializing opinion") {
            val mac = MAC.random()
            val port = UUID.randomUUID()
            val ts = System.nanoTime()
            val macOpinion = (mac, PortTS(port, ts), "owner")

            val encoder = serialization.messageEncoder
            val decoder = serialization.messageDecoder
            val bytes = encoder.serialize("topic", macOpinion)
            decoder.deserialize("topic", bytes) shouldBe macOpinion
        }

        scenario("serializing opinion with null value") {
            val mac = MAC.random()
            val macOpinion = (mac, null, "owner")

            val encoder = serialization.messageEncoder
            val decoder = serialization.messageDecoder
            val bytes = encoder.serialize("topic", macOpinion)
            decoder.deserialize("topic", bytes) shouldBe macOpinion
        }

        scenario("serializing invalid opinion with null key/owner") {
            val encoder = serialization.messageEncoder

            intercept[NullPointerException] {
                encoder.serialize("topic", (null, null, "owner"))
            }

            intercept[NullPointerException] {
                encoder.serialize("topic", (MAC.random(), null, null))
            }
        }

        scenario("invalid serialized opinions") {
            val strEncoder = new StringSerializer()
            val encoder = serialization.messageEncoder
            val decoder = serialization.messageDecoder

            val invalidMsg1 = strEncoder.serialize("topic", "toto/titi/tata")
            intercept[IllegalArgumentException] {
                decoder.deserialize("topic", invalidMsg1)
            }

            val invalidMsg2 = strEncoder.serialize("topic", "toto-titi")
            intercept[IllegalArgumentException] {
                decoder.deserialize("topic", invalidMsg2)
            }

            val invalidMsg3 = IPv4Addr.random.toString + "-" + MAC.random() +
                              "-owner"
            intercept[IllegalArgumentException] {
                decoder.deserialize("topic", strEncoder.serialize("topic",
                                                                  invalidMsg3))
            }
        }

        scenario("keyAsString") {
            val mac = MAC.random
            serialization.keyAsString(mac) shouldBe mac.toString
        }
    }
}
