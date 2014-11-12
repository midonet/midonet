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
package org.midonet.cluster.util

import java.util
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.annotation.concurrent.GuardedBy

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.PathChildrenCache.StartMode
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent.Type._
import org.apache.curator.framework.recipes.cache.{ChildData, PathChildrenCache, PathChildrenCacheEvent, PathChildrenCacheListener}
import org.midonet.util.concurrent.Locks._
import org.slf4j.LoggerFactory.getLogger
import rx.Observable.OnSubscribe
import rx.functions.Action1
import rx.observables.ConnectableObservable
import rx.subjects.{BehaviorSubject, PublishSubject, Subject}
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber, Subscription}

import scala.collection.mutable
import scala.collection.JavaConversions._

object ObservablePathChildrenCache {

    /** This method will provide a hot observable emitting the child observables
      * for each of the child nodes that exist now, or are created later, on
      * the given root path.
      *
      * Assuming the subscription happens at t0, the subscriber will immediately
      * receive one child Observable for each child node that is known at t0 by
      * the cache. These child Observables will in turn emit a single ChildData
      * with the latest known state of the child node.
      *
      * All child node removals happening at t > t0 will cause the corresponding
      * child observable to complete. All child node additions happening at
      * t > t0 will result in a new child observable primed with the initial
      * state being emitted to the subscriber.
      */
    def create(zk: CuratorFramework, path: String)
    : ObservablePathChildrenCache = {
        // TODO: this is needed so the onsubscribe knows where to dump the
        // child observables, not sure if it's the best way
        new ObservablePathChildrenCache(new OnSubscribeToPathChildren(zk, path))
    }
}

class OnSubscribeToPathChildren(val zk: CuratorFramework, val path: String)
    extends OnSubscribe[Observable[ChildData]] {

    private val log = getLogger(classOf[OnSubscribeToPathChildren])

    private val isConnected = new AtomicBoolean(false)
    // The underlying Curator cache
    private val cache = new PathChildrenCache(zk, path, true)

    private val stream = PublishSubject.create[Observable[ChildData]]()

    cache.getListenable.addListener(new PathChildrenCacheListener {
        override def childEvent(client: CuratorFramework,
                                event: PathChildrenCacheEvent) {
            event.getType match {
                case CHILD_ADDED =>
                    newChild(event.getData)
                case CHILD_UPDATED =>
                    changedChild(event)
                case CHILD_REMOVED =>
                    lostChild(event)
                case CONNECTION_SUSPENDED =>
                case CONNECTION_RECONNECTED =>
                case CONNECTION_LOST =>
                case INITIALIZED =>
                case _ =>
            }
        }
    })

    private type ChildMap = mutable.Map[String, Subject[ChildData, ChildData]]
    /* The lock that arbitrates modifications on childStreams */
    private val childrenLock = new ReentrantReadWriteLock()
    @GuardedBy("childrenLock") // unpopulated until we actually connect
    private val childStreams: ChildMap = mutable.HashMap()

    /* A new subscriber for the top-level observable. We'll emit te current list
     * of Observable[ChildData] for each of the children we know about. */
    override def call(s: Subscriber[_ >: Observable[ChildData]]): Unit = {
        withWriteLock(childrenLock) {
            // we can only prime child observables to the new subscriber if
            // already connected to ZK
            if (isConnected.get()) {
                childStreams.values.foreach { s onNext _ }
            }
            stream.subscribe(s)
        }
    }

    /** A child node was deleted, the child observable will be completed and
      * garbage collected. */
    private def lostChild(e: PathChildrenCacheEvent) {
        withWriteLock(childrenLock) {
            val path = e.getData.getPath
            childStreams.remove(path) match {
                case None => log.warn("Change, but no stream found {}", path)
                case Some(s) => s.onCompleted()
            }
        }
    }

    /** A child node was modified, emit the new state on its stream */
    private def changedChild(e: PathChildrenCacheEvent) {
        val path = e.getData.getPath
        val subject = childStreams.getOrElse(path, {
            log.trace("Missing expected stream for {}, creating it", path)
            val s = BehaviorSubject.create[ChildData]()
            childStreams.put(path, s)
            s
        })
        subject.onNext(e.getData)
    }


    /** Creates a new observable and publishes this observable in the parent
      * path's stream of children. Then emits the child's initial state on its
      * observable. We will have to block the children cache to guarantee no
      * missed updates on subscribers. */
    private def newChild(childData: ChildData) {
        withWriteLock(childrenLock) {
            val childStream = BehaviorSubject.create[ChildData](childData)
            childStreams.put(childData.getPath, childStream)
            stream.onNext(childStream)
        }
    }

    /**
     * TODO: document, specially that this is blocking
     */
    def connect(): Unit = {
        if (isConnected.compareAndSet(false, true)) {
            // Force an initial build, blocking
            cache.start(StartMode.BUILD_INITIAL_CACHE)
            cache.getCurrentData.foreach { newChild }
        }
    }

    def close(): Unit = {
        cache.close()
        stream.onCompleted()
    }

    def child(path: String): ChildData = cache.getCurrentData(path)

    def observableChild(path: String): Observable[ChildData] = {
        childStreams.get(path).map(_.asObservable) match {
            case None => null
            case Some(o) => o
        }
    }

    def allChildren: util.List[ChildData] = {
        val children = cache.getCurrentData
        assert(children != null) // canary, if failed something changed in the
                                 // curator api
        children
    }

}
/**
 * The ObservablePathChildrenCache provides a wrapper around a ordinary
 * Curator's PathChildrenCache, exposing an API that allows retrieving an
 * Observable stream that emits Observables for each child node that is found
 * under the parent node. The child observables will emit the state of their
 * corresponding child node, and complete when their child is removed. */
class ObservablePathChildrenCache(onSubscribe: OnSubscribeToPathChildren)
    extends ConnectableObservable[Observable[ChildData]] (onSubscribe) {

    val log = getLogger(classOf[ObservablePathChildrenCache])

    private val emptyAction = new Action1[Subscription]() {
        override def call(t1: Subscription): Unit = {}
    }

    /** Actions passed to the connection are useless, the Subscription returned
      * on the callback won't really control the connection. To close the
      * underlying connection use close() */
    override def connect(connection: Action1[_ >: Subscription] = emptyAction) {
        connection.call(Subscriptions.empty())
        onSubscribe.connect() // TODO: review priming
    }

    /** Stops monitoring the path's children, and completes the observables
      * provided via observable() */
    def close(): Unit = onSubscribe.close()

    /** Returns the latest state known for the children at the given absolute
      * path, or null if it does not exist. */
    def child(path: String) = onSubscribe.child(path)

    /** Returns the observable stream of events for the given child. */
    def observableChild(path: String) = onSubscribe.observableChild(path)

    /** Returns all children currently known to the cache */
    def allChildren(): util.List[ChildData] = onSubscribe.allChildren
}
