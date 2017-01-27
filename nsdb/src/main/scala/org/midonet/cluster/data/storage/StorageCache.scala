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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.{Map => JMap}

import scala.collection.JavaConverters._

import rx.{Observable, Observer}
import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.storage.Storage.ClassInfo
import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.util.functors.makeAction0
import org.midonet.util.logging.Logging

/** A simple, read-only cache of all objects of a given class that are in the
  * storage. Cache-misses do not create a request to the actual storage backend.
  * Instead, it simply mirrors the state of the backend, for the given class of
  * objects.
  *
  * @param clazz The class of objects to cache from the storage
  * @param storage The storage whose objects will be cached
  * @tparam T Class type (inferred)
  */
class StorageCache[T <: Obj](clazz: Class[T], storage: Storage) {
    import StorageCache._

    private val cache = new ConcurrentHashMap[ObjId, T]()
    private val classInfo = storage.objectClasses(clazz)
    private var classObserver: Option[ClassObserver[T]] = None
    private val subject = BehaviorSubject.create(Map.empty[ObjId, T])
    private val subsCount = new AtomicInteger(0)

    /** Return an observable that will emit an event with a map of the currently
      * cached objects. Whenever the cache changes, a new event is emitted, with
      * the new contents of the cache.
      *
      * @return The observable object
      */
    def observable: Observable[Map[ObjId, T]] = {
        subject.asObservable.distinctUntilChanged()
            .doOnSubscribe( makeAction0 { updateSubsCount(+1) } )
            .doOnUnsubscribe( makeAction0 { updateSubsCount(-1) } )
    }

    private def updateSubsCount(delta: Int) = synchronized {
        subsCount.addAndGet(delta) match {
            // last subscriber left => clear cache and close inner observers
            case 0 =>
                classObserver = None
                subject.onNext(Map.empty)
                cache.clear()
            // 1st subscriber started => create cache and inner observers
            case 1 =>
                classObserver = Option(new ClassObserver[T](clazz, classInfo,
                                                            cache, subject))
                classObserver.foreach(storage.observable(clazz).subscribe)
            // everything else => do nothing
            case _ =>
        }
    }
}

object StorageCache extends Logging {
    override def logSource = "org.midonet.nsdb.cache"

    private class ClassObserver[T <: Obj](clazz: Class[T],
                                          classInfo: ClassInfo,
                                          cache: JMap[ObjId, T],
                                          publisher: BehaviorSubject[Map[ObjId, T]])
        extends Observer[Observable[T]] {
        override def onNext(inner: Observable[T]) = {
            inner.subscribe(new ObjectObserver(clazz, classInfo, cache,
                                               publisher))
        }
        override def onError(t: Throwable) = {
            publisher.onError(t)
        }
        override def onCompleted() = {
            publisher.onCompleted()
        }
    }

    private class ObjectObserver[T <: Obj](clazz: Class[T],
                                           classInfo: ClassInfo,
                                           cache: JMap[ObjId, T],
                                           publisher: BehaviorSubject[Map[ObjId, T]])
        extends Observer[T] {
        private var objId: Option[ObjId] = None

        /** Publish an immutable shallow copy of the cache */
        private def publish() = {
            publisher.onNext(cache.asScala.toMap)
        }
        override def onNext(obj: T) = {
            if (objId.isEmpty) objId = Option(classInfo.idOf(obj))
            objId.foreach(cache.put(_, obj))
            publish()
        }
        override def onError(e: Throwable) = {
            log.debug(s"Error processing event of class ${clazz.getName}: " +
                s"${e.getMessage} (id ${objId.getOrElse("unknown")})", e)
        }
        override def onCompleted() = {
            objId.foreach(cache.remove)
            publish()
        }
    }
}
