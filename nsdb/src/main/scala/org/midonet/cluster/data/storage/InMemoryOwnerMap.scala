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

import scala.collection.mutable

import rx.Observable.OnSubscribe
import rx.{Observable, Subscriber}
import rx.subjects.PublishSubject

/**
 * An in-memory implementation of the OwnerMap trait. It is assumed that
 * a single-thread will call methods of this class.
 */
class InMemoryOwnerMap[K, V >: Null <: AnyRef](ownerId: String)
    extends OwnerMap[K, V] with OnSubscribe[(K, V, String)] {

    private val map = mutable.HashMap[K, V]()
    private val subject = PublishSubject.create[(K, V, String)]()

    override def owner = ownerId

    /**
     * @return True iff this map contains the given key.
     */
    override def containsKey(key: K): Boolean = map.contains(key)

    /**
     * @return A snapshot of this map.
     */
    override def snapshot: Map[K, V] = {
        val snap = mutable.HashMap[K, V]()
        for ((k, v) <- map) {
            snap.put(k, v)
        }
        snap.toMap
    }

    /* This method is called when an observer subscribes to the observable
       below. */
    override def call(s: Subscriber[_ >: (K, V, String)]): Unit = {
        // TODO: Make sure that when someone subscribes, no opinions inserted
        // after the snapshot is taken but before s subscribes to the subject,
        // are missed. Also, is this method executed in the same thread
        // that calls subscribe?
        for ((k, v) <- snapshot) {
            s onNext (k, v, ownerId)
        }
        subject subscribe s
    }

    /**
     * @return An observable that emits the content of this map upon
     *         subscription followed by updates to this map. Updates are of the
     *         form (key, value, owner). A null value indicates that the
     *         corresponding key has been removed.
     */
    override def observable: Observable[(K, V, String)] =
        Observable.create(this)

    /**
     * @return The number of entries in this map.
     */
    override def size: Int = map.size

    /**
     * Associates the given non-null opinion to this key. The value must be
     * immutable.
     *
     * @return The previous opinion associated to this key and in this owner
     *         map, or null if no such opinion exists.
     */
    override def putOpinion(key: K, value: V): V = {
        if (key == null || value == null) {
            throw new NullPointerException("Key or value cannot be null")
        }
        subject onNext ((key, value, ownerId))
        map.put(key, value).orNull
    }

    /**
     * Removes the opinion associated with this key, if any.
     * @return The opinion previously put in this owner map and associated to
     *         this key, or null if no such opinion exists.
     */
    override def removeOpinion(key: K): V = {
        val prev = map.remove(key)
        prev match {
            case Some(v) =>
                subject onNext ((key, null.asInstanceOf[V], ownerId))
                v
            case None => null.asInstanceOf[V]
        }
    }

    /**
     * @return The opinion previously put in this owner map and associated to
     *         this key, or null if no such opinion exists.
     */
    override def getOpinion(key: K): V = map.get(key).orNull
}
