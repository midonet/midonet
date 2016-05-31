/*
 * Copyright 2016 Midokura SARL
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

import rx.Observable.OnSubscribe
import rx.{Observable, Subscriber}
import rx.subscriptions.Subscriptions

import org.midonet.cluster.backend.zookeeper.StateAccessException
import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.midolman.state.ReplicatedMap
import org.midonet.midolman.state.ReplicatedMap.Watcher
import org.midonet.util.functors.makeAction0

/**
  * A trait with common methods for a [[StateTable]] based on a
  * [[ReplicatedMap]].
  */
trait ReplicatedMapStateTable[K, V] extends StateTable[K, V] {

    /**
      * Implements the [[OnSubscribe]] interface for subscriptions to updates
      * from this state table. For every new subscription, we start the state
      * table, add a new watcher to the underlying replicated map, and an
      * unsubscribe hook that removes the watcher.
      */
    protected class OnTableSubscribe
        extends OnSubscribe[Update[K, V]] {

        override def call(child: Subscriber[_ >: Update[K, V]]): Unit = {
            val watcher = new Watcher[K, V] {
                override def processChange(key: K, oldValue: V,
                                           newValue: V): Unit = {
                    if (!child.isUnsubscribed) {
                        child onNext Update(key, oldValue, newValue)
                    }
                }
            }
            sync.synchronized {
                map addWatcher watcher
                start()
            }
            child add Subscriptions.create(makeAction0 {
                sync.synchronized {
                    stop()
                    map removeWatcher watcher
                }
            })
        }
    }

    protected def map: ReplicatedMap[K, V]
    protected def nullValue: V

    private val sync = new Object
    private val onSubscribe = new OnTableSubscribe
    private var subscriptions = 0L

    /**
      * @see [[StateTable.start()]]
      */
    @inline
    override def start(): Unit = sync.synchronized {
        if (subscriptions == 0L) {
            map.start()
        }
        subscriptions += 1
    }

    /**
      * @see [[StateTable.stop()]]
      */
    @inline
    override def stop(): Unit = sync.synchronized {
        if (subscriptions > 0) {
            subscriptions -= 1
        }
        if (subscriptions == 0) {
            map.stop()
        }
    }

    /**
      * @see [[StateTable.add()]]
      */
    @throws[StateAccessException]
    @inline
    override def add(key: K, value: V): Unit = {
        map.put(key, value)
    }

    /**
      * @see [[StateTable.remove()]]
      */
    @throws[StateAccessException]
    @inline
    override def remove(key: K): V = {
        map.removeIfOwner(key)
    }

    /**
      * @see [[StateTable.remove()]]
      */
    @throws[StateAccessException]
    @inline
    override def remove(key: K, value: V): Boolean = {
        map.removeIfOwnerAndValue(key, value) != nullValue
    }

    /**
      * @see [[StateTable.containsLocal()]]
      */
    @throws[StateAccessException]
    @inline
    override def containsLocal(key: K): Boolean = {
        map.containsKey(key)
    }

    /**
      * @see [[StateTable.containsLocal()]]
      */
    @throws[StateAccessException]
    @inline
    override def containsLocal(key: K, value: V): Boolean = {
        map.get(key) == value
    }

    /**
      * @see [[StateTable.getLocal()]]
      */
    @inline
    override def getLocal(key: K): V = {
        map.get(key)
    }

    /**
      * @see [[StateTable.getLocalByValue()]]
      */
    @inline
    override def getLocalByValue(value: V): Set[K] = {
        map.getByValue(value).asScala.toSet
    }

    /**
      * @see [[StateTable.localSnapshot]]
      */
    @inline
    override def localSnapshot: Map[K, V] = {
        map.getMap.asScala.toMap
    }

    /**
      * @see [[StateTable.observable]]
      */
    @inline
    override val observable: Observable[Update[K, V]] = {
        Observable.create(onSubscribe)
    }

}
