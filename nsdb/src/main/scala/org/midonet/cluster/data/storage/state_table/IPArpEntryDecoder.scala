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

import java.util

import org.apache.kafka.common.serialization.{StringDeserializer, Deserializer}

import org.midonet.cluster.data.storage.ArpCacheEntry
import org.midonet.cluster.data.storage.state_table.RouterArpCacheMergedMap.{ARPCacheOpinion, ArpEntryTS}
import org.midonet.packets.IPv4Addr

class IPArpEntryDecoder() extends Deserializer[ARPCacheOpinion] {

    val stringDecoder = new StringDeserializer()

    private def nullFilter(value: String): String = value match {
        case "null" => null
        case _ => value
    }

    override def close(): Unit = stringDecoder.close()

    override def configure(configs: util.Map[String, _],
                           isKey: Boolean): Unit = {}

    override def deserialize(topic: String, opinion: Array[Byte])
    : ARPCacheOpinion = {
        val msgAsString = stringDecoder.deserialize(topic, opinion)
        val tokens = msgAsString.split("-")
        if (tokens.length != 3) {
            throw new IllegalArgumentException("Opinion with " +
                "incorrect format: " + msgAsString)
        }

        val ip = IPv4Addr.fromString(tokens(0))
        val arpEntryTS = nullFilter(tokens(1))
        val owner = nullFilter(tokens(2))
        if (arpEntryTS == null) {
            (ip, null, owner)
        } else {
            val arpEntryTokens = arpEntryTS.split("/")
            if (arpEntryTokens.length != 2) {
                throw new IllegalArgumentException("Opinion with " +
                    "incorrect format: " + msgAsString)
            }
            val arpEntry = ArpCacheEntry.decode(arpEntryTokens(0))
            val ts = arpEntryTokens(1).toLong
            (ip, ArpEntryTS(arpEntry, ts), owner)
        }
    }
}
