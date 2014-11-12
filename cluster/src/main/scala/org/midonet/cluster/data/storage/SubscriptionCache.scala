/*
 * Copyright 2014 Midokura SARL
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

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.ChildData

import rx.functions.{Action1, Action0, Func1}
import rx.internal.operators.OperatorDoOnUnsubscribe
import rx.subjects.{BehaviorSubject, PublishSubject}
import rx.{Observable, Observer, Subscription}

import org.midonet.cluster.util.{ObservableNodeCache, ObservablePathChildrenCache}
import org.midonet.util.functors.{makeAction0, makeFunc1}

/**
 * Watches the specified Zookeeper node's data and deserializes updates to
 * objects of the specified class.
 *
 * A subscribing Observer will immediately receive an onNext event with the
 * current state, additional onNext updates whenever the Zookeeper node is
 * updated, and finally an onCompleted event when the node is deleted.
 *
 * If the node is updated multiple times in quick succession, onNext events for
 * the intermediate states may not be sent. This is due to the nature of
 * Zookeeper watchers. However, the observer will eventually receive the latest
 * state, and events will never be received out of order.
 *
 * @param clazz Class to deserialize to.
 * @param path Zookeeper path of node to watch.
 * @param id The id of the node.
 * @param onLastUnsubscribe A function that is called when the last subscriber
 *                          unsubscribes. This is used to pass the ids of caches
 *                          to garbage collect to ZOOM.
 */
private[storage]
class InstanceSubscriptionCache[T](val clazz: Class[T],
                                   val path: String,
                                   val id: String,
                                   val curator: CuratorFramework,
                                   val onLastUnsubscribe:
                                       (String, InstanceSubscriptionCache[_]) => Unit) {

    // This is still unconnected.
    private val nodeCache = ObservableNodeCache.create(curator, path)

    // Hook it immediately, waiting for connection. BehaviorSubject will take
    // care of the re-emission of the last state..
    private val stream = BehaviorSubject.create[T]()
    nodeCache.map[T](DeserializerCache.deserializer(clazz)).subscribe(stream)

    // Auxiliary stuff for GC
    private val refCount = new AtomicInteger(0)
    private val onUnsubscribe = makeAction0 {
        if (refCount.decrementAndGet() == 0) {
            onLastUnsubscribe(path, this)
        }
    }
    private val onSubscribe = makeAction0 {
        refCount.incrementAndGet()
    }

    /** The Observable where clients interested in updates about this entity may
      * subscribe. Elements will start flowing as soon as connect() is called */
    val observable: Observable[T] = stream.doOnSubscribe(onSubscribe)
                                          .doOnUnsubscribe(onUnsubscribe)

    def subscriptionCount = refCount.get

    /** Plug into underlying stream of updates and start emitting them. */
    def connect(): Unit = nodeCache.connect()

    /** Retrieve the last known value of the watched entity. */
    def current: T = DeserializerCache.deserializer(clazz)
                                      .call(nodeCache.current)

    /** Closes the cache if it does not have any subscribers. Called only by the
      * ZOOM garbage collector.
      *
      * @return True if the cache was closed, false otherwise.
      */
    def closeIfNeeded(): Boolean = {
        if (refCount.get == 0) {
            nodeCache.close()
            true
        } else {
            false
        }
    }
}

/**
 * Watches a Zookeeper node's children. Subscribers will receive a stream of
 * Observable[T] instances, one for each child of the specified node.
 * Subscribing to one of these Observables will have the same effect as
 * subscribing to an InstanceSubscriptionCache.
 *
 * @param clazz Class to deserialize to.
 * @param path Path of parent node to watch.
 * @param onLastUnsubscribe A function that is called when the last subscriber
 *                          unsubscribes. This is used to pass the ids of caches
 *                          to garbage collect to ZOOM.
 */
private[storage]
class ClassSubscriptionCache[T](val clazz: Class[T],
                                path: String,
                                curator: CuratorFramework,
                                onLastUnsubscribe:
                                    (String, ClassSubscriptionCache[_]) => Unit) {
    private val pathCache = new ObservablePathChildrenCache(curator)
    private val refCount = new AtomicInteger(0)
    private val unsubscribeAction = new Action0 {
        override def call(): Unit = if (refCount.decrementAndGet() == 0) {
            onLastUnsubscribe(path, ClassSubscriptionCache.this)
        }
    }

    def subscriptionCount = refCount.get

    def connect() = {
        pathCache.connect(path)
    }

    def subscribe(observer: Observer[_ >: Observable[T]]): Subscription = {
        refCount.incrementAndGet()
        val subj = PublishSubject.create[Observable[ChildData]]()
        val mapped = subj.map[Observable[T]](instanceObservableConverter)
        mapped.subscribe(observer)
        pathCache.observable.doOnUnsubscribe(unsubscribeAction).subscribe(subj)
    }

    /**
     * Closes the cache if it does not have any subscribers.
     * This function is called by the ZOOM garbage collector.
     *
     * @return True if the cache was closed, false otherwise.
     */
    def closeIfNeeded(): Boolean = {
        if (refCount.get == 0) {
            pathCache.close()
            true
        } else {
            false
        }
    }

    private val instanceObservableConverter =
        makeFunc1 { obs: Observable[ChildData] =>
            obs.map[T](DeserializerCache.deserializer(clazz))
        }
}

/**
 * Caches deserializer objects on a per-class basis.
 */
private object DeserializerCache {
    import org.midonet.cluster.data.storage.ZookeeperObjectMapper.deserialize

    private val deserializers = new TrieMap[Class[_], Func1[ChildData, _]]

    def deserializer[T](clazz: Class[T]): Func1[ChildData, T] = {
        deserializers.getOrElse(clazz, {
            val nw = makeDeserializer(clazz)
            val cur = deserializers.putIfAbsent(clazz, nw)
            cur.getOrElse(nw)
        }).asInstanceOf[Func1[ChildData, T]]
    }

    private def makeDeserializer[T](clazz: Class[T]) =
        makeFunc1 { (cd: ChildData) =>
            cd match {
                case null => throw new NotFoundException(clazz, None)
                case c => deserialize (c.getData, clazz)
            }
        }
}
