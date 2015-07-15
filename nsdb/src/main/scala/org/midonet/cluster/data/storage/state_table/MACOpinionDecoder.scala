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
import java.util.UUID

import org.apache.kafka.common.serialization.{StringDeserializer, Deserializer}

import org.midonet.cluster.data.storage.state_table.MacTableMergedMap.{MACOpinion, PortTS}
import org.midonet.packets.MAC

class MACOpinionDecoder() extends Deserializer[MACOpinion] {

    val stringDecoder = new StringDeserializer()

    private def nullAwareParse(value: String): String = value match {
        case "null" => null
        case _ => value
    }

    override def close(): Unit = stringDecoder.close()

    override def configure(configs: util.Map[String, _],
                           isKey: Boolean): Unit = {}

    override def deserialize(topic: String, opinion: Array[Byte])
    : MACOpinion = {
        val msgAsString = stringDecoder.deserialize(topic, opinion)
        val tokens = msgAsString.split("/")
        if (tokens.length != 3) {
            throw new IllegalArgumentException("Opinion with " +
                                               "incorrect format: " + msgAsString)
        }

        val mac = MAC.fromString(tokens(0))
        val portTS = nullAwareParse(tokens(1))
        val owner = nullAwareParse(tokens(2))
        if (portTS == null) {
            (mac, null, owner)
        } else {
            val portTokens = portTS.split(":")
            if (portTokens.length != 2) {
                throw new IllegalArgumentException("Opinion with " +
                                                   "incorrect format: " + msgAsString)
            } else {
                val portId = UUID.fromString(portTokens(0))
                val ts = portTokens(1).toLong
                (mac, PortTS(portId, ts), owner)
            }
        }
    }
}
