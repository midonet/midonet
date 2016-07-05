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

package org.midonet.cluster.data.storage

import scala.concurrent.Future

import rx.Observable

import org.midonet.cluster.data.storage.StateTable.Update

object StateTable {

    /**
      * A unique key for a state table, which includes the object class and
      * identifier, key and value classes, table name and optional arguments.
      */
    case class Key(objectClass: Class[_], objectId: Any,
                   keyClass: Class[_], valueClass: Class[_],
                   name: String, args: Set[String]) {
        private var string: String = null
        override def toString: String = {
            if (string eq null) {
                string = s"${objectClass.getSimpleName}|$objectId|$name"
            }
            string
        }
    }

    case class Update[K, V](key: K, oldValue: V, newValue: V)

    def empty[K, V]: StateTable[K, V] = {
        EmptyStateTable.asInstanceOf[StateTable[K, V]]
    }

    private object EmptyStateTable extends StateTable[Any, Any] {
        private val UnitFuture = Future.successful({})
        private val BooleanFuture = Future.successful(false)
        private val SetFuture = Future.successful(Set.empty[Any])
        private val MapFuture = Future.successful(Map.empty[Any, Any])

        override def start(): Unit = {}
        override def stop(): Unit = {}
        override def add(key: Any, value: Any): Unit = {}
        override def addPersistent(key: Any, value: Any): Future[Unit] =
            UnitFuture
        override def remove(key: Any): Any = null
        override def remove(key: Any, value: Any): Boolean = false
        override def removePersistent(key: Any, value: Any): Future[Boolean] =
            BooleanFuture
        override def containsLocal(key: Any): Boolean = false
        override def containsLocal(key: Any, value: Any): Boolean = false
        override def containsRemote(key: Any): Future[Boolean] =
            BooleanFuture
        override def containsRemote(key: Any, value: Any): Future[Boolean] =
            BooleanFuture
        override def containsPersistent(key: Any, value: Any): Future[Boolean] =
            BooleanFuture
        override def getLocal(key: Any): Any = null
        override def getRemote(key: Any): Future[Any] = null
        override def getLocalByValue(value: Any): Set[Any] = Set.empty
        override def getRemoteByValue(value: Any): Future[Set[Any]] =
            SetFuture
        override def localSnapshot: Map[Any, Any] = Map.empty
        override def remoteSnapshot: Future[Map[Any, Any]] = MapFuture
        override def observable: Observable[Update[Any, Any]] =
            Observable.never()
    }

}

/**
 * The base trait for a state table, containing mappings between keys and
 * values. Mapping entries can be `learned` or `persistent`. For learned entries
 * the underlying implementation must provide a mechanism to discriminate
 * between multiple concurrent writes (or opinions). Persistent entries take
 * precedence over learned entries, and cannot be overwritten by the latter.
 */
trait StateTable[K, V] {

    /**
      * Starts the synchronization of the state table.
      */
    def start(): Unit

    /**
      * Stops the synchronization of the state table.
      */
    def stop(): Unit

    /**
      * Adds a learned key value pair to the state table. The new value will
      * overwrite a previous existing value and take precedence over persistent
      * values.
      */
    def add(key: K, value: V): Unit

    /**
      * Adds a persistent key value pair to the state table. If a learned value
      * for the key already exists, the learned value will take precedence over
      * the persistent one.
      */
    def addPersistent(key: K, value: V): Future[Unit]

    /**
      * Removes the value for the specified key from the state table, and it
      * return the value if any, or `null` otherwise.
      */
    def remove(key: K): V

    /**
      * Removes a key value pair from the state table, and it returns `true`
      * if the value existed.
      */
    def remove(key: K, value: V): Boolean

    /**
      * Removes a persistent key value pair from the state table, and it returns
      * `true` if the value existed.
      */
    def removePersistent(key: K, value: V): Future[Boolean]

    /**
      * Returns whether the cached version of the table contains a value for the
      * specified key, either learned or persistent.
      */
    def containsLocal(key: K): Boolean

    /**
      * Returns whether the cached version of the table contains the key value
      * pair, either learned or persistent.
      */
    def containsLocal(key: K, value: V): Boolean

    /**
      * Returns whether the remote table contains a value for the specified key,
      * either learned or persistent.
      */
    def containsRemote(key: K): Future[Boolean]

    /**
      * Returns whether the remote table contains a value for the specified key,
      * either learned or persistent.
      */
    def containsRemote(key: K, value: V): Future[Boolean]

    /**
      * Returns whether the remote table contains the persistent key value pair.
      */
    def containsPersistent(key: K, value: V): Future[Boolean]

    /**
      * Gets the local cached value for the specified key.
      */
    def getLocal(key: K): V

    /**
      * Gets the remote value for the specified key.
      */
    def getRemote(key: K): Future[V]

    /**
      * Gets the local cached set of keys corresponding to the specified value.
      */
    def getLocalByValue(value: V): Set[K]

    /**
      * Gets the remote set of keys corresponding to the specified value.
      */
    def getRemoteByValue(value: V): Future[Set[K]]

    /**
      * Gets the local cached read-only snapshot for the current state table.
      */
    def localSnapshot: Map[K, V]

    /**
      * Gets the remote read-only snapshot for the current state table.
      */
    def remoteSnapshot: Future[Map[K, V]]

    /**
      * Returns an observable that notifies the updates to the current state
      * table. When subscribed to the observable will start the synchronization
      * of the state table, if not already started.
      */
    def observable: Observable[Update[K, V]]

}
