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

package org.midonet.cluster.services.state

import java.util
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.Code
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.{WatchedEvent, Watcher}
import org.slf4j.LoggerFactory

import rx.Subscriber
import rx.observers.SafeSubscriber
import rx.subscriptions.Subscriptions

import org.midonet.cluster.rpc.State.KeyValue
import org.midonet.cluster.stateProxyCacheLog
import org.midonet.cluster.rpc.State.Message.Notify
import org.midonet.cluster.services.state.StateTableCache.{Log, NoEntries, State, Subscription}
import org.midonet.util.functors.{makeAction0, makeRunnable}

object StateTableCache {

    val Log = Logger(LoggerFactory.getLogger(stateProxyCacheLog))

    /**
      * An internal implementation for a [[StateTableSubscription]], which
      * wraps a single Rx subscriber that will receive notifications from the
      * state table cache.
      */
    private class Subscription(override val id: Long,
                               cache: StateTableCache,
                               subscriber: Subscriber[_ >: Notify])
        extends SafeSubscriber[Notify](subscriber) with StateTableSubscription {

        // Register the unsubscribe action for the current subscriber.
        subscriber.add(Subscriptions.create(makeAction0 {
            cache.removeSubscription(this)
        }))

        /**
          * @see [[StateTableSubscription.refresh()]]
          */
        override def refresh(lastVersion: Option[Long]): Unit = {
            cache.requestRefresh(this, lastVersion)
        }

        override def onError(e: Throwable): Unit = {
            try {
                super.onError(e)
            } catch {
                case NonFatal(e2) =>
                    // Unlike Rx, we catch all non-fatal exceptions from a
                    // subscriber to prevent the cache being take down by a
                    // badly implemented subscriber.
                    //
                    // See: https://github.com/ReactiveX/RxJava/issues/198
                    //      and Rx Design Guidelines 5.2
                    Log.warn(s"(${cache.logId}) Unhandled exception during " +
                             s"error for subscription $id: ${e.getMessage}", e2)

            }
        }

        override def onCompleted(): Unit = {
            try {
                super.onCompleted()
            } catch {
                case NonFatal(e) =>
                    // Same here.
                    Log.warn(s"(${cache.logId}) Unhandled exception during " +
                             s"completion for subscription $id", e)
            }
        }

        /**
          * Emits to the underlying subscriber a snapshot of the current table
          * entries. This method is always called on the cache executor thread.
          */
        def snapshot(entries: TableEntries): Unit = {

        }

        /**
          * Emits to the underlying subscriber a differential update for the
          * table entries with respect to the last available snapshot in the
          * cache. If the last
          */
        def diff(entries: TableEntries, lastVersion: Long): Unit = {

        }

    }

    private val NoSubscriptions = new Array[Subscription](0)

    /**
      * State machine representing the current subscriptions to the state table
      * cache. This allows the changes to the subscription list to be
      * implemented as atomic operations, and therefore the retrieval of the
      * current subscriptions is takes O(1).
      */
    private class State(final val closed: Boolean,
                        final val subscriptions: Array[Subscription]) {

        /**
          * Adds a new subscription to the current cache state and returns the
          * new state with the subscription added.
          */
        def add(subscription: Subscription): State = {
            val count = subscriptions.length
            val subs = new Array[Subscription](count + 1)
            System.arraycopy(subscriptions, 0, subs, 0, count)
            subs(count) = subscription
            new State(closed, subs)
        }

        /**
          * Removes a subscription from the current cache state and returns the
          * new state with the subscription removed. If this is the last
          * subscription then, the state changes to [[State.Closed]] such that
          * the resources behind the state table cache can be released.
          */
        def remove(subscription: Subscription): State = {
            val count = subscriptions.length
            if (count == 1 && subscriptions(0) == subscription) {
                State.Closed
            } else if (count == 0) {
                this
            } else {
                var subs = new Array[Subscription](count - 1)
                var indexIn = 0
                var indexOut = 0
                while (indexIn < count) {
                    val sub = subscriptions(indexIn)
                    if (sub != subscription) {
                        if (indexOut == count - 1) {
                            // Subscription not found.
                            return this
                        }
                        subs(indexOut) = sub
                        indexIn += 1
                    }
                    indexOut += 1
                }
                if (indexOut == 0) {
                    State.Closed
                } else {
                    if(indexOut < count - 1) {
                        // Subscription found more than once.
                        val temp = new Array[Subscription](indexOut)
                        System.arraycopy(subs, 0, temp, 0, indexOut)
                        subs = temp
                    }
                    new State(closed, subs)
                }
            }
        }

    }

