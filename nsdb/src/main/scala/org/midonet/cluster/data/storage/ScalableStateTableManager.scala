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

import java.util
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.NonFatal

import org.apache.curator.framework.state.{ConnectionState => StorageConnectionState}
import org.apache.zookeeper.KeeperException.{Code, ConnectionLossException}
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.{CreateMode, KeeperException, WatchedEvent, Watcher => KeeperWatcher}

import rx.Subscriber
import rx.observers.SafeSubscriber

import org.midonet.cluster.backend.DirectoryCallback
import org.midonet.cluster.data.storage.ScalableStateTable.{PersistentVersion, TableEntry}
import org.midonet.cluster.data.storage.ScalableStateTableManager.{KeyValue, ProtectedSubscriber}
import org.midonet.cluster.data.storage.StateTable.{Key, Update}
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.{ConnectionState => ProxyConnectionState}
import org.midonet.util.logging.Logger
import org.midonet.util.reactivex.SubscriptionList

object ScalableStateTableManager {

    /**
      * A subscriber that handles any exceptions thrown by a [[SafeSubscriber]].
      */
    private[storage] class ProtectedSubscriber[K, V](tableKey: Key,
                                                     child: Subscriber[_ >: Update[K, V]],
                                                     log: Logger)
        extends SafeSubscriber[Update[K, V]](child) {

        override def onCompleted(): Unit = {
            try super.onCompleted()
            catch {
                case NonFatal(e) =>
                    log.debug(s"[$tableKey] Exception during onCompleted", e)
            }
        }

        override def onError(e: Throwable): Unit = {
            try super.onError(e)
            catch {
                case NonFatal(e2) =>
                    log.debug(s"[$tableKey] Exception during onError: $e", e2)
            }
        }

        def contains(inner: Subscriber[_ >: Update[K, V]]): Boolean = {
            child eq inner
        }
    }

    /**
      * A key-value pair.
      */
    private case class KeyValue[K, V](key: K, value: V)

}

/**
  * Represents the state for this state table, encapsulating the storage
  * callback, watcher and connection subscriber, and the proxy subscriber.
  */
