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

package org.midonet.cluster.data.storage

import java.util.concurrent.Executors

import scala.collection.mutable

import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.{Observable, Observer}
import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.storage.Storage.ClassInfo
import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.util.concurrent.NamedThreadFactory

/** A cacheable storage allows its client to client to have a simple, read-only
  * cache of all objects of a given class that are in the storage. Cache-misses
  * do not create a request to the actual storage backend. Instead, it simply
  * mirrors the state of the backend, for that object, in an eventually
  * consistent way. */
trait CacheableStorage extends Storage {
    import CacheableStorage._

    /** Return an observable that will emit an event whenever a new object of a
      * certain class is added, removed or updated. The events emited by the
      * observable will consist of the up-to-date list of all objects of the
      * given class, indexed by their ids.
      *
      * @param clazz Class of objects to create a cache of
      * @tparam T Class type (inferred)
      * @return The observable object
      */
    def cache[T <: Obj](clazz: Class[T]): Observable[Map[ObjId, T]] = {
        val cache = mutable.Map[ObjId, T]()
        val clazzInfo = classInfo(clazz)
        val subject = BehaviorSubject.create[Map[ObjId, T]](cache.toMap)
        observable(clazz)
            .subscribe(new ClassObserver[T](clazz, clazzInfo, cache, subject))
        subject.asObservable.distinctUntilChanged()
    }
}

object CacheableStorage {
    private val log = LoggerFactory.getLogger(classOf[CacheableStorage])

    private class ClassObserver[T <: Obj](clazz: Class[T],
                                          classInfo: ClassInfo,
                                          cache: mutable.Map[ObjId, T],
                                          publisher: BehaviorSubject[Map[ObjId, T]])
        extends Observer[Observable[T]] {
        private val singleThread = Executors.newSingleThreadExecutor(
            new NamedThreadFactory(clazz.getName + "-class-observer",
                                   isDaemon = false))
        override def onNext(inner: Observable[T]) = {
            inner.observeOn(Schedulers.from(singleThread))
                .subscribe(new ObjectObserver(clazz, cache, classInfo, publisher))
        }
        override def onError(t: Throwable) = {
            publisher.onError(t)
        }
        override def onCompleted() = {
            publisher.onCompleted()
        }
    }

    private class ObjectObserver[T <: Obj](clazz: Class[T],
                                           cache: mutable.Map[ObjId, T],
                                           classInfo: ClassInfo,
                                           publisher: BehaviorSubject[Map[ObjId, T]])
        extends Observer[T] {
        private var objId: ObjId = null

        /** Publish an immutable snapshot of the cache */
        private def publish() = {
            publisher.onNext(cache.toMap)
        }
        override def onNext(obj: T) = {
            if (objId == null) objId = classInfo.idOf(obj)
            cache += (objId -> obj)
            publish()
        }
        override def onError(e: Throwable) = {
            log.error(s"Error processing event of class ${clazz.getName}: " +
                s"${e.getMessage}", e)
        }
        override def onCompleted() = {
            if (objId != null && cache.contains(objId)) {
                cache -= objId
                publish()
            }
        }
    }
}