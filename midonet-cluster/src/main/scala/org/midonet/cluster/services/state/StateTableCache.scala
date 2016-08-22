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

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.Code
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.{KeeperException, WatchedEvent, Watcher}
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.StateTableStorage
import org.midonet.cluster.rpc.State.KeyValue
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.rpc.State.ProxyResponse.Notify.Update
import org.midonet.cluster.services.state.StateTableCache._
import org.midonet.cluster.{StateProxyConfig, StateProxyCacheLog}
import org.midonet.util.functors.makeRunnable

object StateTableCache {

    final val Log = Logger(LoggerFactory.getLogger(StateProxyCacheLog))

    /**
      * An internal implementation for a [[StateTableSubscription]], which
      * wraps a single Rx subscriber that will receive notifications from the
      * state table cache.
      */
    private class Subscription(override val id: Long,
                               cache: StateTableCache,
                               observer: StateTableObserver)
        extends StateTableSubscription {

        private val unsubscribed = new AtomicBoolean()
        // The last version or -1L if the initial snapshot was not sent: this
        // indicates that the initial snapshot is still pending, and
        // differential updates must be ignored.
        @volatile private var version = -1L
        // The queue is volatile such that it can be nulled during unsubscribe.
        @volatile private var queue =
            new util.ArrayDeque[Notify](cache.initialSubscriberQueueSize)
        private var sending = false

        /**
          * @see [[StateTableSubscription.unsubscribe()]]
          */
        override def unsubscribe(): Unit = {
            if (unsubscribed.compareAndSet(false, true)) {
                queue = null
                cache.removeSubscription(this)
            }
        }

        /**
          * @see [[StateTableSubscription.isUnsubscribed()]]
          */
        override def isUnsubscribed: Boolean = unsubscribed.get

        /**
          * @see [[StateTableSubscription.refresh()]]
          */
        override def refresh(lastVersion: Option[Long]): Unit = {
            if (!unsubscribed.get) {
                cache.requestRefresh(this, lastVersion)
            }
        }

        /**
          * Indicates that en error has occurred while processing changes from
          * this state table. This will send a completion [[Notify]] message
          * to the observer and terminate the subscription.
          */
        def error(e: Throwable): Unit = {
            if (unsubscribed.compareAndSet(false, true)) {
                // Send the message immediately, bypassing the notification
                // queue, which becomes invalidated when the subscription
                // becomes unsubscribed.
                queue = null
                send(Notify.newBuilder()
                           .setCompleted(buildError(e))
                           .setSubscriptionId(id)
                           .build())
            }
        }

        /**
          * Emits to the underlying subscriber a snapshot of the current table
          * entries. This method is always called on the cache dispatcher thread.
          */
        def snapshot(updates: Array[Update], currentVersion: Long): Unit = {
            val currentQueue = queue
            if (currentQueue ne null) {
                currentQueue.clear()
                version = currentVersion
                enqueue(updates)
            }
        }

        /**
          * Emits to the underlying subscriber a differential update for the
          * table entries with respect to the last available snapshot in the
          * cache. This method is always called on the cache dispatcher thread.
          */
        def diff(updates: Array[Update], lastVersion: Long,
                 currentVersion: Long): Unit = {
            if (version == -1L) {
                return
            }
            val currentQueue = queue
            if (currentQueue ne null) {
                if (version == lastVersion) {
                    version = currentVersion
                    if (updates.length > 0) {
                        enqueue(updates)
                    }
                } else {
                    refresh(None)
                }
            }
        }

        /**
          * Enqueues the array of updates on the notification queue with the
          * current subscription number.
          */
        private def enqueue(updates: Array[Update]): Unit = {
            var index = 0
            while (index < updates.length) {
                enqueue(Notify.newBuilder()
                            .setSubscriptionId(id)
                            .setUpdate(updates(index))
                            .build())
                index += 1
            }
        }

        /**
          * Enqueues a message to send to the observer. If the queue is empty
          * the message is sent immediately. This method is always called on
          * the cache dispatcher thread and therefore the queue and sending
          * flag need not be synchronized.
          */
        private def enqueue(notify: Notify): Unit = {
            val currentQueue = queue
            if (currentQueue ne null) {
                if (sending) {
                    currentQueue.offer(notify)
                } else {
                    sending = true
                    send(notify)
                }
            }
        }

        /**
          * Sends a notification message to the observer immediately.
          */
        private def send(notify: Notify): Unit = {
            if (notify eq null) {
                // We only get here for messages polled from the queue, which
                // is done on the dispatcher thread.
                sending = false
                return
            }
            try {
                observer.next(notify).onComplete { result =>
                    // Accessing the observer queue on the cache dispatcher
                    // thread.
                    val currentQueue = queue
                    if (currentQueue ne null) {
                        send(currentQueue.poll())
                    }
                }(cache.dispatcher)
            } catch {
                case NonFatal(e) =>
                    Log.warn(s"(${cache.logId}) Unhandled exception during " +
                             s"notification for subscription $id", e)
                    // Sending the next message on the cache dispatcher thread.
                    cache.dispatcher.execute(makeRunnable {
                        val currentQueue = queue
                        if (currentQueue ne null) {
                            send(currentQueue.poll())
                        }
                    })
            }
        }

        /**
          * Builds a [[Notify.Completed]] message for the specified exception.
          */
        private def buildError(t: Throwable): Notify.Completed = {
            val builder = Notify.Completed.newBuilder()
            t match {
                case e: KeeperException =>
                    builder.setCode(Notify.Completed.Code.NSDB_ERROR)
                    builder.setNsdbCode(e.code().intValue())
                    builder.setDescription(e.getMessage)
                case NonFatal(e) =>
                    builder.setCode(Notify.Completed.Code.UNHANDLED_ERROR)
                    if (e.getMessage ne null) {
                        builder.setDescription(e.getMessage)
                    }
            }
            builder.build()
        }

    }

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
                        indexOut += 1
                    }
                    indexIn += 1
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

    private class TableEntry(val cacheKey: String,
                             val key: KeyValue,
                             val value: KeyValue,
                             val version: Int)

    private type TableEntries = util.HashMap[String, TableEntry]

    private final val NoSubscriptions = new Array[Subscription](0)
    private final val NoUpdates = new Array[Update](0)
    private final val EmptyPendingMap = Map.empty[Subscription, Runnable]
    private final val PersistentVersion = Int.MaxValue

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
class StateTableCache(val config: StateProxyConfig,
                      storage: StateTableStorage,
                      curator: CuratorFramework,
                      subscriptionCounter: AtomicLong,
                      objectClass: Class[_], objectId: Any,
                      tableKey: Class[_], tableValue: Class[_],
                      name: String, args: Seq[Any],
                      executor: ExecutorService,
                      onClose: (StateTableCache) => Unit) {

    // Atomic variable with the current state and subscription list.
    private val state = new AtomicReference[State](State.Stopped)
    // Indicates whether the cache is connected to the backend.
    private val connected = new AtomicBoolean(true)
    // The dispatcher thread: all notifications are sent on this thread.
    private[state] val dispatcher = ExecutionContext.fromExecutor(executor)

    private[state] val initialSubscriberQueueSize =
        config.initialSubscriberQueueSize
    private[state] val notifyBatchSize =
        config.notifyBatchSize

    // The local cache map.
    @volatile private var cache = new TableEntries
    // The current table version.
    private var version = -1L
    // Stores the subscriptions received before the cache is synchronized
    // with the backend. We use scala immutable sets, but the overhead is
    // acceptable since this is expected only for few subscriptions.
    private val pending =
        new AtomicReference[Map[Subscription, Runnable]](EmptyPendingMap)
    // Queue of size one to store the last event notified from backend for this
    // state table cache, allowing the cache to handle notifications arriving
    // faster than the cache can process.
    private val eventQueue = new AtomicReference[CuratorEvent](null)

    private val diffAddCache = new util.ArrayList[TableEntry](8)
    private val diffRemoveCache = new util.ArrayList[TableEntry](8)

    private val path = storage.tablePath(objectClass, objectId, name, args: _*)
    protected[state] val logId =
        s"${objectClass.getSimpleName}/$objectId/$name"

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
    def subscribe(observer: StateTableObserver,
                  lastVersion: Option[Long]): StateTableSubscription = {
        // If the state table is closed throw an exception.
        if (state.get.closed) {
            throw new StateTableCacheClosedException(logId)
        }
        val subscriptionId = subscriptionCounter.incrementAndGet()
        val subscription = new Subscription(subscriptionId, this, observer)

        addSubscription(subscription)

        subscription.refresh(lastVersion)

        subscription
    }

    /**
      * Gracefully closes the current state table cache and notifies all
      * subscribers.
      */
    def close(): Unit = {
        close(new StateTableCacheClosedException(logId))
    }

    /**
      * @return True if the cache is stopped.
      */
    def isStopped: Boolean = state.get == State.Stopped

    /**
      * @return True if the cache is closed.
      */
    def isClosed: Boolean = state.get.closed

    /**
      * @return True if the cache has subscribers.
      */
    def hasSubscribers: Boolean = state.get.subscriptions.length > 0

    /**
      * @return The current list of subscriptions.
      */
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
                throw new StateTableCacheClosedException(logId)
            }
            val newState = oldState.add(subscription)
            if (state.compareAndSet(oldState, newState)) {
                start(oldState, newState)
                return true
            }
        } while (true)
        false
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

            onClose(this)

            connectionListener = null
            watcher = null
            callback = null
            cache = null
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
                subscriptions(index) error e
                index += 1
            }

            stop(State.Closed)
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
                   .inBackground(callback, context)
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
            enqueueEvent(event)
        } else if (event.getResultCode == Code.NONODE.intValue()) {
            Log debug s"($logId) State table does not exist or deleted"
            close(KeeperException.create(Code.NONODE, event.getPath))
        } else if (event.getResultCode == Code.CONNECTIONLOSS.intValue()) {
            Log warn s"($logId) Cache connection lost"
            close(KeeperException.create(Code.CONNECTIONLOSS, event.getPath))
        } else {
            Log warn s"($logId) Refreshing cache failed ${event.getResultCode}"
            close(KeeperException.create(Code.get(event.getResultCode),
                                         event.getPath))
        }
    }

    /**
      * Processes watcher notifications for the state table by triggering a
      * refresh.
      */
    private def processWatcher(event: WatchedEvent): Unit = {
        if (!state.get.closed) {
            Log trace s"($logId) Cache data changed: refreshing"
            refresh()
        }
    }

    /**
      * Processes changes to the underlying storage connection, by updating the
      * connecting flag, and triggering a refresh of the state table whenever
      * the connection is resumed. If the connection is lost, the method closes
      * the cache notifying all subscribers and releasing the resources.
      */
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
            close(KeeperException.create(Code.CONNECTIONLOSS))
        case ConnectionState.READ_ONLY =>
            Log warn s"($logId) Cache is read-only"
            if (connected.compareAndSet(false, true)) {
                refresh()
            }
    }

    /**
      * Enqueues a backend entries event to the event queue, and if this is the
      * first event in the queue schedules it for execution. The current event
      * always overwrites any previous event in the queue, such that the state
      * table cache can keep up with processing the updates.
      */
    private def enqueueEvent(event: CuratorEvent): Unit = {
        val current = eventQueue.getAndSet(event)
        if (current eq null) {
            executor.submit(makeRunnable {
                val last = eventQueue.getAndSet(null)
                if (last ne null) {
                    processEntries(last.getChildren, last.getStat)
                }
            })
        }
    }

    /**
      * Processes the entries received by an update operation. The method
      * updates the cache and notifies all subscriber of the map changes.
      * Processing is done on the dispatcher thread.
      */
    private def processEntries(entries: util.List[String], stat: Stat): Unit = {
        try {
            processEntriesUnsafe(entries, stat)
        } catch {
            case NonFatal(e) =>
                // We should never get here, when we do there is a bug, in
                // which case the cache may be corrupted: terminate all
                // subscriptions.
                val message =
                    s"($logId) Unexpected exception when processing " +
                    s"cache entries for version ${stat.getPzxid}"
                Log.error(message, e)
                close(new IllegalStateException(message))
        }
    }

    /**
      * Unsafely processes the entries during an update of the table cache.
      * Exceptions thrown by this method indicate a bug, however they must be
      * caught to prevent crashing the cache executors. When an exception does
      * occur, we log and close the cache. This method must be executed on the
      * dispatcher thread.
      */
    @throws[Exception]
    private def processEntriesUnsafe(entries: util.List[String],
                                     stat: Stat): Unit = {
        val currentCache = cache
        if (currentCache eq null) {
            return
        }

        Log trace s"($logId) Entries updated version:${stat.getPzxid} $entries"

        // Set the current table version.
        val lastVersion = version
        version = stat.getPzxid

        diffAddCache.clear()
        diffRemoveCache.clear()
        // create a table that can hold all the entries without resizing.
        val newCache = new TableEntries(entries.size()*2)

        // Add/update new entries in the cache.
        val entryIterator = entries.iterator()
        while (entryIterator.hasNext) {
            val newEntry = decodeEntry(entryIterator.next())
            // Ignore entries that cannot be decoded.
            if (newEntry != null) {
                // Compute the diff with respect to the previous version
                // of the cache.
                val currentEntry = newCache.get(newEntry.cacheKey)
                if ((currentEntry eq null) ||
                    currentEntry.version == PersistentVersion ||
                    (currentEntry.version < newEntry.version &&
                     newEntry.version != PersistentVersion)) {

                    newCache.put(newEntry.cacheKey, newEntry)
                }
            }
        }

        // Compute the added entries.
        var cacheIterator = newCache.values().iterator()
        while (cacheIterator.hasNext) {
            val newEntry = cacheIterator.next()
            val oldEntry = currentCache.get(newEntry.cacheKey)
            if ((oldEntry eq null) || oldEntry.version != newEntry.version) {
                diffAddCache.add(newEntry)
            }
        }

        // Compute the removed entries.
        cacheIterator = currentCache.values().iterator()
        while (cacheIterator.hasNext) {
            val oldEntry = cacheIterator.next()
            val newEntry = newCache.get(oldEntry.cacheKey)
            if (newEntry eq null) {
                diffRemoveCache.add(oldEntry)
            }
        }

        // Update the cache.
        cache = newCache

        // Compute the differential updates based on the current batch size.
        val updateCount = diffAddCache.size() + diffRemoveCache.size()
        val updates = if (updateCount > 0) {
            val batchCount = (updateCount - 1) / notifyBatchSize + 1

            /**
              * @return A new [[Update]] builder for the given batch index.
              */
            def newUpdateBuilder(index: Int): Update.Builder = {
                if (index >= batchCount) {
                    return null
                }
                val builder = Update.newBuilder()
                    .setType(Update.Type.RELATIVE)
                    .setCurrentVersion(version)
                if (index == 0)
                    builder.setBegin(true)
                if (index == batchCount - 1)
                    builder.setEnd(true)
                builder
            }

            var inIndex = 0
            var outIndex = 0

            val updates = new Array[Update](batchCount)
            var builder = newUpdateBuilder(outIndex)

            while (inIndex < diffAddCache.size()) {
                val entry = diffAddCache.get(inIndex)
                builder.addEntries(Notify.Entry.newBuilder()
                                       .setKey(entry.key)
                                       .setValue(entry.value)
                                       .setVersion(entry.version))
                inIndex += 1
                if (builder.getEntriesCount == notifyBatchSize) {
                    updates(outIndex) = builder.build()
                    outIndex += 1
                    builder = newUpdateBuilder(outIndex)
                }
            }
            inIndex = 0

            while (inIndex < diffRemoveCache.size()) {
                val entry = diffRemoveCache.get(inIndex)
                builder.addEntries(Notify.Entry.newBuilder()
                                       .setKey(entry.key)
                                       .setVersion(entry.version))
                inIndex += 1
                if (builder.getEntriesCount == notifyBatchSize) {
                    updates(outIndex) = builder.build()
                    outIndex += 1
                    builder = newUpdateBuilder(outIndex)
                }
            }

            if (builder ne null) {
                updates(outIndex) = builder.build()
            }

            updates
        } else {
            NoUpdates
        }

        if (pending.get eq null) {
            // Send the updates to all current subscribers.
            val currentSubscriptions = subscriptions
            var subIndex = 0
            while (subIndex < currentSubscriptions.length) {
                currentSubscriptions(subIndex).diff(updates, lastVersion, version)
                subIndex += 1
            }
        } else {
            // Get the subscriptions that were established before the cache was
            // synchronized the first time: we should only get here at most one
            // time.
            val pendingSubscriptions = pending.getAndSet(null)

            // Call the subscription runnable for each initial pending
            // subscription.
            val iterator = pendingSubscriptions.iterator
            while (iterator.hasNext) {
                iterator.next()._2.run()
            }

            // Provide a normal diff to all other subscriptions.
            val currentSubscriptions = subscriptions
            var subIndex = 0
            while (subIndex < currentSubscriptions.length) {
                if (!pendingSubscriptions.contains(currentSubscriptions(subIndex))) {
                    currentSubscriptions(subIndex).diff(updates, lastVersion,
                                                        version)
                }
                subIndex += 1
            }
        }
    }

    /**
      * Computes the latency of a state table operation assuming that the
      * context includes the start timestamp. Returns -1 otherwise.
      */
    private def latency(context: AnyRef): Long = {
        context match {
            case startTime: java.lang.Long =>
                System.currentTimeMillis() - startTime
            case _ => -1L
        }
    }

    /**
      * Decodes a state table path received from storage into a [[TableEntry]]
      * object.
      */
    private def decodeEntry(path: String): TableEntry = {
        if (path.isEmpty) {
            return null
        }
        val tokens =
            if (path.charAt(0) == '/') path.substring(1).split(",")
            else path.split(",")
        if (tokens.length != 3) {
            return null
        }
        try {
            val key = keyDecoder.decode(tokens(0))
            val value = valueDecoder.decode(tokens(1))
            val version = Integer.parseInt(tokens(2))
            new TableEntry(tokens(0), key, value, version)
        } catch {
            case NonFatal(_) => null
        }
    }

    /**
      * Requests a notification update of the current state table for the given
      * subscriber and optional requested version. If the server supports
      * versioning, then it should return a differential update for the given
      * version. Otherwise, it should return a full snapshot of the table. The
      * notification must be done on the dispatcher thread to provide an
      * ordering with respect to other updates.
      */
    private def requestRefresh(subscription: Subscription,
                               lastVersion: Option[Long]): Unit = {
        val runnable = makeRunnable {
            try {
                requestRefreshUnsafe(subscription, lastVersion)
            } catch {
                case NonFatal(e) =>
                    // We should never get here, when we do there is a bug, in
                    // which case the cache may be corrupted: terminate all
                    // subscriptions.
                    val message =
                        s"($logId) Unexpected exception when requesting " +
                        s"updates for version $lastVersion"
                    Log.error(message, e)
                    close(new IllegalStateException(message))
            }
        }

        var previous: Map[Subscription, Runnable] = null
        var current: Map[Subscription, Runnable] = null
        do {
            previous = pending.get()
            if (previous eq null) {
                executor.submit(runnable)
                return
            }
            current = previous + (subscription -> runnable)
        } while (!pending.compareAndSet(previous, current))
    }

    /**
      * Unsafely handles requests for updates from a subscriber. Exceptions
      * thrown by this method indicate a bug, however they must be caught to
      * prevent crashing the cache executors. When an exception does occur, we
      * log and close the cache. This method must be executed on the dispatcher
      * thread.
      */
    @throws[Exception]
    private def requestRefreshUnsafe(subscription: Subscription,
                                     lastVersion: Option[Long]): Unit = {
        // TODO: Add versioning support.

        val currentCache = cache
        if (currentCache eq null) {
            return
        }

        // Build the list of updates for the current snapshot: a snapshot
        // always emits one update, even if empty.
        val batchCount =
            if (currentCache.isEmpty) 1
            else (currentCache.size() - 1) / notifyBatchSize + 1

        /**
          * @return A new [[Update]] builder for the given batch index.
          */
        def newUpdateBuilder(index: Int): Update.Builder = {
            if (index >= batchCount) {
                return null
            }
            val builder = Update.newBuilder()
                .setType(Update.Type.SNAPSHOT)
                .setCurrentVersion(version)
            if (index == 0)
                builder.setBegin(true)
            if (index == batchCount - 1)
                builder.setEnd(true)
            builder
        }

        var index = 0

        val updates = new Array[Update](batchCount)
        var builder = newUpdateBuilder(index)

        val iterator = cache.values().iterator()
        while (iterator.hasNext) {
            val entry = iterator.next()

            builder.addEntries(Notify.Entry.newBuilder()
                                   .setKey(entry.key)
                                   .setValue(entry.value)
                                   .setVersion(entry.version))
            if (builder.getEntriesCount == notifyBatchSize) {
                updates(index) = builder.build()
                index += 1
                builder = newUpdateBuilder(index)
            }
        }

        if (builder ne null) {
            updates(index) = builder.build()
        }

        subscription.snapshot(updates, version)
    }

}