    private object State {
        val Stopped = new State(closed = false, NoSubscriptions)
        val Closed = new State(closed = true, NoSubscriptions)
    }

    private class TableEntry(val key: KeyValue)

    private class TableEntries(val version: Long,
                               val entries: Array[TableEntries])

    private val NoEntries = new TableEntries(-1, new Array[TableEntries](0))

}

/**
  * A local cache for a state table backed by ZooKeeper storage. The cache
  * manages one or more subscriptions to updates from the table, and
  * automatically synchronizes with the storage whenever there is more than
  * one subscriber.
  *
  * The cache provides lock-free management of subscriptions, where a snapshot
  * of the current subscribers is available as an O(1) operation. To prevent
  * race conditions, when the last subscriber unsubscribes, the cache stops its
  * synchronization with the backend storage and transitions to a terminal
  * [[State.Closed]] state after which the cache will no longer accept new
  * subscriptions, nor will restart synchronizing with the storage. At this
  * point any references held to this cache can be safely released. If a new
  * subscription to the same state table is needed, one needs to create a new
  * state table cache.
  *
  * The cache uses [[CuratorFramework]] and automatically manages the storage
  * connection. When the storage session recovers after a connection loss, the
  * cache continues the notification of updates to all subscribers. When the
  * session is lost, all subscribers are notified with an error, and the cache
  * transitions in the [[State.Closed]] state.
  *
  * A caller can use the `onClose` function to receive a callback for when
  * the cache closes.
  *
  * All changes to the cache are handled on a single-threaded executor, which
  * ensures a total order of updates as seen by the subscribers. The executor
  * also offloads the processing of the table changes from the
  * [[CuratorFramework]] thread, which is available to process other requests.
  */
