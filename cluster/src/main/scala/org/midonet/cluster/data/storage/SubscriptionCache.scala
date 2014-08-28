/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.ChildData
import org.midonet.cluster.util.{ObservablePathChildrenCache, ObservableNodeCache}
import org.midonet.util.functors.makeFunc1
import rx.subjects.PublishSubject
import rx.{Subscription, Observer, Observable}
import rx.functions.Func1

import scala.collection.concurrent.TrieMap

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
 */
private[storage]
class InstanceSubscriptionCache[T](val clazz: Class[T],
                                   path: String,
                                   curator: CuratorFramework) {
    private val nodeCache = new ObservableNodeCache(curator)
    nodeCache.connect(path)

    val observable =
        nodeCache.observable.map[T](DeserializerCache.deserializer(clazz))

    def subscribe(observer: Observer[_ >: T]) = observable.subscribe(observer)

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
 */
private[storage]
class ClassSubscriptionCache[T](val clazz: Class[T],
                                path: String,
                                curator: CuratorFramework) {
    private val pathCache = new ObservablePathChildrenCache(curator)
    pathCache.connect(path)

    def subscribe(observer: Observer[_ >: Observable[T]]): Subscription = {
        val subj = PublishSubject.create[Observable[ChildData]]()
        val mapped = subj.map[Observable[T]](instanceObservableConverter)
        mapped.subscribe(observer)
        pathCache.subscribe(subj)
    }

    def close() = pathCache.close()

    private val instanceObservableConverter =
        makeFunc1 { obs: Observable[ChildData] =>
            obs.map[T](DeserializerCache.deserializer(clazz))
        }
}

/**
 * Caches deserializer objects on a per-class basis.
 */
private object DeserializerCache {
    import ZookeeperObjectMapper.deserialize

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