private class ScalableStateTableManager[K, V](table: ScalableStateTable[K, V])
    extends SubscriptionList[Update[K, V]] {

    /**
      * Completes the addition of an entry to the state table.
      */
    private class AddCallback extends DirectoryCallback[String] {

        override def onSuccess(path: String, stat: Stat, context: Object): Unit = {
            processAddCallback(path, context)
        }

        override def onError(e: KeeperException, context: Object): Unit = {
            processAddError(e, context)
        }
    }

    /**
      * Completes the listing of the current table entries.
      */
    private class GetCallback extends DirectoryCallback[util.Collection[String]] {

        override def onSuccess(entries: util.Collection[String], stat: Stat,
                               context: Object): Unit = {
            processGetCallback(entries, stat, context)
        }

        override def onError(e: KeeperException, context: Object): Unit = {
            processGetError(e, context)
        }
    }

    /**
      * Completes the deletion of an existing entry.
      */
    private class DeleteCallback extends DirectoryCallback[Void] {

        override def onSuccess(arg: Void, stat: Stat, context: Object): Unit = {
            processDeleteCallback(context)
        }

        override def onError(e: KeeperException, context: Object): Unit = {
            processDeleteError(e, context)
        }
    }

    /**
      * Handles [[KeeperWatcher]] notifications when the storage directory for
      * this state table has changed.
      */
    private class Watcher extends KeeperWatcher {
        override def process(event: WatchedEvent): Unit = {
            processWatcher(event)
        }
    }

    /**
      * Handles changes to the storage connection state.
      */
    private class StorageConnectionSubscriber
        extends Subscriber[StorageConnectionState] {

        override def onNext(connectionState: StorageConnectionState): Unit = {
            processStorageConnection(connectionState)
        }

        override def onError(e: Throwable): Unit = {
            log.error(s"Unexpected error ${e.getMessage} on the storage " +
                      "connection observable", e)
            table.close(e)
        }

        override def onCompleted(): Unit = {
            log.warn("Unexpected completion of the storage connection " +
                     "observable: closing table")
            table.close(e = null)
        }
    }

    /**
      * Handles changes to the state proxy connection state.
      */
    private class ProxyConnectionSubscriber
        extends Subscriber[ProxyConnectionState] {

        override def onNext(state: ProxyConnectionState): Unit = {
            log debug s"Proxy connection state changed: $state"
        }

        override def onError(e: Throwable): Unit = {
            log.error(s"Unexpected error ${e.getMessage} on the proxy " +
                      "connection observable", e)
            table.close(e)
        }

        override def onCompleted(): Unit = {
            log.warn("Unexpected completion of the proxy connection " +
                     "observable: closing table")
            table.close(e = null)
        }
    }


    private val addCallback = new AddCallback
    private val getCallback = new GetCallback
    private val deleteCallback = new DeleteCallback
    private val watcher = new Watcher

    private val storageConnectionSubscriber = new StorageConnectionSubscriber
    private val proxyConnectionSubscriber = new ProxyConnectionSubscriber

    private val storageConnectedFlag = new AtomicBoolean(true)

    private val cache = new ConcurrentHashMap[K, TableEntry[K, V]]
    private var version = -1L
    private val failures = new ConcurrentLinkedQueue[TableEntry[K, V]]()

    private val updateCache = new util.HashMap[K, TableEntry[K, V]](64)
    private val updates = new util.ArrayList[Update[K, V]](64)
    private val removals = new util.ArrayList[TableEntry[K, V]](16)

    private val adding = new util.HashSet[KeyValue[K, V]](4)
    private val removing = new util.HashSet[KeyValue[K, V]](4)
    private val owned = new util.HashSet[Int]()

    private def log = table.log

    /**
      * Starts the current state by monitoring the connection for the
      * underlying storage and state-proxy client.
      */
    @throws[IllegalStateException]
    def start(): Unit = {
        if (get().terminated) {
            throw new IllegalStateException("State closed")
        }
        table.proxy.connection.subscribe(proxyConnectionSubscriber)
        table.connection.subscribe(storageConnectionSubscriber)
        refresh()
    }

    /**
      * Stops the current state. If there are any subscribers, their
      * notification stream is completed.
      */
    def stop(e: Throwable): Unit = {
        if (get().terminated) {
            return
        }

        // Complete all subscribers.
        val subs = terminate()
        var index = 0
        while (index < subs.length) {
            if (e ne null) {
                subs(index).onError(e)
            } else {
                subs(index).onCompleted()
            }
            index += 1
        }

        proxyConnectionSubscriber.unsubscribe()
        storageConnectionSubscriber.unsubscribe()
        cache.clear()
        failures.clear()
        version = Long.MaxValue
    }

    /**
      * Begins an asynchronous add operation of a new entry to the state
      * table. The method verifies that an entry for the same key-value
      * pair does not already exists or is in the process of being added.
      */
    def add(key: K, value: V): Unit = {
        log trace s"Add $key -> $value"

        val path = table.encodeEntryPrefix(key, value)
        val keyValue = KeyValue(key, value)
        if (storageConnectedFlag.get()) {
            this.synchronized {
                // Do not add the key-value pair if the same key-value is:
                // (i) in the process of being added, or (ii) the cache already
                // contains a locally owned version of the same entry, and the
                // entry is not being removed.
                if (adding.contains(keyValue)) {
                    log debug s"Already adding $key -> $value"
                    return
                }
                val entry = cache.get(key)
                if ((entry ne null) && entry.value == value &&
                    owned.contains(entry.version) &&
                    !removing.contains(keyValue)) {
                    log debug s"Entry $key -> $value exists"
                    return
                }
                adding.add(keyValue)
            }
            table.directory.asyncAdd(path, null, CreateMode.EPHEMERAL_SEQUENTIAL,
                                     addCallback, keyValue)
        } else {
            log warn s"Add $key -> $value failed: not connected"
        }
    }

    /**
      * Completes an asynchronous add operation of a new entry to the state
      * table cache. The method puts the added entry to the local cache if
      * it can overwrite any existing entry for the same key. It also clears
      * the entry from the adding map. This requires a lock to synchronize
      * the cache modification.
      */
    private def addComplete(entry: TableEntry[K, V], keyValue: KeyValue[K, V])
    : Unit = {
        log trace s"Add ${entry.key} -> ${entry.value} completed"

        val removeEntry = this.synchronized {
            val oldEntry = cache.get(entry.key)
            val removeEntry = if (oldEntry eq null) {
                cache.put(entry.key, entry)
                publish(Update(entry.key, table.nullValue, entry.value))

                log trace s"Added ${entry.key} -> ${entry.value} " +
                          s"version:${entry.version}"
                null
            } else if (oldEntry.version == PersistentVersion ||
                       oldEntry.version < entry.version) {
                cache.put(entry.key, entry)
                publish(Update(entry.key, oldEntry.value, entry.value))

                log trace s"Updated ${entry.key} -> ${entry.value} " +
                          s"from version:${oldEntry.version} to " +
                          s"version:${entry.version}"

                if (owned.contains(oldEntry.version)) {
                    oldEntry
                } else {
                    null
                }
            } else {
                log trace s"Ignore ${entry.key} -> ${entry.value}"
                null
            }

            owned.add(entry.version)
            adding.remove(keyValue)
            removeEntry
        }
        if (removeEntry ne null) {
            delete(removeEntry)
        }
    }

    /**
      * Completes an asynchronous add operation that finished with an error.
      */
    private def addError(e: KeeperException, keyValue: KeyValue[K, V]): Unit = {
        // If an add operation fails, we cannot retry since we cannot
        // guarantee the order with respect to subsequent operations.
        log.warn(s"Add ${e.getPath} failed code: ${e.code.intValue()}", e)

        this.synchronized {
            adding.remove(keyValue)
        }
    }

    /**
      * Begins an asynchronous remove operation for an entry in the state
      * table cache. Removal is allowed only if the current table has
      * previously added the value, and if the value matches the argument
      * value.
      */
    def remove(key: K, value: V): V = this.synchronized {
        log trace s"Remove $key -> $value"

        val entry = cache.get(key)
        if (entry eq null) {
            table.nullValue
        } else if (value != table.nullValue && value != entry.value) {
            table.nullValue
        } else if (!owned.contains(entry.version)) {
            table.nullValue
        } else {
            val keyValue = KeyValue(key, entry.value)
            adding.remove(keyValue)
            removing.add(keyValue)
            delete(entry)
            entry.value
        }
    }

    /**
      * Completes the removal of the specified entry.
      */
    private def removeComplete(entry: TableEntry[K, V]): Unit = {
        log trace s"Remove ${entry.key} -> ${entry.value} " +
                  s"version:${entry.version} completed"

        this.synchronized {
            if (cache.remove(entry.key, entry)) {
                publish(Update(entry.key, entry.value, table.nullValue))
            }
            owned.remove(entry.version)
            removing.remove(KeyValue(entry.key, entry.version))
        }
    }

    /**
      * @return True if the table contains the specified key.
      */
    def containsKey(key: K): Boolean = {
        cache.containsKey(key)
    }

    /**
      * @return True if the table contains the specified key-value entry.
      */
    def contains(key: K, value: V): Boolean = {
        val entry = cache.get(key)
        (entry ne null) && entry.value == value
    }

    /**
      * @return The value for the specified key, or [[table.nullValue]] if the
      *         key does not exist.
      */
    def get(key: K): V = {
        val entry = cache.get(key)
        if (entry ne null) {
            entry.value
        } else {
            table.nullValue
        }
    }

    /**
      * @return The set of keys for the specified value.
      */
    def getByValue(value: V): Set[K] = {
        val iterator = cache.entrySet().iterator()
        val set = Set.newBuilder[K]
        while (iterator.hasNext) {
            val entry = iterator.next()
            if (entry.getValue.value == value) {
                set += entry.getKey
            }
        }
        set.result()
    }

    /**
      * @return A snapshot of this state table.
      */
    def snapshot: Map[K, V] = {
        val iterator = cache.entrySet().iterator()
        val map = Map.newBuilder[K, V]
        while (iterator.hasNext) {
            val entry = iterator.next()
            map += entry.getKey -> entry.getValue.value
        }
        map.result()
    }

    override def call(child: Subscriber[_ >: Update[K, V]]): Unit = {
        val subscriber = new ProtectedSubscriber[K, V](table.tableKey, child, log)
        this.synchronized {
            super.call(subscriber)
        }
    }

    /**
      * @see [[SubscriptionList.start()]]
      */
    protected override def start(child: Subscriber[_ >: Update[K, V]])
    : Unit = {
        // Do nothing.
    }

    /**
      * @see [[SubscriptionList.added()]]
      */
    protected override def added(child: Subscriber[_ >: Update[K, V]])
    : Unit = {
        // The call of this method is synchronized with any update sent to
        // subscribers. Send the initial state of this state table to this
        // subscriber.

        val iterator = cache.elements()
        while (iterator.hasMoreElements && !child.isUnsubscribed) {
            val entry = iterator.nextElement()
            child onNext Update(entry.key, table.nullValue, entry.value)
        }
    }

    /**
      * @see [[SubscriptionList.terminated()]]
      */
    protected override def terminated(child: Subscriber[_ >: Update[K, V]])
    : Unit = {
        child onError new IllegalStateException("Table stopped")
    }

    /**
      * Called when the backend storage is connected.
      */
    private def storageConnected(): Unit = {
        storageConnectedFlag set true
    }

    /**
      * Called when the backend storage is disconnected.
      */
    private def storageDisconnected(): Unit = {
        storageConnectedFlag set false
    }

    /**
      * Called when the backend storage is reconnected. The method
      * refreshes the state table cache and retries any previously failed
      * operations.
      */
    private def storageReconnected(): Unit = {
        if (storageConnectedFlag.compareAndSet(false, true)) {
            refresh()
            retry()
        }
    }

    /**
      * Updates the state table cache with the given list of entries. The
      * method computes the difference with respect to the current snapshot
      * and (i) updates the cache with the new values, (ii) deletes the
      * remove value added by this table that are no longer part of this
      * map, and (iii) notifies all changes to the table subscribers.
      */
    private def update(entries: util.Collection[String], ver: Long): Unit = {
        if (get().terminated) {
            return
        }

        log trace s"Entries updated with version:$ver"

        // Ignore updates that are older than the current cache version.
        if (ver < version) {
            log warn s"Ignore storage update version:$ver " +
                     s"previous to version:$version"
            return
        }

        this.synchronized {

            version = ver

            updateCache.clear()
            updates.clear()
            removals.clear()

            val entryIterator = entries.iterator()
            while (entryIterator.hasNext) {
                val nextEntry = table.decodeEntry(entryIterator.next())
                // Ignore entries that cannot be decoded.
                if (nextEntry != null) {
                    // Verify if there are multiple entries for the same key
                    // and if so select the greatest version learned entry.
                    val prevEntry = updateCache.get(nextEntry.key)
                    if ((prevEntry eq null) ||
                        prevEntry.version == PersistentVersion ||
                        (prevEntry.version < nextEntry.version &&
                         nextEntry.version != PersistentVersion)) {

                        updateCache.put(nextEntry.key, nextEntry)
                    }
                }
            }

            // Compute the added entries.
            val addIterator = updateCache.values().iterator()
            while (addIterator.hasNext) {
                val newEntry = addIterator.next()
                val oldEntry = cache.get(newEntry.key)
                if (oldEntry eq null) {
                    cache.put(newEntry.key, newEntry)
                    updates.add(Update(newEntry.key, table.nullValue,
                                       newEntry.value))
                } else if (oldEntry.version != newEntry.version) {
                    cache.put(newEntry.key, newEntry)
                    updates.add(Update(newEntry.key, oldEntry.value,
                                       newEntry.value))
                    // Remove owned replaced entry.
                    if (owned.contains(oldEntry.version)) {
                        removals.add(oldEntry)
                    }
                }
            }

            // Compute the removed entries.
            val removeIterator = cache.entrySet().iterator()
            while (removeIterator.hasNext) {
                val oldEntry = removeIterator.next()
                if (!updateCache.containsKey(oldEntry.getKey)) {
                    removeIterator.remove()
                    updates.add(Update(oldEntry.getKey, oldEntry.getValue.value,
                                       table.nullValue))
                    // Remove owned deleted entry.
                    if (owned.contains(oldEntry.getValue.version)) {
                        removals.add(oldEntry.getValue)
                    }
                }
            }

            log trace s"Update with ${updates.size()} changes"

            // Publish updates to all subscribers.
            val subs = subscribers
            var updateIndex = 0
            while (updateIndex < updates.size()) {
                var subIndex = 0
                while (subIndex < subs.length) {
                    subs(subIndex) onNext updates.get(updateIndex)
                    subIndex += 1
                }
                updateIndex += 1
            }

            // Delete the removed entries.
            log trace s"Deleting ${removals.size()} obsolete entries"
            var index = 0
            while (index < removals.size()) {
                delete(removals.get(index))
                index += 1
            }

        }
    }

    /**
      * Refreshes the state table cache using data from storage.
      */
    private def refresh(): Unit = {
        if (!storageConnectedFlag.get || get().terminated) {
            return
        }

        val context = Long.box(System.currentTimeMillis())
        table.directory.asyncGetChildren("", getCallback, watcher, context)
    }

    /**
      * Handles the failure for the given context: adds the context to the
      * failures queue and if connected calls retry.
      */
    private def failure(entry: TableEntry[K, V]): Unit = {
        failures.offer(entry)
        if (storageConnectedFlag.get()) {
            retry()
        }
    }

    /**
      * Deletes from storage an obsolete table entry that has been added by
      * this state table. The method ignores null values, or entries without
      * a version.
      */
    private def delete(entry: TableEntry[K, V]): Unit = {
        log trace s"Delete ${entry.key} -> ${entry.value} " +
                  s"version:${entry.version} (table version:$version)"

        if (storageConnectedFlag.get()) {
            table.directory.asyncDelete(table.encodeEntryWithVersion(entry),
                                        -1, deleteCallback, entry)
        } else {
            failure(entry)
        }
    }

    /**
      * Retries the previously failed operations.
      */
    private def retry(): Unit = {
        var entry: TableEntry[K, V] = null
        do {
            entry = failures.poll()
            if (entry ne null) {
                delete(entry)
            }
        } while ((entry ne null) && storageConnectedFlag.get())
    }

    /**
      * Publishes an update to all subscribers.
      */
    private def publish(update: Update[K, V]): Unit = {
        val sub = subscribers
        var index = 0
        while (index < sub.length) {
            sub(index) onNext update
            index += 1
        }
    }

    /**
      * Handles changes to the storage connection state.
      */
    private def processStorageConnection(connectionState: StorageConnectionState)
    : Unit = {
        // Ignore, if the manager is terminated.
        if (get().terminated) {
            return
        }
        connectionState match {
            case StorageConnectionState.CONNECTED =>
                log debug "Storage connected"
                storageConnected()
            case StorageConnectionState.SUSPENDED =>
                log debug "Storage connection suspended"
                storageDisconnected()
            case StorageConnectionState.RECONNECTED =>
                log debug "Storage reconnected"
                storageReconnected()
            case StorageConnectionState.LOST =>
                log warn "Storage connection lost"
                storageDisconnected()
                table.close(new ConnectionLossException())
            case StorageConnectionState.READ_ONLY =>
                log warn "Storage connection is read-only"
                storageDisconnected()
        }
    }

    /**
      * Processes the completion of adding a new entry to this state table. The
      * method updates to the current state the map of owned versions, that is
      * entries that have been added through this table.
      */
    private def processAddCallback(path: String, context: Object): Unit = {
        // Ignore, if the manager is terminated.
        if (get().terminated) {
            return
        }
        addComplete(table.decodeEntry(path), context.asInstanceOf[KeyValue[K, V]])
    }

    /**
      * Processes errors during add.
      */
    private def processAddError(e: KeeperException, context: Object): Unit = {
        // Ignore, if the manager is terminated.
        if (get().terminated) {
            return
        }

        addError(e, context.asInstanceOf[KeyValue[K, V]])
    }

    /**
      * Processes the list of entries received from storage for this state
      * table. This entries set always overrides the
      */
    private def processGetCallback(entries: util.Collection[String],
                                   stat: Stat, context: Object): Unit = {
        // Ignore, if the manager is terminated.
        if (get().terminated) {
            return
        }

        log trace s"Read ${entries.size()} entries in ${latency(context)} ms"
        update(entries, stat.getPzxid)
    }

    /**
      * Processes errors during get.
      */
    private def processGetError(e: KeeperException, context: Object): Unit = {
        // Ignore, if the manager is terminated.
        if (get().terminated) {
            return
        }

        e.code() match {
            case Code.NONODE =>
                log debug "State table does not exist or deleted"
                table.close(e = null)
            case Code.CONNECTIONLOSS =>
                log warn "Storage connection lost"
                table.close(e)
            case _ =>
                log warn s"Refreshing state table failed ${e.code()}"
                table.close(e)
        }
    }

    /**
      * Processes delete completions.
      */
    private def processDeleteCallback(context: Object): Unit = {
        // Ignore, if the manager is terminated.
        if (get().terminated) {
            return
        }

        removeComplete(context.asInstanceOf[TableEntry[K, V]])
    }

    /**
      * Processes errors during delete. If the error is retriable, the method
      * enqueues the failed context to retry later.
      */
    private def processDeleteError(e: KeeperException, context: Object): Unit = {
        // Ignore, if the manager is terminated.
        if (get().terminated) {
            return
        }

        val entry = context.asInstanceOf[TableEntry[K, V]]

        e.code() match {
            case Code.CONNECTIONLOSS | Code.OPERATIONTIMEOUT |
                 Code.SESSIONEXPIRED | Code.SESSIONMOVED =>
                log.info(s"Delete ${e.getPath} failed code:" +
                         s"${e.code().intValue()} retrying")
                failure(entry)
            case Code.NONODE =>
                removeComplete(entry)
            case _ =>
                log.warn(s"Delete ${e.getPath} failed code:" +
                         s"${e.code().intValue()}", e)
                removeComplete(entry)
        }
    }

    /**
      * Processes a watcher event for the current state table.
      */
    private def processWatcher(event: WatchedEvent): Unit = {

        log trace "Table data changed: refreshing"
        refresh()
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

}

