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

import com.google.common.annotations.VisibleForTesting
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.zookeeper.KeeperException.NoNodeException
import rx.Observable
import rx.functions.Func1

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
                                       (InstanceSubscriptionCache[_]) => Unit) {

    private val nodeCache = new ObservableNodeCache(curator, path)

    // Auxiliary stuff for GC
    private val refCount = new AtomicInteger()
    private val onSubscribe = makeAction0 { refCount.incrementAndGet() }
    private val onUnsubscribe = makeAction0 {
            if (refCount.decrementAndGet() == 0) {
                nodeCache.close()
                onLastUnsubscribe(this)
            }
    }
    private val onError = makeFunc1((error: Throwable) => error match {
        case ex: NoNodeException =>
            Observable.error(new NotFoundException(clazz, id))
        case e: Throwable => Observable.error(e)
    })

    /** The Observable where clients interested in updates about this entity may
      * subscribe. Elements will start flowing as soon as connect() is called
      *
      * The InstanceSubscriptionCache will keep track of subscriptions to this
      * observable and may decide to close the connection to ZK at any time in
      * order to avoid open connections with no subscribers, rendering the
      * InstanceSubscriptionCache unusable. When the connection is closed, the
      * observable will just call onError(NodeCacheDisconnected) to signal that
      * the connection is dead. The subscriber is then responsible to use an
      * operator such as onErrorResumeNext to recreate a new
      * InstanceSubscriptionCache and fetch the restored Observable. */
    val observable = nodeCache.observable
                              .map[T](DeserializerCache.deserializer(clazz))
                              .onErrorResumeNext(onError)
                              .doOnSubscribe(onSubscribe)
                              .doOnUnsubscribe(onUnsubscribe)

    def connect(): Unit = nodeCache.connect()

    @VisibleForTesting
    def subscriptionCount = refCount.get

    /** Retrieve the last known value of the watched entity. */
    def current: T = DeserializerCache.deserializer(clazz)
                                      .call(nodeCache.current)

    def close() = nodeCache.close()

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
                                    (ClassSubscriptionCache[_]) => Unit) {

    private val pathCache = ObservablePathChildrenCache.create(curator, path)
    private val refCount = new AtomicInteger()

    private val decSubscribers = makeAction0 (
        if (refCount.decrementAndGet() == 0) {
            pathCache.close()
            onLastUnsubscribe(ClassSubscriptionCache.this)
        }
    )
    private val incSubscribers = makeAction0(refCount.incrementAndGet())

    private val deserializer = makeFunc1 {
        obs: Observable[ChildData] =>
            obs.map[T](DeserializerCache.deserializer(clazz))
    }

    val observable = pathCache.map[Observable[T]](deserializer)
                              .doOnSubscribe(incSubscribers)
                              .doOnUnsubscribe(decSubscribers)

    def subscriptionCount = refCount.get

    def close() = pathCache.close()

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
