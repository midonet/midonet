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

package org.midonet.cluster.state

import java.util
import java.util.UUID

import com.google.inject.Inject
import org.I0Itec.zkclient.ZkClient
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer}

import org.midonet.cluster.data.storage.{KafkaBus, KafkaSerialization, MergedMap}
import org.midonet.cluster.state.MergedMapStateStorage.{MacMergedMapSerialization, PortOrdering, PortTS}
import org.midonet.cluster.storage.KafkaConfig
import org.midonet.conf.HostIdGenerator
import org.midonet.packets.MAC

object MergedMapStateStorage {
    type PortTS = (UUID, Long)
    type MACOpinion = (MAC, PortTS, String)

    class PortOrdering extends Ordering[PortTS]  {
        override def compare(x: PortTS, y: PortTS): Int =
            if (x._2 < y._2) -1
            else if (x._2 == y._2) 0
            else 1
    }

    class MacMergedMapSerialization extends KafkaSerialization[MAC, PortTS] {
        class MACOpinionEncoder() extends Serializer[MACOpinion] {
            val stringEncoder = new StringSerializer()

            override def close(): Unit = stringEncoder.close()
            override def configure(configs: util.Map[String, _],
                                   isKey: Boolean): Unit = {}

            override def serialize(topic: String, opinion: MACOpinion)
            : Array[Byte] = {
                val strBuffer = new StringBuffer()
                strBuffer.append(opinion._1.toString)
                strBuffer.append("-")
                val portId = opinion._2._1
                val portTS = opinion._2._2
                strBuffer.append(portId + ":" + portTS)
                strBuffer.append("-")
                strBuffer.append(opinion._3)
                stringEncoder.serialize(topic, strBuffer.toString)
            }
        }

        class MACOpinionDecoder() extends Deserializer[MACOpinion] {

            val stringDecoder = new StringDeserializer()

            private def nullFilter(value: String): String = value match {
                case "null" => null
                case _ => value
            }

            override def close(): Unit = stringDecoder.close()

            override def configure(configs: util.Map[String, _],
                                   isKey: Boolean): Unit = {}

            override def deserialize(topic: String, opinion: Array[Byte])
            : MACOpinion = {
                val msgAsString = stringDecoder.deserialize(topic, opinion)
                val tokens = msgAsString.split("-")
                if (tokens.length != 3) {
                    throw new IllegalArgumentException("Opinion with " +
                        "incorrect format: " + msgAsString)
                } else {
                    val mac = MAC.fromString(tokens(0))
                    val portTS = nullFilter(tokens(1))
                    val owner = nullFilter(tokens(2))
                    if (portTS != null) {
                        (mac, null, owner)
                    } else {
                        val portTokens = portTS.split(":")
                        if (portTokens.length != 2) {
                            throw new IllegalArgumentException("Opinion with " +
                                "incorrect format: " + msgAsString)
                        } else {
                            val portId = UUID.fromString(portTokens(0))
                            val ts = portTokens(1).toLong
                            (mac, (portId, ts), owner)
                        }
                    }
                }
            }
        }

        private val opinionDecoder = new MACOpinionDecoder()
        private val opinionEncoder = new MACOpinionEncoder()

        def keyAsString(key: MAC): String = key.toString
        def messageEncoder: Serializer[MACOpinion] = opinionEncoder
        def messageDecoder: Deserializer[MACOpinion] = opinionDecoder
    }
}

class MergedMapStateStorage @Inject() (config: KafkaConfig, zkClient: ZkClient)
    extends MergedMapState {
    /**
     * Returns true iff merged maps are enabled.
     */
    override def isEnabled: Boolean = config.useMergedMaps

    /**
     * Gets the MAC-port merged map for the specified bridge.
     */
    override def bridgeMacMergedMap(bridgeId: UUID, vlanId: Short)
    :MergedMap[MAC, PortTS] = {
        val mapId = "MacTable-" + vlanId + "-" + bridgeId.toString
        val ownerId = HostIdGenerator.getHostId.toString
        val kafkaBus =
            new KafkaBus[MAC, PortTS](mapId, ownerId, config, zkClient,
                                      new MacMergedMapSerialization())
        new MergedMap[MAC, PortTS](kafkaBus)(new PortOrdering())
    }
}