class StateTableCache(curator: CuratorFramework,
                      objectClass: Class[_], objectId: Any,
                      tableKey: Class[_], tableValue: Class[_],
                      name: String, args: Seq[Any],
                      executor: ExecutorService, onClose: => Unit) {

    private val state = new AtomicReference[State](State.Stopped)
    private val counter = new AtomicLong()
    private val connected = new AtomicBoolean(true)

    @volatile private var entries = NoEntries

    private val path = ""
    protected[state] val logId =
        s"$name @ ${objectClass.getSimpleName}/$objectId"

    private val keyDecoder = StateEntryDecoder.get(tableKey)
    private val valueDecoder = StateEntryDecoder.get(tableValue)

    @volatile
    private var callback = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit =
            processCallback(event)
    }

    @volatile
    private var watcher = new Watcher {
        override def process(event: WatchedEvent): Unit =
            processWatcher(event)
    }

    @volatile
    private var connectionListener = new ConnectionStateListener {
        override def stateChanged(client: CuratorFramework,
                                  state: ConnectionState): Unit =
            processStateChanged(state)
    }

    /**
      * Subscribes to this state table cache for notifications. Upon
      * subscription, the observer will receive one or more notifications with
      * the current contents of the table as follows:
      * - If `lastVersion` is specified and versioning is supported, then
      *   the observer will receive the differential updates since the specified
      *   version.
      * - Otherwise, the observer will receive a snapshot with all the entries
      */
    @throws[StateTableCacheClosedException]
    def subscribe(subscriber: Subscriber[_ >: Notify],
                  lastVersion: Option[Long]): StateTableSubscription = {
        // If the state table is closed throw an exception.
        if (state.get.closed) {
            throw new StateTableCacheClosedException
        }
        val subscriptionId = counter.incrementAndGet()
        val subscription = new Subscription(subscriptionId, this, subscriber)

        addSubscription(subscription)

        subscription
    }

    private def subscriptions: Array[Subscription] = {
        state.get.subscriptions
    }

    /**
      * Adds the specified subscription to this state table cache. The new
      * subscription is added by atomically changing the cache state to include
      * the new subscription. If the state table cache closes due to a
      * concurrent modification
      */
    @throws[StateTableCacheClosedException]
    private def addSubscription(subscription: Subscription): Boolean = {
        do {
            val oldState = state.get()
            if (oldState.closed) {
                throw new StateTableCacheClosedException
            }
            val newState = oldState.add(subscription)
            if (state.compareAndSet(oldState, newState)) {
                start(oldState, newState)
                return true
            }
        } while (true)
    }

    /**
      * Removes the specified subscription from this state table cache. If
      * this is the last subscription to this table, it also closes the cache
      * in order to stop watching the underlying ZooKeeper directory, and
      * release any allocated resources.
      */
    private def removeSubscription(subscription: Subscription): Unit = {
        do {
            val oldState = state.get()
            if (oldState.closed) {
                return
            }
            val newState = oldState.remove(subscription)
            if (newState == oldState || state.compareAndSet(oldState, newState)) {
                stop(newState)
                return
            }
        } while (true)
    }

    /**
      * Starts the synchronization of the state table cache with the ZooKeeper
      * storage. This is only allowed when the state changes from
      * [[State.Stopped]] to a subscribed state.
      */
    private def start(oldState: State, newState: State): Unit = {
        if (oldState != State.Stopped || newState == State.Closed) {
            return
        }

        curator.getConnectionStateListenable.addListener(connectionListener)
        refresh()
    }

    /**
      * Stops the synchronization of the state table cache with the ZooKeeper
      * storage. This is only allowed when the state changes from a
      * subscribed state to [[State.Closed]].
      */
    private def stop(newState: State): Unit = {
        if (newState.closed) {
            curator.getConnectionStateListenable.removeListener(connectionListener)
            curator.clearWatcherReferences(watcher)

            onClose

            connectionListener = null
            watcher = null
            callback = null
        }
    }

    /**
      * Closes the state table cache due to an unhandled fatal exception. If
      * the cache is in any subscribed state, then the method emits the error
      * to all subscribers, terminating all subscriptions. The method
      * transitions the cache state to [[State.Closed]].
      */
    private def close(e: Throwable): Unit = {
        if (!state.get.closed) {
            val subscriptions = state.getAndSet(State.Closed).subscriptions
            var index = 0
            while (index < subscriptions.length) {
                subscriptions(index) onError e
                index += 1
            }
        }
    }

    /**
      * Refreshes the state table cache.
      */
    private def refresh(): Unit = {
        if (!connected.get || state.get.closed) {
            return
        }
        try {
            val context = Long.box(System.currentTimeMillis())
            curator.getChildren
                   .usingWatcher(watcher)
                   .inBackground(callback, context, executor)
                   .forPath(path)
        } catch {
            case NonFatal(e) =>
                Log.debug(s"($logId) Refreshing state table cache failed", e)
                close(e)
        }
    }

    /**
      * Processes the changes for the state table.
      */
    private def processCallback(event: CuratorEvent): Unit = {
        if (state.get.closed) {
            return
        }

        if (event.getResultCode == Code.OK.intValue()) {
            Log trace s"($logId) Read ${event.getChildren.size()} entries in " +
                      s"${latency(event.getContext)} ms"
            processEntries(event.getChildren, event.getStat)
        } else if (event.getResultCode == Code.NONODE.intValue()) {
            Log debug s"($logId) State table does not exist or deleted"
            close(new StateTableCacheException(event.getResultCode))
        } else if (event.getResultCode == Code.CONNECTIONLOSS.intValue()) {
            Log warn s"($logId) Cache connection lost"
            close(new StateTableCacheException(event.getResultCode))
        } else {
            Log warn s"($logId) Refreshing cache failed ${event.getResultCode}"
            close(new StateTableCacheException(event.getResultCode))
        }
    }

    private def processWatcher(event: WatchedEvent): Unit = {
        if (!state.get.closed) {
            Log trace s"($logId) Cache data changed: refreshing"
            refresh()
        }
    }

    private def processStateChanged(state: ConnectionState): Unit = state match {
        case ConnectionState.CONNECTED =>
            Log debug s"($logId) Cache connected"
            connected set true
        case ConnectionState.SUSPENDED =>
            Log debug s"($logId) Cache connection suspended"
            connected set false
        case ConnectionState.RECONNECTED =>
            Log debug s"($logId) Cache reconnected"
            if (connected.compareAndSet(false, true)) {
                refresh()
            }
        case ConnectionState.LOST =>
            Log warn s"($logId) Cache connection lost"
            connected set false
            close(new StateTableCacheException(
                Code.CONNECTIONLOSS.intValue()))
        case ConnectionState.READ_ONLY =>
            Log warn s"($logId) Cache is read-only"
            if (connected.compareAndSet(false, true)) {
                refresh()
            }
    }

    private def processEntries(entries: util.List[String], stat: Stat): Unit = {
        val iterator = entries.iterator()
        while (iterator.hasNext) {
            val entry = parseEntry(iterator.next())
        }
    }

    private def latency(context: AnyRef): Long = {
        context match {
            case startTime: java.lang.Long =>
                System.currentTimeMillis() - startTime
            case _ => -1L
        }
    }

    private def requestRefresh(subscription: Subscription,
                               lastVersion: Option[Long]): Unit = {
        executor.submit(makeRunnable {
            // TODO: Support differential updates
            subscription
        })
    }

}
