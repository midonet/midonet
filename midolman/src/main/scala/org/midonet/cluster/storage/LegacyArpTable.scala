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

package org.midonet.cluster.storage

import java.util.UUID

import scala.collection.JavaConverters._

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.utils.ZKPaths

import rx.Observable

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{StateAccessException, ZkDirectory}
import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.eventloop.CallingThreadReactor

/**
 * Provides an implementation of the [[StateTable]] interface for a bridge
 * ARP table using the legacy storage.
 */
final class LegacyArpTable(bridgeId: UUID, curator: CuratorFramework,
                           paths: PathBuilder)
    extends StateTable[IPv4Addr, MAC] {

    private val reactor = new CallingThreadReactor
    private val curatorConnection = new CuratorZkConnection(curator, reactor)

    /** Starts the synchronization of the state table. */
    def start(): Unit = ???

    /** Stops the synchronization of the state table. */
    def stop(): Unit = ???

    /** Adds an opinion key MAC pair to the state table. */
    def add(address: IPv4Addr, mac: MAC): Unit = ???

    /** Adds a persistent address MAC pair to the state table. */
    @throws[StateAccessException]
    def addPersistent(address: IPv4Addr, mac: MAC): Unit = {
        val directory = createDirectory()
        if (Ip4ToMacReplicatedMap.hasPersistentEntry(directory, address, mac)) {
            return
        }
        val oldMac = Ip4ToMacReplicatedMap.getEntry(directory, address)
        if (oldMac ne null) {
            Ip4ToMacReplicatedMap.deleteEntry(directory, address, oldMac)
        }
        Ip4ToMacReplicatedMap.addPersistentEntry(directory, address, mac)
    }

    /** Removes the opinion for the specified address from the state table. */
    def remove(address: IPv4Addr): MAC = ???

    /** Removes an address MAC pair from the state table, either learned or
      * persistent. */
    @throws[StateAccessException]
    def remove(address: IPv4Addr, mac: MAC): MAC = ???

    /** Removes a persistent key value pair from the state table. */
    @throws[StateAccessException]
    def removePersistent(address: IPv4Addr, mac: MAC): MAC = {
        Ip4ToMacReplicatedMap.deleteEntry(createDirectory(), address, mac)
    }

    /** Returns whether the table contains a MAC for the specified address,
      * either learned or persistent. */
    def contains(address: IPv4Addr): Boolean = ???

    /** Returns whether the table contains the address MAC pair, either
      * learned or persistent. */
    @throws[StateAccessException]
    def contains(address: IPv4Addr, mac: MAC): Boolean = {
        val directory = createDirectory()
        Ip4ToMacReplicatedMap.hasPersistentEntry(directory, address, mac) ||
        Ip4ToMacReplicatedMap.hasLearnedEntry(directory, address, mac)
    }

    /** Returns whether the table contains the persistent address MAC pair. */
    def containsPersistent(address: IPv4Addr, mac: MAC): Boolean = ???

    /** Gets the MAC for the specified address. */
    def get(address: IPv4Addr): MAC = ???

    /** Gets the set of addresses corresponding the specified MAC. */
    def getByValue(value: MAC): Set[IPv4Addr] = ???

    /** Gets a read-only snapshot for the current state table. */
    def snapshot: Map[IPv4Addr, MAC] = {
        Ip4ToMacReplicatedMap.getAsMap(createDirectory()).asScala.toMap
    }

    /** Returns an observable that notifies the updates to the current state
      * table. */
    def observable: Observable[Update[IPv4Addr, MAC]] = ???

    private def createDirectory(): Directory = {
        val directoryPath = paths.getBridgeIP4MacMapPath(bridgeId)
        ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper, directoryPath,
                       true /* make last node */)
        new ZkDirectory(curatorConnection, directoryPath, null /*ACL*/, reactor)
    }
}
