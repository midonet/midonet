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

import rx.Observable

import org.midonet.cluster.cache.ObjectMessaging
import org.midonet.cluster.cache.ObjectNotification.{MappedSnapshot => ObjSnapshot}
import org.midonet.cluster.data.ZoomMetadata.ZoomOwner
import org.midonet.cluster.data.storage.{NotFoundException, PersistenceOp, Storage, Transaction}
import org.midonet.cluster.data.{ObjId, oneLiner}
import org.midonet.util.logging.Logger

/**
  * Implements the Storage trait using a cache of the topology objects that is
  * backed up by a map. This is meant to be used only during startup to start
  * simulating new packets faster, and eventually start using an implementation
  * that reaches NSDB directly.
  *
  * For performance, the objects are only finally deserialized into their
  * message type once a client asks for the object. This saves the cost of
  * deserializing the whole map on startup before starting to use it.
  */
class CachedStorage(private val store: Storage,
                    private val snapshot: ObjSnapshot)
    extends Storage {

    private val log = Logger("org.midonet.cluster.cached-storage")

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
        getDeserialized(clazz, id) match {
            case Some(cached) =>
                log.debug("Cache hit, starting observable with cached instance" +
                          s" [$clazz, ${oneLiner(id)}] -> ${oneLiner(cached)}")
                store.observable(clazz, id).startWith(cached)
            case None =>
                log.debug("Cache miss, listening for update from storage " +
                          s"[$clazz, ${oneLiner(id)}]")
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
        getAllDeserialized(clazz) match {
                case Some(cached) =>
                    log.debug("Cache hit, starting observable with cached " +
                              s"instances of [$clazz] -> ${oneLiner(cached)}")
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
        getDeserialized(clazz, id) match {
            case Some(cached) =>
                log.debug("Cache hit, returning cached value for " +
                          s"[$clazz, ${oneLiner(id)}] -> ${oneLiner(cached)}")
                Future.successful(cached)
            case None =>
                log.debug("Cache miss, failing for value " +
                          s"[$clazz, ${oneLiner(id)}]")
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
        val allCached = getAllDeserialized(clazz, filter = ids contains _)
            .getOrElse(Seq.empty)
        Future.successful(allCached)
    }

    /**
      * Asynchronously gets all instances of the specified class. The future
      * completes when all instances have been successfully retrieved, or fails
      * if any of the requested instances cannot be retrieved. Each element
      * will be fetched from the backend storage in order to retrieve the
      * latest version.
      */
    override def getAll[T](clazz: Class[T]): Future[Seq[T]] = {
        val allCached = getAllDeserialized(clazz).getOrElse(Seq.empty)
        Future.successful(allCached)
    }

    /**
      * Asynchronous method that indicated if the specified object exists in the
      * storage.
      */
    override def exists(clazz: Class[_], id: ObjId): Future[Boolean] = {
        val existsCached = Option(snapshot get clazz).exists(_ containsKey id)
        Future.successful(existsCached)
    }

    private def getDeserialized[T](clazz: Class[T], id: ObjId)
    : Option[T] = {
        val cached = Option(snapshot get clazz)
            .map(_ get id)
            .filterNot( _ eq null)
            .map(_.asInstanceOf[T])

        cached match {
            case Some(textData: Array[Byte]) =>
                val obj = deserialize(clazz, textData).asInstanceOf[AnyRef]
                snapshot.get(clazz).put(id.asInstanceOf[AnyRef], obj)
            case _ =>
        }

        cached
    }

    private def getAllDeserialized[T](clazz: Class[T],
                                      filter: (AnyRef) => Boolean = _ => true)
    : Option[Seq[T]] = {
        val cached = Option(snapshot get clazz).map { all =>
            all.asScala.filterKeys(filter).mapValues {
                case textData: Array[Byte] => deserialize[T](clazz, textData)
                case deserialized => deserialized.asInstanceOf[T]
            }
        }

        cached.foreach(_.foreach { entry =>
            snapshot.get(clazz).put(entry._1, entry._2.asInstanceOf[AnyRef])
        })

        cached.map(_.values.toSeq)
    }

    private def deserialize[T](clazz: Class[T], textData: Array[Byte]): T = {
        ObjectMessaging.serializerOf(clazz)
            .convertTextToMessage(textData)
            .asInstanceOf[T]
    }
}
