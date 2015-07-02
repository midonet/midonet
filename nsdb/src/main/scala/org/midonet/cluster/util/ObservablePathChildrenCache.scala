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

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.annotation.concurrent.GuardedBy

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api._
import org.apache.curator.framework.recipes.cache.PathChildrenCache.StartMode
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent.Type._
import org.apache.curator.framework.recipes.cache._
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.Code.{NONODE, OK}
import org.apache.zookeeper.KeeperException.NoNodeException
import org.apache.zookeeper.WatchedEvent
import org.apache.zookeeper.Watcher.Event.EventType._
import org.slf4j.LoggerFactory.getLogger
import rx.Observable.OnSubscribe
import rx.subjects.{BehaviorSubject, PublishSubject, Subject}
import rx.{Observable, Subscriber}

import org.midonet.cluster.data.storage.{BlackHoleZoomMetrics, ZoomMetrics}
import org.midonet.util.concurrent.Locks._

object ObservablePathChildrenCache {

    /** This method will provide a hot observable emitting the child observables
      * for each of the child nodes that exist now, or are created later, on the
      * given root path.  This method will connect to ZK and prime the cache
      * asynchronously. If the parent node doesn't exist the Observable will
      * immediately emit onError with a NoNodeException.
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
      *
      * Connection management is delegated to the underlying PathChildrenCache,
      * which will deal with reconnections according to the policies and times
      * set in the given CuratorFramework.  When Curator gives up on a
      * connection all subscribers of this observable will be notified by
      * emitting an onError with a PathCacheDisconnectedException, as well as
      * all child observables emitted so far. Any new subscribers to the
      * Observable will immediately receive the same onError.
      *
      * All data notifications are executed on ZK's watcher thread.
      */
    def create(zk: CuratorFramework, path: String,
               zoomMetrics: ZoomMetrics = BlackHoleZoomMetrics)
    : ObservablePathChildrenCache = {
        new ObservablePathChildrenCache(
            new OnSubscribeToPathChildren(zk, path, zoomMetrics)
        )
    }
}

