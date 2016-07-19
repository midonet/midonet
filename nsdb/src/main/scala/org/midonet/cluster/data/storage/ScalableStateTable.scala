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

package org.midonet.cluster.data.storage

import scala.util.control.NonFatal

import org.apache.curator.framework.state.{ConnectionState => StorageConnectionState}

import rx.Observable.OnSubscribe
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber}

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.data.storage.ScalableStateTable._
import org.midonet.cluster.data.storage.ScalableStateTableManager.ProtectedSubscriber
import org.midonet.cluster.data.storage.StateTable.{Key, Update}
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.rpc.State.KeyValue
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.util.functors.makeAction0
import org.midonet.util.logging.Logging

object ScalableStateTable {

    final val PersistentVersion = Int.MaxValue

    /**
      * A state table entry.
      *
      * @param key The entry key.
      * @param value The entry value.
      * @param version The entry version corresponding to the ephemeral sequential
      *                number.
      */
    private[storage] case class TableEntry[K, V](key: K, value: V, version: Int)

}

/**
  * A trait for a [[StateTable]] that provides dual backend support for both
  * [[Directory]] read-write operations and [[StateTableClient]] read
  * operations.
  */
trait ScalableStateTable[K, V] extends StateTable[K, V] with StateTableEncoder[K, V]
                               with Logging {

    override def logSource = "org.midonet.nsdb.state-table"
    override def logMark: String = tableKey.toString

    /**
      * Implements the [[OnSubscribe]] interface for subscriptions to updates
      * from this state table. For every new subscription, we start the state
      * table, and subscribe to the table via the underlying state proxy client
      * with fallback on the directory.
      */
    private class OnTableSubscribe extends OnSubscribe[Update[K, V]] {

        override def call(child: Subscriber[_ >: Update[K, V]]): Unit = {
            sync.synchronized {
                child.add(Subscriptions.create(makeAction0 {
                    stopInternal(0, child)
                }))
                if (!child.isUnsubscribed) {
                    startInternal(0).call(child)
                }
            }
        }
    }

    /**
      * Implements the [[OnSubscribe]] interface for subscriptions to the
      * ready state. Upon subscription, the table starts if not already started,
      * but unlike the update subscriptions, unsubscribing from the ready
      * observable will not stop the table, nor will the ready subscribers
      * prevent the table from being stopped.
      */
    private class OnReadySubscribe extends OnSubscribe[StateTable.Key] {

        override def call(child: Subscriber[_ >: StateTable.Key]): Unit = {
            sync.synchronized {
                if (!child.isUnsubscribed) {
                    startInternal(0).ready(child)
                }
            }
        }
    }

    protected[storage] def tableKey: Key

    protected[storage] def directory: Directory
    protected[storage] def connection: Observable[StorageConnectionState]
    protected[storage] def proxy: StateTableClient
    protected[storage] def metrics: StorageMetrics

    protected[storage] def nullValue: V

    @volatile private var manager: ScalableStateTableManager[K, V] = null

    private var subscriptions = 0L
    private val onTableSubscribe = new OnTableSubscribe
    private val onReadySubscribe = new OnReadySubscribe
    private val sync = new Object

    /**
      * @see [[StateTable.start()]]
      */
    @inline
    override def start(): Unit = sync.synchronized {
        startInternal(1)
    }

    /**
      * @see [[StateTable.stop()]]
      */
    @inline
    override def stop(): Unit = sync.synchronized {
        stopInternal(1, child = null)
    }

    /**
      * @see [[StateTable.add()]]
      */
    override def add(key: K, value: V): Unit = {
        val currentManager = manager
        if (currentManager ne null) {
            currentManager.add(key, value)
        } else {
            log warn s"Adding $key -> $value with table stopped"
        }
    }

    /**
      * @see [[StateTable.remove()]]
      */
    override def remove(key: K): V = {
        val currentManager = manager
        if (currentManager ne null) {
            currentManager.remove(key, nullValue)
        } else {
            log warn s"Removing $key with table stopped"
            nullValue
        }
    }

    /**
      * @see [[StateTable.remove()]]
      */
    override def remove(key: K, value: V): Boolean = {
        val currentManager = manager
        if (currentManager ne null) {
            currentManager.remove(key, value) != nullValue
        } else {
            log warn s"Removing $key -> $value with table stopped"
            false
        }
    }

    /**
      * @see [[StateTable.containsLocal()]]
      */
    override def containsLocal(key: K): Boolean = {
        val currentManager = manager
        if (currentManager ne null) {
            currentManager.containsKey(key)
        } else {
            false
        }
    }

    /**
      * @see [[StateTable.containsLocal()]]
      */
    override def containsLocal(key: K, value: V): Boolean = {
        val currentManager = manager
        if (currentManager ne null) {
            currentManager.contains(key, value)
        } else {
            false
        }
    }

    /**
      * @see [[StateTable.getLocal()]]
      */
    override def getLocal(key: K): V = {
        val currentManager = manager
        if (currentManager ne null) {
            currentManager.get(key)
        } else {
            nullValue
        }
    }

    /**
      * @see [[StateTable.getLocalByValue()]]
      */
    override def getLocalByValue(value: V): Set[K] = {
        val currentManager = manager
        if (currentManager ne null) {
            currentManager.getByValue(value)
        } else {
            Set.empty
        }
    }

    /**
      * @see [[StateTable.localSnapshot]]
      */
    override def localSnapshot: Map[K, V] = {
        val currentManager = manager
        if (currentManager ne null) {
            currentManager.snapshot
        } else {
            Map.empty
        }
    }

    /**
      * @see [[StateTable.observable]]
      */
    @inline
    override val observable: Observable[Update[K, V]] = {
        Observable.create(onTableSubscribe)
    }

    /**
      * @see [[StateTable.ready]]
      */
    override val ready: Observable[StateTable.Key] = {
        Observable.create(onReadySubscribe)
    }

    /**
      * @see [[StateTable.isReady]]
      */
    override def isReady:Boolean = {
        val currentManager = manager
        if (currentManager ne null) {
            currentManager.isReady
        } else {
            false
        }
    }

    /**
      * @return True if the table is stopped.
      */
    def isStopped: Boolean = manager eq null

    /**
      * Starts the synchronization of this state table with the backend storage
      * and state proxy servers.
      */
    private def startInternal(inc: Int): ScalableStateTableManager[K, V] = {
        subscriptions += inc
        if (manager eq null) {
            log debug "Starting state table"
            manager = new ScalableStateTableManager[K, V](this)
            manager.start()
        }
        manager
    }

    /**
      * Stops the synchronization of this state table with the backend storage
      * and state proxy servers.
      */
    private def stopInternal(dec: Int, child: Subscriber[_ >: Update[K, V]])
    : Unit = {

        def hasNoneOrOnlySubscriber(child: Subscriber[_ >: Update[K, V]])
        : Boolean = {
            val subscribers = manager.get().subscribers
            subscribers.length == 0 ||
                (subscribers.length == 1 &&
                 subscribers(0).asInstanceOf[ProtectedSubscriber[K, V]]
                     .contains(child))
        }

        if (subscriptions > 0) {
            subscriptions -= dec
        }
        if (subscriptions == 0 && (manager ne null) &&
            hasNoneOrOnlySubscriber(child)) {
            log debug "Stopping state table"
            manager.stop(null)
            manager = null
        }
    }

    /**
      * Closes immediately the current state table, regardless of the current
      * subscribers.
      */
    private[storage] def close(e: Throwable): Unit = sync.synchronized {
        if (e ne null)
            log warn s"State table closed with exception: $e"
        else
            log warn "State table closed"
        subscriptions = 0
        if (manager ne null) {
            manager.stop(e)
            manager = null
        }
    }

    /**
      * @return The encoded table entry path with the version suffix.
      */
    private[storage] def encodeEntryWithVersion(entry: TableEntry[K, V]): String = {

        encodePath(entry.key, entry.value, entry.version)
    }

    /**
      * @return The encoded table entry path without the version suffix.
      */
    private[storage] def encodeEntryPrefix(key: K, value: V): String = {
        s"/${encodeKey(key)},${encodeValue(value)},"
    }

    /**
      * Decodes the table path and returns a [[TableEntry]].
      */
    private[storage] def decodeEntry(path: String): TableEntry[K, V] = {
        val string = if (path.startsWith("/")) path.substring(1)
        else path
        val tokens = string.split(",")
        if (tokens.length != 3)
            return null
        try {
            TableEntry(decodeKey(tokens(0)), decodeValue(tokens(1)),
                       Integer.parseInt(tokens(2)))
        } catch {
            case NonFatal(_) => null
        }
    }
    /**
      * Decodes a state proxy [[Notify.Entry]] and returns a [[TableEntry]].
      */
    private[storage] def decodeEntry(entry: Notify.Entry): TableEntry[K, V] = {
        try {
            TableEntry(decodeKey(entry.getKey), decodeValue(entry.getValue),
                       entry.getVersion)
        } catch {
            case NonFatal(_) => null
        }
    }

    /**
      * This changes the access rights for a protected method.
      */
    @inline private[storage] def accessibleDecodeKey(kv: KeyValue): K = {
        decodeKey(kv)
    }

}
