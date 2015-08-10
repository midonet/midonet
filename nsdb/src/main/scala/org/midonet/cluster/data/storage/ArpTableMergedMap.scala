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

import java.util
import java.util.UUID

import com.typesafe.scalalogging.Logger
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer}
import org.slf4j.LoggerFactory
import rx.Observable

import org.midonet.cluster.data.storage.ArpTableMergedMap.MacTS
import org.midonet.cluster.data.storage.KafkaBus.Opinion
import org.midonet.cluster.data.storage.MergedMap.Update
import org.midonet.cluster.data.storage.StateTable.ArpTableUpdate
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.functors._

object ArpTableMergedMap {
    case class MacTS(mac: MAC, ts: Long)
    type ARPOpinion = Opinion[IPv4Addr, MacTS]

    class MacOrdering extends Ordering[MacTS]  {
        override def compare(x: MacTS, y: MacTS): Int =
            if (x.ts < y.ts) -1
            else if (x.ts == y.ts) 0
            else 1
    }

    class ArpMergedMapSerialization extends KafkaSerialization[IPv4Addr, MacTS] {
        class IPOpinionEncoder() extends Serializer[ARPOpinion] {
            val stringEncoder = new StringSerializer()

            override def close(): Unit = stringEncoder.close()
            override def configure(configs: util.Map[String, _],
                                   isKey: Boolean): Unit = {}

            override def serialize(topic: String, opinion: ARPOpinion)
            : Array[Byte] = {
                val strBuffer = new StringBuffer()
                if (opinion._1 eq null) {
                    throw new IllegalArgumentException("Opinion with null key")
                }
                strBuffer.append(opinion._1.toString)
                strBuffer.append("-")
                if (opinion._2 != null) {
                    val mac = opinion._2.mac
                    val ts = opinion._2.ts
                    strBuffer.append(mac + "/" + ts)
                } else {
                    strBuffer.append("null")
                }
                strBuffer.append("-")
                if (opinion._3 eq null) {
                    throw new IllegalArgumentException("Opinion with null owner")
                }
                strBuffer.append(opinion._3)
                stringEncoder.serialize(topic, strBuffer.toString)
            }
        }

        class IPOpinionDecoder() extends Deserializer[ARPOpinion] {

            val stringDecoder = new StringDeserializer()

            private def nullFilter(value: String): String = value match {
                case "null" => null
                case _ => value
            }

            override def close(): Unit = stringDecoder.close()

            override def configure(configs: util.Map[String, _],
                                   isKey: Boolean): Unit = {}

            override def deserialize(topic: String, opinion: Array[Byte])
            : ARPOpinion = {
                val msgAsString = stringDecoder.deserialize(topic, opinion)
                val tokens = msgAsString.split("-")
                if (tokens.length != 3) {
                    throw new IllegalArgumentException("Opinion with " +
                                                       "incorrect format: " + msgAsString)
                } else {
                    val ip = IPv4Addr.fromString(tokens(0))
                    val macTS = nullFilter(tokens(1))
                    val owner = nullFilter(tokens(2))
                    if (macTS == null) {
                        (ip, null, owner)
                    } else {
                        val macTokens = macTS.split("/")
                        if (macTokens.length != 2) {
                            throw new IllegalArgumentException("Opinion with " +
                                                               "incorrect format: " + msgAsString)
                        } else {
                            val mac = MAC.fromString(macTokens(0))
                            val ts = macTokens(1).toLong
                            (ip, MacTS(mac, ts), owner)
                        }
                    }
                }
            }
        }

        private val opinionDecoder = new IPOpinionDecoder()
        private val opinionEncoder = new IPOpinionEncoder()

        override def keyAsString(key: IPv4Addr): String = key.toString
        override def messageEncoder: Serializer[ARPOpinion] = opinionEncoder
        override def messageDecoder: Deserializer[ARPOpinion] = opinionDecoder
    }
}

/**
 * The implementation of an ARP table for a bridge based on [[MergedMap]].
 */
class ArpTableMergedMap(bridgeId: UUID, map: MergedMap[IPv4Addr, MacTS])
    extends StateTable[IPv4Addr, MAC, ArpTableUpdate] {

    import ArpTableMergedMap._

    private val log = Logger(LoggerFactory.getLogger(
        s"org.midonet.devices.bridge.bridge-$bridgeId.arp-table"))

    override def add(ip: IPv4Addr, mac: MAC): Unit = {
        map.putOpinion(ip, MacTS(mac, System.nanoTime()))
        log.info("Added IP {} to MAC {}", ip, mac)
    }
    override def addPersistent(ip: IPv4Addr, mac: MAC): Unit = {
        map.putOpinion(ip, MacTS(mac, Long.MaxValue))
        log.info("Added persistent IP {} to MAC {}", ip, mac)
    }

    override def get(ip: IPv4Addr): MAC = map.get(ip) match {
        case null => null
        case MacTS(mac, ts) => mac
    }

    override def remove(ip: IPv4Addr): Unit = {
        map.removeOpinion(ip)
        log.info("Removed opinion for IP {}", ip)
    }
    override def remove(ip: IPv4Addr, mac: MAC): Unit = remove(ip)

    override def contains(ip: IPv4Addr): Boolean = map.containsKey(ip)
    override def contains(ip: IPv4Addr, mac: MAC): Boolean = {
        val value = map.get(ip)
        (value ne null) && (value.mac.equals(mac))
    }
    override def containsPersistent(ip: IPv4Addr, mac: MAC): Boolean = {
        val macTS = map.get(ip)
        macTS.mac == mac && macTS.ts == Long.MaxValue
    }

    override def getByValue(mac: MAC): Set[IPv4Addr] =
        map.snapshot.filter(entry => entry._2.mac == mac)
            .map(entry => entry._1).toSet

    override def snapshot: Map[IPv4Addr, MAC] =
        map.snapshot.map(entry => (entry._1, entry._2.mac))

    override def observable: Observable[ArpTableUpdate] =
        map.observable.map[ArpTableUpdate](
            makeFunc1((update: Update[IPv4Addr, MacTS]) => {
                val ip = update.key
                val oldMac = if (update.oldValue ne null) {
                    update.oldValue.mac
                } else {
                    null
                }
                val newMac = if (update.newValue ne null) {
                    update.newValue.mac
                } else {
                    null
                }
                new ArpTableUpdate(ip, oldMac, newMac)
            }))

    override def close(): Unit = map.close()
}
