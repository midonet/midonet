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

package org.midonet.cluster.data.storage.state_table

import org.apache.kafka.common.serialization.StringSerializer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.data.storage.state_table.RouterArpCacheMergedMap.{ArpCacheMergedMapSerialization, ArpEntryTS}
import org.midonet.cluster.data.storage.{ArpCacheEntry, KafkaSerialization}
import org.midonet.packets.{IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class ArpCacheMergedMapSerializationTest extends FeatureSpec with BeforeAndAfter
                                         with Matchers {

    var serialization: KafkaSerialization[IPv4Addr, ArpEntryTS] = _

    before {
        serialization = new ArpCacheMergedMapSerialization()
    }

    private def newArpEntry(mac: MAC): ArpCacheEntry =
        new ArpCacheEntry(mac, expiry = 0l, stale = 0l, lastArp = 0l)

    feature("Serialization") {
        scenario("serializing opinion") {
            val ip = IPv4Addr.random
            val mac = MAC.random()
            val ts = System.nanoTime()
            val arpOpinion = (ip, ArpEntryTS(newArpEntry(mac), ts), "owner")

            val encoder = serialization.messageEncoder
            val decoder = serialization.messageDecoder
            val bytes = encoder.serialize("topic", arpOpinion)
            decoder.deserialize("topic", bytes) shouldBe arpOpinion
        }

        scenario("serializing opinion with null value") {
            val ip = IPv4Addr.random
            val arpOpinion = (ip, null, "owner")

            val encoder = serialization.messageEncoder
            val decoder = serialization.messageDecoder
            val bytes = encoder.serialize("topic", arpOpinion)
            decoder.deserialize("topic", bytes) shouldBe arpOpinion
        }

        scenario("serializing invalid opinion with null key/owner") {
            val encoder = serialization.messageEncoder

            intercept[NullPointerException] {
                encoder.serialize("topic", (null, null, "owner"))
            }

            intercept[NullPointerException] {
                encoder.serialize("topic", (IPv4Addr.random, null, null))
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
            val ip = IPv4Addr.random
            serialization.keyAsString(ip) shouldBe ip.toString
        }
    }
}
