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

package org.midonet.midolman.state

import java.util
import java.util.UUID

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import kafka.utils.ZkUtils
import org.I0Itec.zkclient.ZkClient
import org.apache.kafka.common.serialization.{StringDeserializer, Deserializer, StringSerializer, Serializer}

import org.midonet.cluster.data.storage.{InMemoryMergedMapBus, MergedMap, KafkaBus, KafkaSerialization}
import org.midonet.cluster.data.storage.MergedMap.MapUpdate
import org.midonet.cluster.storage.KafkaConfig
import org.midonet.conf.HostIdGenerator
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.state.MacLearningTable.MacTableUpdate
import org.midonet.packets.MAC
import org.midonet.util.functors._

object MacTableMergedMap {
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

    val inMemMergedMapBus = TrieMap[(UUID, Short),
                                    InMemoryMergedMapBus[MAC, PortTS]]()
}

/**
 * The implementation of a [[MacLearningTable]] for a bridge based on
 * a merged map. During initialization the table creates an underlying
 * [[MergedMap]] for the given bridge and VLAN, and exposes an
 * [[rx.Observable]] with notifications for MAC-port updates. A complete()
 * methods stops watching the underlying [[MergedMap]] and completes
 * the exposed observable when the VLAN is no longer present on the bridge.
 *
 * When [[inMem]] is true, the merged map does not rely on Kafka and resides in
 * memory.
 */
class MacTableMergedMap(bridgeId: UUID, vlanId: Short, config: KafkaConfig,
                        inMem: Boolean)
    extends MacLearningTable with MidolmanLogging {

    import MacTableMergedMap._

    override def logSource =
        s"org.midonet.devices.bridge.bridge-$bridgeId.mac-learning-table"

    val mapId = "MacTable-" + vlanId + "-" + bridgeId.toString
    val ownerId = HostIdGenerator.getHostId.toString
    val kafkaBus = if (inMem) {
        inMemMergedMapBus.getOrElseUpdate((bridgeId, vlanId), {
            val bus = new InMemoryMergedMapBus[MAC, PortTS](mapId, ownerId);
            inMemMergedMapBus.putIfAbsent((bridgeId, vlanId), bus)
                .getOrElse(bus)

        })
    } else {
        val zkClient = ZkUtils.createZkClient(config.zkHosts,
                                              config.zkConnectionTimeout,
                                              config.zkSessionTimeout)
        new KafkaBus[MAC, PortTS](mapId, ownerId, config, zkClient,
                                  new MacMergedMapSerialization())
    }
    val map = new MergedMap[MAC, PortTS](kafkaBus)(new PortOrdering())

    override def add(mac: MAC, portId: UUID): Unit = {
        map.putOpinion(mac, (portId, System.nanoTime()))
        log.info("Added MAC {} VLAN {} to port {}", mac,
                 Short.box(vlanId), portId)
    }

    /** Obsolete method. */
    override def notify(cb: Callback3[MAC, UUID, UUID]): Unit = ???

    override def get(mac: MAC): UUID = map.get(mac) match {
        case null => null
        case (portId, ts) => portId
    }

    override def remove(mac: MAC): Unit = {
        map.removeOpinion(mac)
        log.info("Removed opinion for MAC {} and VLAN {}", mac,
                 Short.box(vlanId))
    }

    override def remove(mac: MAC, portId: UUID): Unit = remove(mac)

    override def observable = map.observable.map[MacTableUpdate](
        makeFunc1((update: MapUpdate[MAC, PortTS]) => {
            val mac = update.key
            val oldPort = if (update.oldValue ne null) {
                update.oldValue._1
            } else {
                null
            }
            val newPort = if (update.newValue ne null) {
                update.newValue._1
            } else {
                null
            }
            MacTableUpdate(vlanId, mac, oldPort, newPort)
        }))

    override def complete(): Unit = map.close()
}
