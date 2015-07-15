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

import com.typesafe.scalalogging.Logger
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer}
import org.slf4j.LoggerFactory
import rx.Observable

import org.midonet.cluster.data.storage.KafkaBus.Opinion
import org.midonet.cluster.data.storage.MergedMap.Update
import org.midonet.cluster.data.storage.StateTable.MacTableUpdate
import org.midonet.cluster.data.storage._
import org.midonet.midolman.state.MacTableMergedMap.PortTS
import org.midonet.packets.MAC
import org.midonet.util.functors._

object MacTableMergedMap {
    case class PortTS(id: UUID, ts: Long)
    type MACOpinion = Opinion[MAC, PortTS]

    class PortOrdering extends Ordering[PortTS]  {
        override def compare(x: PortTS, y: PortTS): Int =
            if (x.ts < y.ts) -1
            else if (x.ts == y.ts) 0
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
                val portId = opinion._2.id
                val ts = opinion._2.ts
                strBuffer.append(portId + ":" + ts)
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
                            (mac, PortTS(portId, ts), owner)
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
 * The implementation of a [[StateTable]] for a bridge based on
 * a merged map. During initialization the table creates an underlying
 * [[MergedMap]] for the given bridge and VLAN, and exposes an
 * [[rx.Observable]] with notifications for MAC-port updates. A close()
 * methods closes the underlying [[MergedMap]] when the VLAN is no longer
 * present on the bridge.
 */
class MacTableMergedMap(bridgeId: UUID, vlanId: Short,
                        bus: MergedMapBus[MAC, PortTS])
    extends StateTable[MAC, UUID, MacTableUpdate] {

    import MacTableMergedMap._

    val log = Logger(LoggerFactory.getLogger(
        s"org.midonet.devices.bridge.bridge-$bridgeId.mac-learning-table"))

    val map = new MergedMap[MAC, PortTS](bus)(new PortOrdering())

    override def add(mac: MAC, portId: UUID): Unit = {
        map.putOpinion(mac, PortTS(portId, System.nanoTime()))
        log.info("Added MAC {} VLAN {} to port {}", mac,
                 Short.box(vlanId), portId)
    }
    override def addPersistent(mac: MAC, portId: UUID): Unit = {
        map.putOpinion(mac, PortTS(portId, Long.MaxValue))
        log.info("Added persistent MAC {} VLAN {} to port {}", mac,
                 Short.box(vlanId), portId)
    }

    override def get(mac: MAC): UUID = map.get(mac) match {
        case null => null
        case PortTS(portId, ts) => portId
    }

    override def remove(mac: MAC): Unit = {
        map.removeOpinion(mac)
        log.info("Removed opinion for MAC {} and VLAN {}", mac,
                 Short.box(vlanId))
    }
    override def remove(mac: MAC, portId: UUID): Unit = remove(mac)

    override def contains(mac: MAC): Boolean = map.containsKey(mac)
    override def contains(mac: MAC, portId: UUID): Boolean =
        map.get(mac).equals(portId)
    override def containsPersistent(mac: MAC, portId: UUID): Boolean =
        map.get(mac).ts == Long.MaxValue

    override def getByValue(portId: UUID): Set[MAC] =
        map.snapshot.filter(entry => entry._2.id == portId)
                    .map(entry => entry._1).toSet

    override def snapshot: Map[MAC, UUID] =
        map.snapshot.map(entry => (entry._1, entry._2.id))

    override def observable: Observable[MacTableUpdate] =
        map.observable.map[MacTableUpdate](
            makeFunc1((update: Update[MAC, PortTS]) => {
                val mac = update.key
                val oldPort = if (update.oldValue ne null) {
                    update.oldValue.id
                } else {
                    null
                }
                val newPort = if (update.newValue ne null) {
                    update.newValue.id
                } else {
                    null
                }
                new MacTableUpdate(vlanId, mac, oldPort, newPort)
            }))

    override def close(): Unit = map.close()
}