class OnSubscribeToPathChildren(zk: CuratorFramework, path: String,
                                zoomMetrics: ZoomMetrics)
    extends OnSubscribe[Observable[ChildData]] {

    private type ChildMap = mutable.Map[String, Subject[ChildData, ChildData]]
    private val log = getLogger(classOf[OnSubscribeToPathChildren])

    // All the streams of updates for each child
    // TODO: I believe this lock is not quite necessary, we have 1 thread with
    //       zk notifications, so we might be able to remove it if we're careful
    //       with the connection events
    @GuardedBy("childrenLock")
    private var childStreams: ChildMap = mutable.HashMap()
    private val childrenLock = new ReentrantReadWriteLock()

    // The main stream of observables for each child node
    private var stream = PublishSubject.create[Observable[ChildData]]()

    // The failure that completed this Observable, if any.
    private val failure = new AtomicReference[Throwable]()

    @volatile
    private var initialized = false

    // This listener monitors the PathChildrenCache itself, which reports on
    // events affecting children, as well as the zk connection.
    private var cacheListener = new PathChildrenCacheListener {
        override def childEvent(client: CuratorFramework,
                                event: PathChildrenCacheEvent) {
            event.getType match {
                // These run on the event thread
                case CHILD_ADDED =>
                    zoomMetrics.childrenWatcherTriggered()
                    newChild(event.getData)
                case CHILD_UPDATED =>
                    zoomMetrics.nodeWatcherTriggered()
                    changedChild(event)
                case CHILD_REMOVED =>
                    // Node deletion triggers both children and node watchers.
                    zoomMetrics.childrenWatcherTriggered()
                    zoomMetrics.nodeWatcherTriggered()
                    lostChild(event)
                // These run on the connection event thread
                case CONNECTION_SUSPENDED =>
                    log.debug(s"connection suspended $path")
                case CONNECTION_RECONNECTED =>
                    log.debug(s"connection restored $path")
                case CONNECTION_LOST =>
                    failWith(new PathCacheDisconnectedException())
                case INITIALIZED =>
                    initialized = true
                    doInitialize = null // gc the callback
                case _ =>
            }
        }
    }

    // The listener that will deal with connection state changes. Curator
    // has a custom thread managing the connection, so processing this needs to
    // be thread-safe with data notifications (that run on the event thread)
    private var connListener = new ConnectionStateListener {
        override def stateChanged(client: CuratorFramework,
                                  newState: ConnectionState): Unit = {
            newState match {
                case ConnectionState.LOST =>
                    failWith(new PathCacheDisconnectedException())
                case _ =>
            }
        }
    }

    @inline
    private def isFailed: Boolean = { failure.get() != null }

    zk.getConnectionStateListenable.addListener(connListener)

    lazy private val cache = new PathChildrenCache(zk, path, true)
    cache.getListenable.addListener(cacheListener)

    // This callback will be triggered in case the main node which children are
    // being watched is deleted.  Note that Curator's ObservablePathCache only
    // reports deletions on the children, but not the parent node itself.  So
    // we have to do it ourselves.
    //
    // This executes on the same event thread as the data notifications.
    @volatile
    private var doInitialize = new BackgroundCallback {
        override def processResult(c: CuratorFramework,
                                   e: CuratorEvent): Unit = e.getType match {
            case CuratorEventType.EXISTS if !initialized =>
                if (e.getResultCode == NONODE.intValue()) {
                    zoomMetrics.zkNoNodeTriggered()
                    failWith(new NoNodeException(path))
                } else if (e.getResultCode == OK.intValue()) {
                    cache.start(StartMode.POST_INITIALIZED_EVENT)
                }
            case _ =>
        }
    }

    // PathChildrenCache can't deal with deletions of the parent, so we have to
    private var deletionCb = new CuratorWatcher {
        override def process(event: WatchedEvent): Unit = {
            event.getType match {
                case NodeDeleted =>
                    failWith(new ParentDeletedException(path))
                case _ => watchParent()
            }
        }
    }

    /** Watch the parent node for existence, triggering the initialization
      * on the first check. Runs also on our good olde-data event thread.
      */
    private def watchParent(): Unit = {
        if (initialized) {
            zk.checkExists().usingWatcher(deletionCb).inBackground()
        } else {
            zk.checkExists().usingWatcher(deletionCb).inBackground(doInitialize)
        }
    }.forPath(path)

    // Start watching the parent right away, since we rely on its existence
    watchParent()

    /* A new subscriber for the top-level observable. We'll emit te current list
     * of Observable[ChildData] for each of the children we know about, as long
     * as the connection to ZK is not lost.
     *
     * This runs on the data event thread.
     */
    override def call(s: Subscriber[_ >: Observable[ChildData]]): Unit = {
        if (isFailed) {
            s onError failure.get()
        } else {
            withReadLock(childrenLock) { // Probably not necessary, removals
                                         // happen in the same thrad
                val it = childStreams.values.iterator // NPE-safe
                while(it.hasNext && failure.get() == null) {
                    s onNext it.next()
                }
            }
            if (!isFailed) {
                stream subscribe s
            }
        }
    }

    /** The Observable is not recoverable. Mark it as such so any new
      * subscribers get an error and are forced to retrieve a new one. Existing
      * subscribers will receive an onError with the given Throwable, which
      * will give information about the reason why the Observable died.  The
      * same Throwable will be thrown on the child observables.
      *
      * Runs on the connection event thread.
      */
    private def failWith(t: Throwable): Unit = {
        if (!failure.compareAndSet(null, t)) {
            return
        }

        cache.getListenable.removeListener(cacheListener)
        zk.getConnectionStateListenable.removeListener(connListener)

        cache.close()
        if (t.isInstanceOf[ParentDeletedException]) {
            stream.onCompleted()
        } else {
            stream.onError(t)
        }

        childStreams.values.foreach { _ onError t }
        childStreams.clear()

        // Aid GC, in case someone keeps a ref to this object
        stream = null
        cacheListener = null
        deletionCb = null
        connListener = null
        childStreams = null
    }

    /* A child node was deleted, the child observable will be completed.
     *
     * Runs on the data event thread.
     */
    private def lostChild(e: PathChildrenCacheEvent): Unit = {
        var cs: Option[Subject[ChildData, ChildData]] = Option.empty
        withWriteLock(childrenLock) {
            cs = childStreams.remove(e.getData.getPath)
        }
        if (!isFailed) {
            cs.foreach { _.onCompleted() }
        }
    }

    /* A child node was modified, emit the new state on its stream */
    private def changedChild(e: PathChildrenCacheEvent): Unit = {
        childStreams.get(e.getData.getPath).foreach { s =>
            if (!isFailed) {
                s onNext e.getData
            }
        }
    }


    /** Creates a new observable and publishes this observable in the parent
      * path's stream of children. Then emits the child's initial state on its
      * observable. We will have to block the children cache to guarantee no
      * missed updates on subscribers. */
    private def newChild(childData: ChildData): Unit = {
        if (isFailed) {
            return
        }
        var newStream: Subject[ChildData, ChildData] = null
        withWriteLock(childrenLock) {
            if (!childStreams.contains(childData.getPath)) {
                newStream = BehaviorSubject.create(childData)
                childStreams.put(childData.getPath, newStream)
            }
        }
        if (isFailed) {
            childStreams.remove(childData.getPath)
        } else if (newStream != null) {
            stream onNext newStream
        }
    }

    /** Terminate the connection. This will have the same effect as a connection
      * loss. The parent and child observables will emit a
      * PathCacheDisconnectedException signalling that the observables are
      * no longer listening ZK updates. */
    def close(): Unit = failWith(new PathCacheDisconnectedException())

    /** Expose the ChildData of the children under the given absolute path. */
    def child(path: String): ChildData = cache.getCurrentData(path)

    /** Expose the Observable of the given child.
      *
      * Note that all onNext notifications are processed directly on the thread
      * that ZK uses to process the server's notification so you can assume that
      * data notifications emitted from the Observable will be serialized.
      *
      * Connection loss events will be notified on a separate thread.
      */
    def observableChild(path: String): Observable[ChildData] = {
        childStreams.get(path).map(_.asObservable).getOrElse(
            Observable.error(new ChildNotExistsException(path))
        )
    }

    /** Expose a view of the latest known state of all children */
    def allChildren: Seq[ChildData] = {
        if (isFailed) {
            Seq.empty
        } else {
            val children = cache.getCurrentData
            assert(children != null) // canary: if failed, curator broke its API
            children
        }
    }

}
/**
 * The ObservablePathChildrenCache provides a wrapper around an ordinary
 * Curator's PathChildrenCache, exposing an API that allows retrieving an
 * Observable stream that emits Observables for each child node that is found
 * under the parent node. The child observables will emit the state of their
 * corresponding child node, and complete when their child is removed.
 *
 * Note that all onNext notifications are processed directly on the thread
 * that ZK uses to process the server's notification so you can assume that
 * data notifications emitted from the Observable will be serialized.
 */
class ObservablePathChildrenCache(onSubscribe: OnSubscribeToPathChildren)
    extends Observable[Observable[ChildData]] (onSubscribe) {

    /** Complete the observable for all subscribers, and terminate the
      * connection to ZK. */
    def close(): Unit = onSubscribe.close()

    /** Returns the latest state known for the children at the given absolute
      * path, or null if it does not exist. */
    def child(path: String) = onSubscribe.child(path)

    /** Returns an Observable for a given child. If the path does not correspond
      * to a known child, the resulting Observable will immediately onError with
      * an UnknownChild exception. */
    def observableChild(path: String) = onSubscribe.observableChild(path)

    /** Returns a view of all children currently known to the cache */
    def allChildren: Seq[ChildData] = onSubscribe.allChildren
}

/** Signals that the parent node has been deleted */
class ParentDeletedException(path: String)
    extends RuntimeException(s"Parent $path removed")

/** Signals that the underlying cache has lost the connection to ZK. */
class PathCacheDisconnectedException extends RuntimeException

/** Signals that the requested path is not a known child of the observed node */
class ChildNotExistsException(path: String)
    extends RuntimeException(s"Non existing child $path")
