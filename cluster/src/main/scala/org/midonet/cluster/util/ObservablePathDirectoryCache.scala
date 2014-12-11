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

import scala.collection.JavaConverters._

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}
import org.apache.curator.framework.recipes.cache._
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.Code
import org.apache.zookeeper.{WatchedEvent, Watcher}
import org.slf4j.LoggerFactory.getLogger

import rx.subjects.BehaviorSubject
import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

import org.midonet.cluster.util.ObservablePathDirectoryCache.State
import org.midonet.cluster.util.ObservablePathDirectoryCache.State.State

object ObservablePathDirectoryCache {

    object State extends Enumeration {
        type State = Value
        val Latent, Started, Closed = Value
    }

    /**
     * Creates an observable that emits updates of the set of child nodes for a
     * given storage path. The observable completes, when connection to storage
     * is closed, by calling the close() method.
     *
     * If the path node is deleted before the observable is closed, it emits
     * a [[ParentDeletedException]] error.
     * If the connection to storage is lost, the observable emits a
     * [[CacheDisconnectedException]] error.
     * When any other error is received, the observable emits a
     * [[CacheDisconnectedException]] error.
     */
    def create(curator: CuratorFramework, path: String)
    :ObservablePathDirectoryCache = {
        new ObservablePathDirectoryCache(
            new OnSubscribeToDirectory(curator, path))
    }
}

private[util]
class OnSubscribeToDirectory(curator: CuratorFramework, path: String)
    extends OnSubscribe[Set[String]] {

    private val log = getLogger(classOf[OnSubscribeToDirectory])

    private val state = new AtomicReference[State](State.Latent)

    private val subject = BehaviorSubject.create[Set[String]]()
    private val children = new AtomicReference[Set[String]]()

    @volatile
    private var childrenWatcher = new Watcher {
        override def process(event: WatchedEvent): Unit =
            processWatcher(event)
    }

    @volatile
    private var childrenCallback = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit =
            processChildren(event)
    }

    @volatile
    private var connectionListener = new ConnectionStateListener {
        override def stateChanged(client: CuratorFramework,
                                  state: ConnectionState): Unit =
            processStateChanged(state)
    }

    override def call(child: Subscriber[_ >: Set[String]]): Unit = {
        if (state.compareAndSet(State.Latent, State.Started)) {
            curator.getConnectionStateListenable.addListener(connectionListener)
            refresh()
        }
        subject.subscribe(child)
    }

    private def refresh(): Unit = try {
        curator.getChildren
            .usingWatcher(childrenWatcher)
            .inBackground(childrenCallback)
            .forPath(path)
    } catch {
        case e: Exception =>
            log.debug("Exception on refreshing the directory cache", e)
            subject.onError(new CacheDisconnectedException(path, e))
            close()
    }


    private def processWatcher(event: WatchedEvent): Unit = {
        log.debug(s"Watcher: ${event.getType}, refreshing")
        refresh()
    }

    private def processChildren(event: CuratorEvent): Unit = {
        if (event.getResultCode == Code.OK.intValue) {
            children.set(event.getChildren.asScala.toSet)
            subject.onNext(children.get)
        } else if (event.getResultCode == Code.NONODE.intValue) {
            log.debug("Watcher: node deleted, closing the cache")
            subject.onError(new ParentDeletedException(path))
            close()
        } else if (event.getResultCode == Code.CONNECTIONLOSS.intValue) {
            log.debug("Connection lost")
            subject.onError(new CacheDisconnectedException(path))
            close()
        } else  {
            log.debug(s"Get children failed with ${event.getResultCode}")
            subject.onError(new CacheDisconnectedException(path))
            close()
        }
    }

    private def processStateChanged(state: ConnectionState): Unit = state match {
        case ConnectionState.CONNECTED => log.debug("Cache connected")
        case ConnectionState.SUSPENDED => log.debug("Cache suspended")
        case ConnectionState.RECONNECTED =>
            log.debug("Cache reconnected")
            refresh()
        case ConnectionState.LOST =>
            log.debug("Connection lost")
            subject.onError(new CacheDisconnectedException(path))
        case ConnectionState.READ_ONLY => log.debug("Cache read-only")
    }

    /** Terminates the connection and complete the observable */
    def close(): Unit = if (state.compareAndSet(State.Started, State.Closed)) {
        curator.getConnectionStateListenable.removeListener(connectionListener)
        curator.clearWatcherReferences(childrenWatcher)

        subject.onCompleted()

        connectionListener = null
        childrenWatcher = null
        childrenCallback = null
    }

    /** Exposes the current sequence of children */
    def allChildren: Set[String] = children.get

}

/**
 * The [[ObservablePathDirectoryCache]] provides a lightweight wrapper around
 * an ordinary Curator's [[PathChildrenCache]]. Unlike the
 * [[ObservablePathChildrenCache]], this observable emits the set of all
 * current children names whenever a child is added or removed. This observable
 * does not cache or emit child node data.
 */
class ObservablePathDirectoryCache(onSubscribe: OnSubscribeToDirectory)
    extends Observable[Set[String]](onSubscribe) {

    /**
     * Completes the observable for all subscribers, and terminates the
     * connection to ZK.
     */
    def close(): Unit = onSubscribe.close()

    /**
     * Returns all children known to the cache.
     */
    def allChildren: Set[String] = onSubscribe.allChildren

}

/** Signals that the underlying cache relies on a non existent path */
class ParentDeletedException(val path: String)
    extends RuntimeException(s"Parent $path removed")

/** Signals that the underlying cache has lost the connection to ZK. */
class CacheDisconnectedException(val path: String, e: Throwable)
    extends RuntimeException(s"Storage connection for $path was lost.", e) {
    def this(path: String) = this(path, null)
}
