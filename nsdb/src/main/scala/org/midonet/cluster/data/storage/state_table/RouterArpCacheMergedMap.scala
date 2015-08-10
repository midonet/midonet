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

import org.midonet.cluster.data.storage.KafkaBus._
import org.midonet.cluster.data.storage.MergedMap.Update
import org.midonet.cluster.data.storage.state_table.RouterArpCacheMergedMap.{ArpCacheUpdate, ArpEntryTS}
import org.midonet.cluster.data.storage.{ArpCacheEntry, KafkaSerialization, MergedMap}
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.makeFunc1

object RouterArpCacheMergedMap {
    case class ArpEntryTS(arpEntry: ArpCacheEntry, ts: Long)
    type ARPCacheOpinion = Opinion[IPv4Addr, ArpEntryTS]

    case class ArpCacheUpdate(ipV4Addr: IPv4Addr, oldEntry: ArpCacheEntry,
                              newEntry: ArpCacheEntry)
        extends Update[IPv4Addr, ArpCacheEntry](ipV4Addr, oldEntry, newEntry) {
        override val toString = s"{ip=$ipV4Addr oldEntry=$oldEntry " +
                                s"newEntry=$newEntry}"
    }

    class ArpCacheOrdering extends Ordering[ArpEntryTS]  {
        override def compare(x: ArpEntryTS, y: ArpEntryTS): Int =
            if (x.ts < y.ts) -1
            else if (x.ts == y.ts) 0
            else 1
    }

    class ArpCacheMergedMapSerialization
        extends KafkaSerialization[IPv4Addr, ArpEntryTS] {

        private val opinionDecoder = new IPArpEntryDecoder()
        private val opinionEncoder = new IPArpEntryEncoder()

        override def keyAsString(key: IPv4Addr): String = key.toString
        override def messageEncoder: Serializer[ARPCacheOpinion] = opinionEncoder
        override def messageDecoder: Deserializer[ARPCacheOpinion] = opinionDecoder
    }
}

class RouterArpCacheMergedMap(routerId: UUID,
                              map: MergedMap[IPv4Addr, ArpEntryTS])
    extends StateTable[IPv4Addr, ArpCacheEntry, ArpCacheUpdate] {

    import RouterArpCacheMergedMap._

    private val log = Logger(LoggerFactory.getLogger(
        s"org.midonet.devices.router.router-$routerId.arp-cache"))

    override def add(ip: IPv4Addr, arpEntry: ArpCacheEntry): Unit = {
        map.putOpinion(ip, ArpEntryTS(arpEntry, System.nanoTime()))
        log.info("Added IP {} to ARP entry {}", ip, arpEntry)
    }
    override def addPersistent(ip: IPv4Addr, arpEntry: ArpCacheEntry): Unit = {
        map.putOpinion(ip, ArpEntryTS(arpEntry, Long.MaxValue))
        log.info("Added persistent IP {} to ARP entry {}", ip, arpEntry)
    }

    override def remove(ip: IPv4Addr): Unit = {
        map.removeOpinion(ip)
        log.info("Removed opinion for IP {}", ip)
    }

    override def snapshot: Map[IPv4Addr, ArpCacheEntry] = map.snapshot.map(
        (entry: (IPv4Addr, ArpEntryTS)) => (entry._1, entry._2.arpEntry)
    )

    override def get(ip: IPv4Addr): ArpCacheEntry = map.get(ip) match {
        case null => null
        case arpEntryTS => arpEntryTS.arpEntry
    }

    override def observable: Observable[ArpCacheUpdate] =
        map.observable.map[ArpCacheUpdate](
            makeFunc1((update: Update[IPv4Addr, ArpEntryTS]) => {
                val oldArpEntry = if (update.oldValue eq null) null
                                  else update.oldValue.arpEntry
                val newArpEntry = if (update.newValue eq null) null
                                  else update.newValue.arpEntry

                ArpCacheUpdate(update.key, oldArpEntry, newArpEntry)
            }))

    override def remove(ip: IPv4Addr, arpEntry: ArpCacheEntry): Unit = remove(ip)

    override def contains(ip: IPv4Addr): Boolean = map.containsKey(ip)

    override def contains(ip: IPv4Addr, arpEntry: ArpCacheEntry): Boolean = {
        val value = map.get(ip)
        (value ne null) && (value.arpEntry.equals(arpEntry))
    }

    override def getByValue(arpEntry: ArpCacheEntry): Set[IPv4Addr] =
        snapshot.filter(_._2.equals(arpEntry)).map(_._1).toSet

    override def containsPersistent(ip: IPv4Addr,
                                    arpEntry: ArpCacheEntry): Boolean = {
        val value = map.get(ip)
        (value ne null) && (value.ts == Long.MaxValue) &&
            (value.arpEntry.equals(arpEntry))
    }

    override def close(): Unit = map.close()
}
