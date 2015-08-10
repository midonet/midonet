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

import java.util.UUID

import com.typesafe.scalalogging.Logger
import org.apache.kafka.common.serialization.{Deserializer, Serializer}
import org.slf4j.LoggerFactory
import rx.Observable

import org.midonet.cluster.data.storage.KafkaBus.Opinion
import org.midonet.cluster.data.storage.MergedMap.Update
import org.midonet.cluster.data.storage.state_table.MacTableMergedMap.{MacTableUpdate, PortTS}
import org.midonet.cluster.data.storage.{KafkaSerialization, MergedMap}
import org.midonet.packets.MAC
import org.midonet.util.functors._

object MacTableMergedMap {
    case class PortTS(id: UUID, ts: Long)
    type MACOpinion = Opinion[MAC, PortTS]

    case class MacTableUpdate(vlanId: Short, mac: MAC, oldPort: UUID,
                              newPort: UUID)
        extends Update[MAC, UUID](mac, oldPort, newPort) {
        override val toString = s"{vlan=$vlanId mac=$mac oldPort=$oldPort " +
                                s"newPort=$newPort}"
    }

    class PortOrdering extends Ordering[PortTS]  {
        override def compare(x: PortTS, y: PortTS): Int =
            if (x.ts < y.ts) -1
            else if (x.ts == y.ts) 0
            else 1
    }

    class MacMergedMapSerialization extends KafkaSerialization[MAC, PortTS] {
        private val opinionDecoder = new MACPortDecoder()
        private val opinionEncoder = new MACPortEncoder()

        def keyAsString(key: MAC): String = key.toString
        def messageEncoder: Serializer[MACOpinion] = opinionEncoder
        def messageDecoder: Deserializer[MACOpinion] = opinionDecoder
    }
}

/**
 * The implementation of the [[StateTable]] trait for a MAC table
 * based on a [[MergedMap]].
 */
class MacTableMergedMap(bridgeId: UUID, vlanId: Short,
                        map: MergedMap[MAC, PortTS])
    extends StateTable[MAC, UUID, MacTableUpdate] {

    import MacTableMergedMap._

    val log = Logger(LoggerFactory.getLogger(
        s"org.midonet.devices.bridge.bridge-$bridgeId.mac-learning-table"))

    override def add(mac: MAC, portId: UUID): Unit = {
        map.putOpinion(mac, PortTS(portId, System.nanoTime()))
        log.debug("Added MAC {} VLAN {} to port {}", mac,
                  Short.box(vlanId), portId)
    }
    override def addPersistent(mac: MAC, portId: UUID): Unit = {
        map.putOpinion(mac, PortTS(portId, Long.MaxValue))
        log.debug("Added persistent MAC {} VLAN {} to port {}", mac,
                  Short.box(vlanId), portId)
    }

    override def get(mac: MAC): UUID = map.get(mac) match {
        case null => null
        case PortTS(portId, ts) => portId
    }

    override def remove(mac: MAC): Unit = {
        map.removeOpinion(mac)
        log.debug("Removed opinion for MAC {} and VLAN {}", mac,
                  Short.box(vlanId))
    }
    override def remove(mac: MAC, portId: UUID): Unit = remove(mac)

    override def contains(mac: MAC): Boolean = map.containsKey(mac)
    override def contains(mac: MAC, portId: UUID): Boolean = {
        val portTS = map.get(mac)
        (portTS ne null) && portTS.id.equals(portId)
    }
    override def containsPersistent(mac: MAC, portId: UUID): Boolean = {
        val portTS = map.get(mac)
        (portTS ne null) && portTS.ts == Long.MaxValue
    }

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
