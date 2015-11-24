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

import scala.collection.JavaConverters._

import rx.subscriptions.Subscriptions
import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{ZkConnectionAwareWatcher, StateAccessException}
import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.storage.MacIp4StateTable.OnTableSubscribe
import org.midonet.midolman.state.ReplicatedMap.Watcher
import org.midonet.midolman.state.{MacToIp4ReplicatedMap, Ip4ToMacReplicatedMap}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.functors.makeAction0

object MacIp4StateTable {

    /**
      * Implements the [[OnSubscribe]] interface for subscriptions to updates
      * from this state table. For every new subscription, we start the state
      * table, add a new watcher to the underlying replicated map, and an
      * unsubscribe hook that removes the watcher.
      */
    protected class OnTableSubscribe(table: MacIp4StateTable)
        extends OnSubscribe[Update[MAC, IPv4Addr]] {

        private val sync = new Object

        override def call(child: Subscriber[_ >: Update[MAC, IPv4Addr]]): Unit = {
            val watcher = new Watcher[MAC, IPv4Addr] {
                override def processChange(k: MAC, oldV: IPv4Addr,
                                           newV: IPv4Addr): Unit = {
                    if (!child.isUnsubscribed) {
                        child onNext Update(k, oldV, newV)
                    }
                }
            }
            sync.synchronized {
                table.start()
                table.map addWatcher watcher
            }
            child add Subscriptions.create(makeAction0 {
                sync.synchronized { table.map removeWatcher watcher }
            })
        }
    }

}

/**
  * Wraps an MAC-IPv4 [[org.midonet.midolman.state.ReplicatedMap]] to a
  * [[StateTable]], where state tables are intended as backend-agnostic
  * counterparts for replicated maps. This provides an implementation using
  * ZooKeeper as backend.
  *
  * KNOWN ISSUE: The table does not support the update of a persistent entry
  * because the underlying implementation uses the same entry version number.
  * Therefore, to modify an existing persisting entry, first delete the entry
  * and then add a new one with the same IP address.
  */
final class MacIp4StateTable(directory: Directory,
                             zkConnWatcher: ZkConnectionAwareWatcher)
    extends StateTable[MAC, IPv4Addr] {

    private val map = new MacToIp4ReplicatedMap(directory)
    private val onSubscribe = new OnTableSubscribe(this)
    if (zkConnWatcher ne null)
        map.setConnectionWatcher(zkConnWatcher)

    /**
      * Starts the synchronization of the state table.
      */
    @inline
    override def start(): Unit = {
        map.start()
    }

    /**
      * Stops the synchronization of the state table.
      */
    @inline
    override def stop(): Unit = {
        map.stop()
    }

    /**
      * Adds an opinion key MAC pair to the state table. The method is
      * asynchronous.
      */
    @throws[StateAccessException]
    @inline
    override def add(k: MAC, v: IPv4Addr): Unit = {
        map.put(k, v)
    }

    /**
      * Adds a persistent address MAC pair to the state table. The method
      * is synchronous.
      */
    @throws[StateAccessException]
    @inline
    override def addPersistent(k: MAC, v: IPv4Addr): Unit = {
        map.putPersistent(k, v)
    }

    /**
      * Removes the opinion for the specified address from the state table.
      * The method is asynchronous.
      */
    @throws[StateAccessException]
    @inline
    override def remove(k: MAC): IPv4Addr = {
        map.removeIfOwner(k)
    }

    /**
      * Removes an address MAC pair from the state table, either learned or
      * persistent. The method is asynchronous.
      */
    @throws[StateAccessException]
    @inline
    override def remove(k: MAC, v: IPv4Addr): IPv4Addr = {
        map.removeIfOwnerAndValue(k, v)
    }

    /**
      * Removes a persistent key value pair from the state table. The method
      * is synchronous.
      */
    @throws[StateAccessException]
    def removePersistent(k: MAC, v: IPv4Addr): IPv4Addr = {
        MacToIp4ReplicatedMap.deleteEntry(directory, k, v)
    }

    /**
      * Returns whether the table contains a MAC for the specified address,
      * either learned or persistent. The method reads from the underlying
      * map cache and it requires the table to be started.
      */
    @throws[StateAccessException]
    @inline
    override def containsLocal(k: MAC): Boolean = {
        map.containsKey(k)
    }

    /**
      * Returns whether the table contains the address MAC pair, either
      * learned or persistent. The method reads from the underlying
      * map cache and it requires the table to be started.
      */
    @throws[StateAccessException]
    @inline
    override def containsLocal(k: MAC, v: IPv4Addr): Boolean = {
        map.containsKey(k)
    }

    /**
      * Returns whether the remote table contains a MAC for the specified
      * address either learned or persistent.
      */
    @throws[StateAccessException]
    @inline
    override def containsRemote(address: MAC): Boolean = {
        // Not implemented
        ???
    }

    /**
      * Returns whether the remote table contains the address MAC pair, either
      * learned or persistent. The method reads synchronously from ZooKeeper.
      */
    @throws[StateAccessException]
    @inline
    override def containsRemote(k: MAC, v: IPv4Addr): Boolean = {
        MacToIp4ReplicatedMap.hasPersistentEntry(directory, k, v) ||
        MacToIp4ReplicatedMap.hasLearnedEntry(directory, k, v)
    }

    /**
      * Returns whether the table contains the persistent address MAC pair. The
      * method reads synchronously from ZooKeeper.
      */
    @throws[StateAccessException]
    @inline
    override def containsPersistent(k: MAC, v: IPv4Addr): Boolean = {
        MacToIp4ReplicatedMap.hasPersistentEntry(directory, k, v)
    }

    /**
      * Gets the MAC for the specified address. The method reads from the
      * underlying map cache and it requires the table to be started.
      */
    @inline
    override def getLocal(address: MAC): IPv4Addr = {
        map.get(address)
    }

    /**
      * Gets the MAC for the specified address.
      */
    @inline
    override def getRemote(address: MAC): IPv4Addr = {
        // Not implemented
        ???
    }

    /**
      * Gets the set of addresses corresponding the specified MAC. The method
      * reads from the underlying map cache and it requires the table to be
      * started.
      */
    @inline
    override def getLocalByValue(value: IPv4Addr): Set[MAC] = {
        map.getByValue(value).asScala.toSet
    }
    /**
      * Gets the set of addresses corresponding the specified MAC.
      */
    @inline
    override def getRemoteByValue(value: IPv4Addr): Set[MAC] = {
        // Not implemented
        ???
    }

    /**
      * Gets a read-only snapshot for the current state table. The method reads
      * from the underlying map cache and it requires the map to be started.
      */
    @inline
    override def localSnapshot: Map[MAC, IPv4Addr] = {
        map.getMap.asScala.toMap
    }

    /**
      * Gets a read-only snapshot for the current state table. The method reads
      * synchronously from ZooKeeper.
      */
    @inline
    override def remoteSnapshot: Map[MAC, IPv4Addr] = {
        MacToIp4ReplicatedMap.getAsMap(directory).asScala.toMap
    }

    /**
      * Returns an observable that notifies the updates from the current state
      * table. Upon subscription to the observable, the table will start if
      * not started.
      */
    @inline
    override val observable: Observable[Update[MAC, IPv4Addr]] = {
        Observable.create(onSubscribe)
    }

}
