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

import java.util.concurrent.atomic.AtomicReference

import scala.collection.JavaConverters._

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.Code
import org.apache.zookeeper.{WatchedEvent, Watcher}
import org.slf4j.LoggerFactory.getLogger

import rx.subjects.BehaviorSubject
import rx.subscriptions.Subscriptions
import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

import org.midonet.cluster.util.PathDirectoryObservable.State
import org.midonet.cluster.util.PathDirectoryObservable.State.State
import org.midonet.util.functors.makeAction0

object PathDirectoryObservable {

    object State extends Enumeration {
        type State = Value
        val Stopped, Started, Closed = Value
    }

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
     */
    def create(curator: CuratorFramework, path: String)
    :PathDirectoryObservable = {
        new PathDirectoryObservable(
            new OnSubscribeToDirectory(curator, path))
    }
}

private[util]
class OnSubscribeToDirectory(curator: CuratorFramework, path: String)
    extends OnSubscribe[Set[String]] {

    private val log = getLogger(s"org.midonet.cluster.zk-directory-[$path]")

    private val state = new AtomicReference[State](State.Stopped)

    private val subject = BehaviorSubject.create[Set[String]]()
    private val children = new AtomicReference[Set[String]]()

    private val unsubscribeAction = makeAction0 {
        if (!subject.hasObservers) {
            close()
        }
    }

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
        try {
            curator.getChildren
                .usingWatcher(childrenWatcher)
                .inBackground(childrenCallback)
                .forPath(path)
        } catch {
            case e: Exception =>
                log.debug("Exception on refreshing the directory observable", e)
                close(new DirectoryObservableDisconnectedException(path, e))
        }
    }


    private def processWatcher(event: WatchedEvent): Unit = {
        if (state.get == State.Started) {
            log.debug("Watcher event {}: refreshing", event.getType)
            refresh()
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
            log.debug("Node deleted: closing the observable")
            close(new ParentDeletedException(path))
        } else if (event.getResultCode == Code.CONNECTIONLOSS.intValue) {
            log.debug("Connection lost")
            close(new DirectoryObservableDisconnectedException(path))
        } else  {
            log.error("Get children failed with {} ", event.getResultCode)
            close(new DirectoryObservableDisconnectedException(path))
        }
    }

    private def processStateChanged(state: ConnectionState): Unit = state match {
        case ConnectionState.CONNECTED => log.debug("Observable connected")
        case ConnectionState.SUSPENDED => log.debug("Observable suspended")
        case ConnectionState.RECONNECTED =>
            log.debug("Observable reconnected")
            refresh()
        case ConnectionState.LOST =>
            log.debug("Connection lost")
            close(new DirectoryObservableDisconnectedException(path))
        case ConnectionState.READ_ONLY => log.debug("Observable read-only")
    }

    /** Terminates the connection and complete the observable */
    private def close(e: Throwable): Unit = {
        if (state.compareAndSet(State.Started, State.Closed)) {
            curator.getConnectionStateListenable.removeListener(connectionListener)
            curator.clearWatcherReferences(childrenWatcher)

            subject.onError(e)

            connectionListener = null
            childrenWatcher = null
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

}

/** Signals that the underlying observable has lost the connection to ZK. */
class DirectoryObservableDisconnectedException(val path: String, e: Throwable)
    extends RuntimeException(s"Storage connection for $path was lost.", e) {
    def this(path: String) = this(path, null)
}
/** Signals that the underlying observable has closed its connection to ZK. */
class DirectoryObservableClosedException(val path: String)
    extends RuntimeException(s"Storage connection for $path was closed.")
