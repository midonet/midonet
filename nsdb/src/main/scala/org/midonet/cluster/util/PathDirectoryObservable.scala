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
package org.midonet.cluster.util

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.Code
import org.apache.zookeeper.Watcher.Event.EventType.NodeChildrenChanged
import org.apache.zookeeper.{WatchedEvent, Watcher}

import rx.subjects.BehaviorSubject
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber}
import rx.Observable.OnSubscribe

import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.util.PathDirectoryObservable.State
import org.midonet.cluster.util.PathDirectoryObservable.State.State
import org.midonet.util.functors.makeAction0
import org.midonet.util.logging.Logging

object PathDirectoryObservable {

    object State extends Enumeration {
        type State = Value
        val Stopped, Started, Closed = Value
    }

    private val OnCloseDefault = { }

    /**
      * Creates an [[Observable]] that emits updates of the set of child nodes
      * for a given storage path. The observable completes, when connection to
      * ZooKeeper is closed or when calling the `close()` method.
      *
      * If the path node is deleted before the observable is closed, it emits
      * a [[ParentDeletedException]] error. If the connection to storage is lost,
      * the observable emits a [[DirectoryObservableDisconnectedException]] error.
      * When any other error is received, the observable emits a
      * [[DirectoryObservableDisconnectedException]] error. When the observable
      * is closed by calling the `close()` method, the observable emits a
      * [[DirectoryObservableClosedException]].
      *
      * The `onClose` function is called when the observable closes its
      * underlying watcher to storage, either because the observable has no
      * more subscribers or when the corresponding object was deleted and
      * `completeOnDelete` is set to `true`.
      *
      * @param completeOnDelete The argument specifies whether the observable
      *                         should complete when the path is deleted or if
      *                         it does not exist. When `true` the observable
      *                         completes with a [[ParentDeletedException]] when
      *                         the path is deleted. Otherwise, the observable
      *                         will install an exists watcher on the underlying
      *                         node such that it triggers a notification when
      *                         the node is created.
      */
    def create(curator: CuratorFramework, path: String,
               metrics: StorageMetrics,
               completeOnDelete: Boolean = true,
               onClose: => Unit = OnCloseDefault)
    :PathDirectoryObservable = {
        new PathDirectoryObservable(
            new OnSubscribeToDirectory(curator, path, onClose, completeOnDelete,
                                       metrics))
    }
}

