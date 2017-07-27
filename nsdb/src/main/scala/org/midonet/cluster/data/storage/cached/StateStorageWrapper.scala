/*
 * Copyright 2017 Midokura SARL
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
package org.midonet.cluster.data.storage.cached

import rx.Observable

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.storage.{StateKey, StateResult, StateStorage, Storage}

/**
  * This class provides a wrapper over a regular storage object. This wrapper
  * can use the cached version or the regular version of the storage, depending
  * on if the cached version has been invalidated or not.
  *
  */
class StateStorageWrapper(private val cacheTtlMs: Long,
                          private val store: Storage,
                          private val stateStore: StateStorage,
                          private val cache: Map[Class[_], Map[ObjId, Object]],
                          private val stateCache: Map[Class[_], Map[ObjId, Map[String, StateKey]]])
    extends StorageWrapper(cacheTtlMs, store, cache) with StateStorage {

    private val cachedStateStore =
        new CachedStateStorage(store, stateStore, stateCache)

    protected def validStateStore: StateStorage =
        if (cacheValid) cachedStateStore else stateStore

    override protected val namespace: String = null // Not used in this wrapper

    override def addValue(clazz: Class[_], id: ObjId, key: String,
                          value: String): Observable[StateResult] =
        validStateStore.addValue(clazz, id, key, value)

    override def removeValue(clazz: Class[_], id: ObjId, key: String,
                             value: String): Observable[StateResult] =
        validStateStore.removeValue(clazz, id, key, value)

    override def getKey(clazz: Class[_], id: ObjId,
                        key: String): Observable[StateKey] =
        validStateStore.getKey(clazz, id, key)

    override def getKey(namespace: String, clazz: Class[_], id: ObjId,
                        key: String): Observable[StateKey] =
        validStateStore.getKey(namespace, clazz, id, key)

    override def keyObservable(clazz: Class[_], id: ObjId,
                               key: String): Observable[StateKey] =
        validStateStore.keyObservable(clazz, id, key)

    override def keyObservable(namespace: String, clazz: Class[_], id: ObjId,
                               key: String): Observable[StateKey] =
        validStateStore.keyObservable(namespace, clazz, id, key)

    override def keyObservable(namespaces: Observable[String], clazz: Class[_],
                               id: ObjId, key: String): Observable[StateKey] =
        validStateStore.keyObservable(namespaces, clazz, id, key)

    override def ownerId: Long = validStateStore.ownerId

    override def failFastOwnerId: Long = validStateStore.failFastOwnerId
}
