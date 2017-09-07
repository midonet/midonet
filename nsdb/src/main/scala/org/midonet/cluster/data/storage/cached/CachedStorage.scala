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

import scala.collection.JavaConverters._
import scala.concurrent.Future

import org.slf4j.LoggerFactory

import rx.Observable

import org.midonet.cluster.cache.ObjectNotification.{MappedSnapshot => ObjSnapshot}
import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.ZoomMetadata.ZoomOwner
import org.midonet.cluster.data.storage.{NotFoundException, PersistenceOp, Storage, Transaction}
import org.midonet.util.logging.Logger

class CachedStorage(private val store: Storage,
                    private val snapshot: ObjSnapshot)
    extends Storage {

    private val log =
        Logger(LoggerFactory.getLogger("org.midonet.cluster.cached-storage"))

    protected def notImplemented = throw new NotImplementedError(
        "Operation not implemented for the initial cached storage")

    /**
      * Synchronous method that executes multiple create, update, and/or delete
      * operations atomically.
      */
    override def multi(ops: Seq[PersistenceOp]): Unit = notImplemented

    /**
      * Creates a new storage transaction that allows multiple read and write
      * operations to be executed atomically. The transaction guarantees that
      * the value of an object is not modified until the transaction is
      * completed or that the transaction will fail with a
      * [[java.util.ConcurrentModificationException]].
      */
    override def transaction(owner: ZoomOwner): Transaction = notImplemented

    /**
      * Tries to execute a transaction using the current write and retry policy.
      */
    override def tryTransaction[R](owner: ZoomOwner)
                                  (f: (Transaction) => R): R = notImplemented

    /**
      * Provide an Observable that emits updates to the specified object
      * asynchronously. Note that an implementation may chose to cache
      * the observable for each given object in order for several callers
      * to share it. Any recycling/GC mechanism may also be applied on
      * observables, as long as it guarantees that the observable has no
      * subscriptions left.
      *
      * When the object exists in the backend, the Observable will always emit
      * at least one element containing its most recent state. After this,
      * further updates will be emitted in the same order as they occurred in
      * the backend storage. Note however that if an object is updated in the
      * backend storage multiple times in quick succession, some updates may not
      * trigger a call to onNext(). In any case, each call to onNext() will
      * provide the most up-to-date data available, and all subscribers will
      * be able to see the same sequence of events, in the same order.
      *
      * When an object doesn't exist the returned observable will
      * immediately complete with an Error containing an IllegalStateException.
      *
      * When an object is deleted, the observable will be completed. If
      * the object doesn't exist in the backend when the Observable is first
      * requested then it'll onError.
      */
    override def observable[T](clazz: Class[T], id: ObjId): Observable[T] =
        Option(snapshot.get(clazz)).map(_ get id) match {
            case Some(cached) =>
                log.debug("Cache hit, starting observable with cached instance " +
                          s"[$clazz, $id] -> $cached")
                store.observable(clazz, id).startWith(cached.asInstanceOf[T])
            case None =>
                log.debug("Cache miss, listening for update from storage " +
                          s"[$clazz, $id]")
                store.observable(clazz, id)
        }

    /**
      * Subscribes to all the entities of the given type. Upon subscription at
      * time t0, obs.onNext() will receive an Observable[T] for each object of
      * class T existing at time t0, and future updates at tn > t0 will each
      * trigger a call to onNext() with an Observable[T] for a new object.
      *
      * Neither obs.onCompleted() nor obs.onError() will be invoked under
      * normal circumstances.
      *
      * The subscribe() method of each of these Observables has the same behavior
      * as ZookeeperObjectMapper.subscribe(Class[T], ObjId).
      */
    override def observable[T](clazz: Class[T]): Observable[Observable[T]] =
        Option(snapshot.get(clazz))
            .map(_.values.asScala.map(_.asInstanceOf[T])) match {
                case Some(cached) =>
                    log.debug("Cache hit, starting observable with cached " +
                              s"instances of [$clazz] -> $cached")
                    val initial = Observable.from(cached.asJava)
                    store.observable(clazz).startWith(initial)
                case None =>
                    log.debug("Cache miss, listening for updates from storage " +
                              s"[$clazz].")
                    store.observable(clazz)
            }

    /**
      * Asynchronous method that gets the specified instance of the specified
      * class from storage.  This method *always* goes to the backend storage
      * in order to fetch the latest version of the entity.
      */
    override def get[T](clazz: Class[T], id: ObjId): Future[T] =
        Option(snapshot.get(clazz)).map(_ get id) match {
            case Some(cached) =>
                log.debug("Cache hit, returning cached value for " +
                          s"[$clazz, $id] -> $cached")
                Future.successful(cached.asInstanceOf[T])
            case None =>
                log.debug("Cache miss, failing for value " +
                          s"[$clazz, $id]")
                Future.failed(new NotFoundException(clazz, id))
        }

    /**
      * Asynchronously gets the specified instances of the specified class from
      * storage. The future completes when all instances have been successfully
      * retrieved, or fails if any of the requested instances cannot be
      * retrieved.  Each element will be fetched from the backend storage in
      * order to retrieve the latest version.
      */
    override def getAll[T](clazz: Class[T],
                           ids: Seq[_ <: ObjId]): Future[Seq[T]] = {
        val allCached = Option(snapshot.get(clazz)).map { all =>
            all.asScala.filterKeys(ids contains _).values.map(_.asInstanceOf[T])
        }.getOrElse(Iterable.empty)
        Future.successful(allCached.toSeq)
    }

    /**
      * Asynchronously gets all instances of the specified class. The future
      * completes when all instances have been successfully retrieved, or fails
      * if any of the requested instances cannot be retrieved. Each element
      * will be fetched from the backend storage in order to retrieve the
      * latest version.
      */
    override def getAll[T](clazz: Class[T]): Future[Seq[T]] = {
        val allCached = Option(snapshot.get(clazz))
            .map(_.values.asScala.map(_.asInstanceOf[T]))
            .getOrElse(Iterable.empty)
        Future.successful(allCached.toSeq)
    }

    /**
      * Asynchronous method that indicated if the specified object exists in the
      * storage.
      */
    override def exists(clazz: Class[_], id: ObjId): Future[Boolean] = {
        val existsCached = Option(snapshot.get(clazz)).exists(_ containsKey id)
        Future.successful(existsCached)
    }
}
