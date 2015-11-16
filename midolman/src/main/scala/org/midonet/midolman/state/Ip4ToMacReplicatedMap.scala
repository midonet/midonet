/*
 * Copyright 2014 Midokura SARL
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

import scala.collection.JavaConversions._

import org.apache.zookeeper.CreateMode

import org.midonet.cluster.backend.Directory
import org.midonet.packets.{IPv4Addr, MAC}

class Ip4ToMacReplicatedMap(dir: Directory)
    extends ReplicatedMap[IPv4Addr, MAC](dir) {
    def putPersistent(key: IPv4Addr, value: MAC): Unit = {
        Ip4ToMacReplicatedMap.addPersistentEntry(dir, key, value)
    }

    protected def encodeKey(key: IPv4Addr): String = key.toString
    protected def decodeKey(str: String): IPv4Addr = IPv4Addr.fromString(str)
    protected def encodeValue(value: MAC): String = value.toString
    protected def decodeValue(str: String): MAC = MAC.fromString(str)
}

object Ip4ToMacReplicatedMap {

    def getAsMap(dir: Directory): util.Map[IPv4Addr, MAC] =
        getAsMapBase(dir, (ip: String, mac: String, version: String) =>
            (IPv4Addr.fromString(ip), MAC.fromString(mac)))

    def getAsMapWithVersion(dir: Directory): util.Map[IPv4Addr, (MAC, Int)] =
        getAsMapBase(dir, (ip: String, mac: String, version: String) =>
            (IPv4Addr.fromString(ip), (MAC.fromString(mac), version.toInt)))

    def getAsMapBase[K, V](dir: Directory,
                           mapEntryConvert: (String, String, String) => (K, V))
    : collection.immutable.Map[K, V] = ZKExceptions.adapt {
        def makeMapEntry(path: String) = {
            val parts = ReplicatedMap.getKeyValueVersion(path)
            mapEntryConvert(parts(0), parts(1), parts(2))
        }
        dir.getChildren("/", null).map(makeMapEntry).toMap
    }

    /**
     * Check if a given ip->mac pair was explicitly set.
     */
    def hasPersistentEntry(dir: Directory, key: IPv4Addr, value: MAC): Boolean =
        ZKExceptions.adapt(
            getAsMapWithVersion(dir).get(key) match {
                case (m, Int.MaxValue) if m.equals(value) => true
                case _ => false
            }
        )

    /**
     * Check if a given ip->mac pair was learned.
     */
    def hasLearnedEntry(dir: Directory, key: IPv4Addr, value: MAC): Boolean =
        ZKExceptions.adapt(
            getAsMapWithVersion(dir).get(key) match {
                case (m, 0) if m.equals(value) => true
                case _ => false
            }
        )

    /**
     * Explicitly adds an entry with the mac value associated to an ip address.
     *
     * Warning: the caller must guarantee that no previous mapping exists for
     * that ip; overwriting an existing key results in undefined behaviour
     * (... which is more probably a bug than a feature...)
     *
     * @throws StateAccessException if a pair with the same ip and mac already
     *                              exists.
     */
    def addPersistentEntry(dir: Directory, key: IPv4Addr, value: MAC)
    = ZKExceptions.adapt(dir.add(
        encodePersistentPath(key, value), null, CreateMode.PERSISTENT))

    /**
     * Adds an entry with a learned mac for a given ip.
     *
     * Warning: the caller must guarantee that no previous mapping exists for
     * that ip: overwriting an existing key results in undefined behaviour.
     * Regular (non-learned) entries will not be replaced.
     *
     * @throws StateAccessException if a pair with the same ip and mac already
     *                              exists.
     */
    def addLearnedEntry(dir: Directory, key: IPv4Addr, value: MAC) = {
        /* Entries will be persistent because the main use case is for
         * VxGW arp supression where we're keeping MN's state consistent with
         * the VTEP's, so it's not determined by traffic but an external db */
        ZKExceptions.adapt(dir.add(
            encodeLearnedPath(key, value), null, CreateMode.PERSISTENT)
        )
    }

    /**
     * Deletes an entry forcefully (either learned or explicitly set).
     */
    def deleteEntry(dir: Directory, key: IPv4Addr, mac: MAC) = {
        getAsMapWithVersion(dir).get(key) match {
            case (m, ver) if m.equals(mac) =>
                dir.delete(encodePath(key, mac, ver))
            case _ => ()
        }
    }

    /**
     * Deletes a learned entry. Non learned entries will be ignored.
     */
    def deleteLearnedEntry(dir: Directory, key: IPv4Addr, mac: MAC) = {
        getAsMapWithVersion(dir).get(key) match {
            case (m, 0) if m.equals(mac) => dir.delete(encodePath(key, mac, 0))
            case _ => () // learned entries always carry a 0
        }
    }

    /**
     * Retrieves an entry (either learned or explicitly set).
     */
    def getEntry(dir: Directory, key: IPv4Addr): MAC = {
        getAsMapWithVersion(dir).get(key) match {
            case (m, ver) => m
            case _ => null
        }
    }

    /**
     * Get all Ip addresses associated to the same MAC (from both
     * learned and explicitly set mappings).
     */
    def getByMacValue(dir: Directory, mac: MAC): util.Set[IPv4Addr] =
        getAsMapWithVersion(dir).filter(e => e._2._1.equals(mac)).keySet

    def encodePersistentPath(k: IPv4Addr, v: MAC) =
        encodePath(k, v, Int.MaxValue)

    /*
     * We use version 0 for learned entries. This is done so to remain
     * compatible with a very likely buggy version of the ReplicatedMap that
     * uses always 1 for persistent entries, but never increments. The learned
     * entries should always be overwritten by the non-learned entries (set
     * from the API).
     */
    def encodeLearnedPath(k :IPv4Addr, v :MAC) = encodePath(k, v, 0)

    def encodePath(k :IPv4Addr, v :MAC, ver: Int) =
        ReplicatedMap.encodeFullPath(k.toString, v.toString, ver)

}

