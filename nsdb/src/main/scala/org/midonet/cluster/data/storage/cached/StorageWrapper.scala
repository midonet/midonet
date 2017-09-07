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

import scala.concurrent.Future

import org.slf4j.LoggerFactory

import rx.Observable

import org.midonet.cluster.cache.ObjectNotification.{MappedSnapshot => ObjSnapshot}
import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.ZoomMetadata.ZoomOwner
import org.midonet.cluster.data.storage.{PersistenceOp, Storage, Transaction}
import org.midonet.util.logging.Logger

/**
  * This class provides a wrapper over a regular storage object. This wrapper
  * can use the cached version or the regular version of the storage, depending
  * on if the cached version has been invalidated or not.
  *
  */
class StorageWrapper(private val cacheTtlMs: Long,
                     private val store: Storage,
                     private val snapshot: ObjSnapshot)
    extends Storage {

    private val log = Logger(LoggerFactory.getLogger("org.midonet.cluster.storage-wrapper"))

    private val cachedStore = new CachedStorage(store, snapshot)

    @volatile
    protected var cacheValid: Boolean = true

    def invalidateCache(): Unit = {
        log.debug(s"Invalidating NSDB ${getClass.getName} cache after $cacheTtlMs ms.")
        cacheValid = false
    }

    protected def validStore: Storage = if (cacheValid) cachedStore else store

    override def multi(ops: Seq[PersistenceOp]): Unit = validStore.multi(ops)

    override def transaction(owner: ZoomOwner): Transaction =
        validStore.transaction(owner)

    override def observable[T](clazz: Class[T], id: ObjId): Observable[T] =
        validStore.observable(clazz, id)

    override def observable[T](clazz: Class[T]): Observable[Observable[T]] =
        validStore.observable(clazz)

    override def tryTransaction[R](owner: ZoomOwner)(f: (Transaction) => R): R =
        validStore.tryTransaction(owner)(f)

    override def get[T](clazz: Class[T], id: ObjId): Future[T] =
        validStore.get(clazz, id)

    override def getAll[T](clazz: Class[T],
                           ids: Seq[_ <: ObjId]): Future[Seq[T]] =
        validStore.getAll(clazz, ids)

    override def getAll[T](clazz: Class[T]): Future[Seq[T]] =
        validStore.getAll(clazz)

    override def exists(clazz: Class[_], id: ObjId): Future[Boolean] =
        validStore.exists(clazz, id)
}
