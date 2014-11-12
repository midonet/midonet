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
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.annotation.concurrent.GuardedBy

import rx.Observable.OnSubscribe
import rx.functions.Action0

import scala.collection.mutable

import com.google.inject.Inject

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.PathChildrenCache.StartMode
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent.Type._
import org.apache.curator.framework.recipes.cache.{ChildData, PathChildrenCache, PathChildrenCacheEvent, PathChildrenCacheListener}

import org.slf4j.LoggerFactory

import rx.internal.operators.OperatorDoOnUnsubscribe
import rx.{Subscription, Subscriber, Observer, Observable}
import rx.subjects.{BehaviorSubject, PublishSubject, Subject}

import org.midonet.util.concurrent.Locks._
import org.midonet.util.functors._

/**
 * The ObservablePathChildrenCache provides a wrapper around a ordinary
 * Curator's PathChildrenCache, exposing an API that allows retrieving an
 * Observable stream that emits Observables for each child node that is found
 * under the parent node. The child observables will emit the state of their
 * corresponding child node, and complete when their child is removed.
 *
 * @param zk Curator client, connection management is assumed to be done by the
 *           caller.
 */
@Inject
class ObservablePathChildrenCache(val zk: CuratorFramework) {

    val log = LoggerFactory.getLogger(classOf[ObservablePathChildrenCache])

    private type PathSub = Subject[Observable[ChildData], Observable[ChildData]]
    private type ChildMap = mutable.Map[String, Subject[ChildData, ChildData]]
    private type UnsubscribeOp = OperatorDoOnUnsubscribe[Observable[ChildData]]

    /* The path we're watching */
    private var path: String = _

    /* The wrapped PathChildrenCache */
    private var pathCache: PathChildrenCache = _

    /* The Subject where we publish all child observables */
    private val stream: PathSub = PublishSubject.create()

    /* The index of Subjects where we publish each child's updates */
    @GuardedBy("childrenLock")
    private val children: ChildMap = mutable.HashMap()

    /* The lock that arbitrates modifications on children */
    private val childrenLock = new ReentrantReadWriteLock()

    private val listener = new PathChildrenCacheListener {
        override def childEvent(client: CuratorFramework,
                                event: PathChildrenCacheEvent) {
            event.getType match {
                case CHILD_ADDED => newChild(event)
                case CHILD_UPDATED => changedChild(event)
                case CHILD_REMOVED => lostChild(event)
                case CONNECTION_SUSPENDED =>
                case CONNECTION_RECONNECTED =>
                case CONNECTION_LOST =>
                case INITIALIZED =>
                case _ =>
            }
        }
    }

    /** Connects to ZK, starts monitoring the node at the given absolute path */
    def connect(toPath: String) {
        path = toPath
        pathCache = new PathChildrenCache(zk, path, true)
        log.debug("Monitoring path {}", path)
        pathCache.getListenable.addListener(listener)
        pathCache.start(StartMode.NORMAL)
    }

    /** Stops monitoring the path's children, and completes the observables
      * provided via observable() */
    def close() {
        pathCache.close()
        stream.onCompleted()
    }

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
    def observable: Observable[Observable[ChildData]] = {
        // this will keep the subscription to the underlying update stream
        var subscription: Subscription = null
        val preseedAndSubscribe = new OnSubscribe[Observable[ChildData]] {
            override def call(s: Subscriber[_ >: Observable[ChildData]]): Unit = {
                withReadLock(childrenLock) {
                    val preSeed = children.values
                    log.info("Subscribe {}, curr size {}", path, preSeed.size)
                    preSeed foreach { s onNext _ } // emit all known children
                    subscription = stream.subscribe(s)
                }
            }
        }
        Observable.create(preseedAndSubscribe).doOnUnsubscribe(new Action0 {
            override def call(): Unit = {
                if (subscription != null) subscription.unsubscribe()
            }
        })
    }

    /** Returns the latest state known for the children at the given absolute
      * path, or null if it does not exist. */
    def child(path: String) = pathCache.getCurrentData(path)

    /** Returns the observable stream of events for the given child. */
    def observableChild(path: String) = this.children.getOrElse(path, null)

    /** Returns all children currently known to the cache */
    def allChildren(): util.List[ChildData] = {
        val children = pathCache.getCurrentData
        assert(children != null) // canary, if failed something changed in the
                                 // curator api
        children
    }

    /** Emits the new data to the child at the given absolute path. If
      * If expectExists is set to true but the child's update stream is not
      * found, we'll create it anyway, but log a warning.
      *
      * If no Observable has been emitted so far for the given child, we'll emit
      * it, priming it with the initial data. Otherwise we'll just emit the data
      * from the child observable.
      *
      * Note that this method contends with new subscriptions for access to the
      * internal cache.
      */
    private def emitToChild(cd: ChildData, expectExists: Boolean) = {
        val path = cd.getPath
        val subject = children.getOrElse(path, {
            if (expectExists) log.warn("Created missing update stream {}", path)
            else log.trace("New update stream for {}", path)
            val s = BehaviorSubject.create(cd)
            children.put(path, s)
            s
        })
        subject.onNext(cd)
        subject
    }

    /** Creates a new observable and publishes this observable in the parent
      * path's stream of children. Then emits the child's initial state on its
      * observable. We will have to block the children cache to guarantee no
      * missed updates on subscribers. */
    private def newChild(e: PathChildrenCacheEvent) {
        withWriteLock(childrenLock) {
            stream.onNext(
                emitToChild(e.getData, expectExists = false)
            )
        }
    }

    /** A child node was deleted, the child observable will be completed and
      * garbage collected. */
    private def lostChild(e: PathChildrenCacheEvent) {
        withWriteLock(childrenLock) {
            val path = e.getData.getPath
            children.remove(path) match {
                case None => log.warn("Changed child, but no stream found {}",
                                      path)
                case Some(s) => s.onCompleted()
            }
        }
    }

    /** A child node was modified, emit the new state on its stream */
    private def changedChild(e: PathChildrenCacheEvent) {
        emitToChild(e.getData, expectExists = true)
    }

}