private[util]
class OnSubscribeToDirectory(curator: CuratorFramework, path: String,
                             onClose: => Unit, completeOnDelete: Boolean,
                             metrics: StorageMetrics)
    extends OnSubscribe[Set[String]] with Logging {

    override def logSource = "org.midonet.nsdb.nsdb-directory"
    override def logMark = s"node:$path"

    final val none = Set.empty[String]

    private val state = new AtomicReference[State](State.Stopped)

    private val subject = BehaviorSubject.create[Set[String]]()
    private val children = new AtomicReference[Set[String]]()
    private val connected = new AtomicBoolean(true)

    private val unsubscribeAction = makeAction0 {
        if (!subject.hasObservers) {
            close()
        }
    }

    @volatile
    private var childrenWatcher = new Watcher {
        override def process(event: WatchedEvent): Unit = {
            event.getType match {
                case NodeChildrenChanged =>
                    metrics.watchers.childrenTriggeredWatchers.inc()
                case _ =>
            }
            processWatcher(event)
        }
    }

    @volatile
    private var existsCallback = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit =
            processExists(event)
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
        if (state.get == State.Closed) {
            child.onError(new DirectoryObservableClosedException(path))
            return
        }
        if (state.compareAndSet(State.Stopped, State.Started)) {
            curator.getConnectionStateListenable.addListener(connectionListener)
            refresh()
        }
        if(!subject.subscribe(child).isUnsubscribed) {
            child.add(Subscriptions.create(unsubscribeAction))
        }
    }

    private def refresh(): Unit = {
        if (!connected.get) {
            return
        }
        try {
            curator.getChildren
                .usingWatcher(childrenWatcher)
                .inBackground(childrenCallback)
                .forPath(path)
        } catch {
            case NonFatal(e) =>
                log.debug("Exception on refreshing the directory observable", e)
                close(new DirectoryObservableDisconnectedException(path, e))
        }
    }

    private def monitor(): Unit = {
        if (!connected.get) {
            return
        }
        try {
            curator.checkExists()
                .usingWatcher(childrenWatcher)
                .inBackground(existsCallback)
                .forPath(path)
        } catch {
            case NonFatal(e) =>
                log.debug("Exception on setting the exists watcher", e)
                close(new DirectoryObservableDisconnectedException(path, e))
        }
    }

    private def processWatcher(event: WatchedEvent): Unit = {
        if (state.get == State.Started) {
            log.debug("Watcher event {}: refreshing", event.getType)
            refresh()
        }
    }

    private def processExists(event: CuratorEvent): Unit = {
        if (state.get != State.Started) {
            return
        }
        if (event.getResultCode == Code.OK.intValue()) {
            refresh()
        } else if (event.getResultCode == Code.NONODE.intValue()) {
            log.debug("Node does not exist: waiting for creation")
        } else if (event.getResultCode == Code.CONNECTIONLOSS.intValue()) {
            log.debug("Connection lost: waiting to reconnect")
        } else if (event.getResultCode == Code.SESSIONEXPIRED.intValue()) {
            log.debug("Session expired")
            close(new DirectoryObservableDisconnectedException(path))
        } else {
            log.error(s"Check node existence failed with ${event.getResultCode}")
            close(new DirectoryObservableDisconnectedException(path))
        }
    }

    private def processChildren(event: CuratorEvent): Unit = {
        if (state.get != State.Started) {
            return
        }
        if (event.getResultCode == Code.OK.intValue) {
            children.set(event.getChildren.asScala.toSet)
            subject.onNext(children.get)
        } else if (event.getResultCode == Code.NONODE.intValue) {
            if (completeOnDelete) {
                log.debug("Node deleted: closing the observable")
                close(new ParentDeletedException(path))
            } else {
                children.set(none)
                subject.onNext(none)
                monitor()
            }
        } else if (event.getResultCode == Code.CONNECTIONLOSS.intValue) {
            log.debug("Connection lost: waiting to reconnect")
        } else if (event.getResultCode == Code.SESSIONEXPIRED.intValue()) {
            log.debug("Session expired")
            close(new DirectoryObservableDisconnectedException(path))
        }else  {
            log.error(s"Get children failed with ${event.getResultCode}")
            close(new DirectoryObservableDisconnectedException(path))
        }
    }

    private def processStateChanged(state: ConnectionState): Unit = state match {
        case ConnectionState.CONNECTED =>
            log.debug("Observable connected")
            connected.set(true)
        case ConnectionState.SUSPENDED =>
            log.debug("Observable suspended")
            connected.set(false)
        case ConnectionState.RECONNECTED =>
            log.debug("Observable reconnected")
            if (connected.compareAndSet(false, true)) {
                refresh()
            }
        case ConnectionState.LOST =>
            log.debug("Connection lost")
            connected.set(false)
            close(new DirectoryObservableDisconnectedException(path))
        case ConnectionState.READ_ONLY =>
            log.debug("Observable read-only")
            connected.set(false)
    }

    /** Terminates the connection and complete the observable */
    private def close(e: Throwable): Unit = {
        if (state.compareAndSet(State.Started, State.Closed)) {
            curator.getConnectionStateListenable.removeListener(connectionListener)
            curator.clearWatcherReferences(childrenWatcher)

            onClose
            subject.onError(e)

            connectionListener = null
            childrenWatcher = null
            existsCallback = null
            childrenCallback = null
        }
    }

    /** Exposes the current sequence of children */
    def allChildren: Set[String] = children.get

    /** Closes the directory observable. */
    def close(): Unit = close(new DirectoryObservableClosedException(path))

    /** Indicates that the underlying subject has one or more observers
      * subscribed to it. */
    def hasObservers = subject.hasObservers

    /** Indicates that the observable is in the closed state and therefore
      * unusable. */
    def isClosed = state.get() == State.Closed

    /** Indicates that the observable is started. */
    def isStarted = state.get() == State.Started
}

/**
 * An [[Observable]] that emits notifications when the children for a given
 * path have changed. Each element that is emitted provides the entire set of
 * current children. The observable only watches for changes in the children
 * set, it does not monitor nor it notifies for changes in the children data.
 *
 * The observable manages the underlying ZooKeeper connection according to the
 * configuration set in the provided [[CuratorFramework]] instance. When the
 * connection to the ZooKeeper server is lost, the observable will emit a
 * [[DirectoryObservableDisconnectedException]] error and the observable becomes
 * closed.
 *
 * A call to `close()` will disconnect the observable from ZooKeeper and
 * complete the observable with a [[DirectoryObservableClosedException]] error.
 * After the observable becomes closed, either when the underlying connection
 * was lost or when the `close()` method was called explicitly, the observable
 * cannot be reused.
 */
class PathDirectoryObservable(onSubscribe: OnSubscribeToDirectory)
    extends Observable[Set[String]](onSubscribe) {

    /** Completes the observable for all subscribers, and terminates the
      *  connection to ZK. */
    def close(): Unit = onSubscribe.close()

    /** Returns all children known to the observable. */
    def allChildren: Set[String] = onSubscribe.allChildren

    /** Indicates that the underlying observable has one or more observers
      * subscribed to the observable. */
    def hasObservers = onSubscribe.hasObservers

    /** Indicates that the underlying observable has closed its ZooKeeper
      * connection and is therefore unusable. */
    def isClosed = onSubscribe.isClosed

    /** Returns true iff the observable is started. */
    def isStarted: Boolean = onSubscribe.isStarted
}

/** Signals that the underlying observable has lost the connection to ZK. */
class DirectoryObservableDisconnectedException(val path: String, e: Throwable)
    extends RuntimeException(s"Storage connection for $path was lost.", e) {
    def this(path: String) = this(path, null)
}
/** Signals that the underlying observable has closed its connection to ZK. */
class DirectoryObservableClosedException(val path: String)
    extends RuntimeException(s"Storage connection for $path was closed.")
