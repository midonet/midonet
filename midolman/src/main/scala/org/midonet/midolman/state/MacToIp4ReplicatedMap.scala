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
import org.midonet.cluster.backend.zookeeper.StateAccessException
import org.midonet.packets.{IPv4Addr, MAC}

class MacToIp4ReplicatedMap(dir: Directory)
    extends ReplicatedMap[MAC, IPv4Addr](dir) {
    def putPersistent(key: MAC, value: IPv4Addr): Unit = {
        MacToIp4ReplicatedMap.addPersistentEntry(dir, key, value)
    }

    protected def encodeKey(key: MAC): String = key.toString
    protected def decodeKey(str: String): MAC = MAC.fromString(str)
    protected def encodeValue(value: IPv4Addr): String = value.toString
    protected def decodeValue(str: String): IPv4Addr = IPv4Addr.fromString(str)
}

object MacToIp4ReplicatedMap {

    def getAsMap(dir: Directory): util.Map[MAC, IPv4Addr] =
        getAsMapBase(dir, (mac: String, ip: String, version: String) =>
            (MAC.fromString(mac), IPv4Addr.fromString(ip)))

    def getAsMapWithVersion(dir: Directory): util.Map[MAC, (IPv4Addr, Int)] =
        getAsMapBase(dir, (mac: String, ip: String, version: String) =>
            (MAC.fromString(mac), (IPv4Addr.fromString(ip), version.toInt)))

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
     * Check if a given mac-ip pair was explicitly set.
     */
    def hasPersistentEntry(dir: Directory, key: MAC, value: IPv4Addr): Boolean =
        ZKExceptions.adapt(
            getAsMapWithVersion(dir).get(key) match {
                case (m, Int.MaxValue) if m.equals(value) => true
                case _ => false
            }
        )

    /**
     * Check if a given mac-ip pair was learned.
     */
    def hasLearnedEntry(dir: Directory, key: MAC, value: IPv4Addr): Boolean =
        ZKExceptions.adapt(
            getAsMapWithVersion(dir).get(key) match {
                case (m, 0) if m.equals(value) => true
                case _ => false
            }
        )

    /**
     * Explicitly adds an entry with the ip value associated to a mac address.
     *
     * Warning: the caller must guarantee that no previous mapping exists for
     * that mac; overwriting an existing key results in undefined behaviour
     * (... which is more probably a bug than a feature...)
     *
     * @throws StateAccessException if a pair with the same ip and mac already
     *                              exists.
     */
    def addPersistentEntry(dir: Directory, key: MAC, value: IPv4Addr)
    = ZKExceptions.adapt(dir.add(
        encodePersistentPath(key, value), null, CreateMode.PERSISTENT))

    /**
     * Adds an entry with an ip for a given mac.
     *
     * Warning: the caller must guarantee that no previous mapping exists for
     * that mac: overwriting an existing key results in undefined behaviour.
     * Regular (non-learned) entries will not be replaced.
     *
     * @throws StateAccessException if a pair with the same ip and mac already
     *                              exists.
     */
    def addLearnedEntry(dir: Directory, key: MAC, value: IPv4Addr) = {
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
    def deleteEntry(dir: Directory, key: MAC, v: IPv4Addr): IPv4Addr = {
        getAsMapWithVersion(dir).get(key) match {
            case (m, ver) if m.equals(v) =>
                dir.delete(encodePath(key, v, ver))
                v
            case _ => null
        }
    }

    /**
     * Deletes a learned entry. Non learned entries will be ignored.
     */
    def deleteLearnedEntry(dir: Directory, key: MAC, v: IPv4Addr) = {
        getAsMapWithVersion(dir).get(key) match {
            case (m, 0) if m.equals(v) => dir.delete(encodePath(key, v, 0))
            case _ => () // learned entries always carry a 0
        }
    }

    /**
     * Retrieves an entry (either learned or explicitly set).
     */
    def getEntry(dir: Directory, key: MAC): IPv4Addr = {
        getAsMapWithVersion(dir).get(key) match {
            case (m, ver) => m
            case _ => null
        }
    }

    def encodePersistentPath(k: MAC, v: IPv4Addr) =
        encodePath(k, v, Int.MaxValue)

    /*
     * We use version 0 for learned entries. This is done so to remain
     * compatible with a very likely buggy version of the ReplicatedMap that
     * uses always 1 for persistent entries, but never increments. The learned
     * entries should always be overwritten by the non-learned entries (set
     * from the API).
     */
    def encodeLearnedPath(k: MAC, v: IPv4Addr) = encodePath(k, v, 0)

    def encodePath(k: MAC, v: IPv4Addr, ver: Int) =
        ReplicatedMap.encodeFullPath(k.toString, v.toString, ver)

}

