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

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.{Code, NoNodeException}
import org.apache.zookeeper.{WatchedEvent, Watcher}
import org.slf4j.LoggerFactory.getLogger

import rx.Observable.OnSubscribe
import rx.subjects.BehaviorSubject
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber}

import org.midonet.cluster.util.NodeObservable.State
import org.midonet.cluster.util.NodeObservable.State.State
import org.midonet.util.functors.makeAction0


object NodeObservable {

    object State extends Enumeration {
        type State = Value
        val Latent, Started, Closed = Value
    }

    /**
     * Creates an [[Observable]] that emits updates when the data for a node
     * at a given path changes. The observable completes when the node is
     * deleted, or it emits an error when the connection to ZooKeeper is lost or
     * when calling the `close()` method. If the node does not exist, then
     * the observable emits a [[NoNodeException]] error.
     *
     * If the connection to storage is lost, the observable emits a
     * [[NodeObservableDisconnectedException]]. When the cache is closed by calling
     * the `close()` method, the observable emits a [[NodeObservableClosedException]].
     */
    def create(curator: CuratorFramework, path: String)
    : NodeObservable = {
        new NodeObservable(new OnSubscribeToNode(curator, path))
    }
}

private[util]
class OnSubscribeToNode(curator: CuratorFramework, path: String)
    extends OnSubscribe[ChildData] {

    private val log =
        getLogger(s"${classOf[NodeObservable].getName}-$path")

    private val state = new AtomicReference[State](State.Latent)
    private val currentData = new AtomicReference[ChildData](null)

    private val subject = BehaviorSubject.create[ChildData]()
    private val unsubscribeAction = makeAction0 {
        if (!subject.hasObservers) {
            close()
        }
    }

    @volatile
    private var nodeWatcher = new Watcher {
        override def process(event: WatchedEvent): Unit =
            processWatcher(event)
    }

    @volatile
    private var nodeCallback = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit =
            processNode(event)
    }

    @volatile
    private var connectionListener = new ConnectionStateListener {
        override def stateChanged(client: CuratorFramework,
                                  state: ConnectionState): Unit =
            processStateChanged(state)
    }

    override def call(child: Subscriber[_ >: ChildData]): Unit = {
        if (state.get == State.Closed) {
            child.onError(new NodeObservableClosedException(path))
            return
        }
        if (state.compareAndSet(State.Latent, State.Started)) {
            curator.getConnectionStateListenable.addListener(connectionListener)
            refresh()
        }
        if(!subject.subscribe(child).isUnsubscribed) {
            child.add(Subscriptions.create(unsubscribeAction))
        }
    }

    private def refresh(): Unit = {
        try {
            curator.getData
                .usingWatcher(nodeWatcher)
                .inBackground(nodeCallback)
                .forPath(path)
        } catch {
            case e: Exception =>
                log.debug("Exception on refreshing the node cache", e)
                close(new NodeObservableDisconnectedException(path))
        }
    }

    private def processWatcher(event: WatchedEvent): Unit = {
        if (state.get == State.Started) {
            log.debug("Watcher event {}: refreshing", event.getType)
            refresh()
        }
    }

    private def processNode(event: CuratorEvent): Unit = {
        if (state.get != State.Started) {
            return
        }
        if (event.getResultCode == Code.OK.intValue) {
            val childData = new ChildData(path, event.getStat, event.getData)
            currentData.set(childData)
            subject.onNext(childData)
        } else if (event.getResultCode == Code.NONODE.intValue) {
            if (currentData.get eq null) {
                log.debug("Node does not exist")
                close(new NoNodeException(path))
            } else {
                log.debug("Node deleted: closing the cache")
                close(null)
            }
        } else if (event.getResultCode == Code.CONNECTIONLOSS.intValue) {
            log.debug("Connection lost")
            close(new NodeObservableDisconnectedException(path))
        } else {
            log.error("Get node data failed with {}", event.getResultCode)
            close(new NodeObservableDisconnectedException(path))
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
            subject.onError(new NodeObservableDisconnectedException(path))
        case ConnectionState.READ_ONLY => log.debug("Cache read-only")
    }

    private def close(e: Throwable): Unit = {
        if (state.compareAndSet(State.Started, State.Closed)) {
            curator.getConnectionStateListenable.removeListener(connectionListener)
            curator.clearWatcherReferences(nodeWatcher)

            if (e eq null) subject.onCompleted() else subject.onError(e)

            connectionListener = null
            nodeWatcher = null
            nodeCallback = null
        }
    }

    /** Gets the current node data. */
    def current = currentData.get

    /** Closes the node cache. */
    def close(): Unit = close(new NodeObservableClosedException(path))

    /** Indicates that the underlying subject has one or more observers
      * subscribed to it. */
    def hasObservers = subject.hasObservers

    /** Indicates that the cache is in the closed state and therefore
      * unusable. */
    def isClosed = state.get() == State.Closed
}

/**
 * An [[Observable]] that emits notifications when the date for the node at the
 * given path changes. It manages the underlying ZooKeeper connection according
 * to the configuration set in the provided [[CuratorFramework]] instance. When
 * the connection to the ZooKeeper server is lost, the observable will emit a
 * [[NodeObservableDisconnectedException]] error and the cache becomes closed.
 *
 * A call to `close()` will disconnect the cache from ZooKeeper and complete the
 * observable with a [[NodeObservableClosedException]] error. After the cache becomes
 * closed, either when the underlying connection was lost or when the `close()`
 * method was called explicitly, the cache cannot be reused.
 */
class NodeObservable(onSubscribe: OnSubscribeToNode)
    extends Observable[ChildData](onSubscribe) {

    /** Gets the current node data. */
    def current = onSubscribe.current

    /** Completes the observable for all subscribers, and terminates the
      * connection to ZooKeeper. */
    def close(): Unit = onSubscribe.close()

    /** Indicates that the underlying cache has one ot more observers subscribed
      * to the cache. */
    def hasObservers = onSubscribe.hasObservers

    /** Indicates that the underlying cache has closed its ZooKeeper connection
      * and is therefore unsable. */
    def isClosed = onSubscribe.isClosed

}

/** Signals that the Observable is no longer able to emit notifications from
  * ZK because the connection to ZK was lost. */
class NodeObservableDisconnectedException(s: String) extends RuntimeException(s)

/** Signals that the [[rx.Observable]] is no longer able to emit notifications
  * from ZK< because the [[NodeObservable]] was closed. */
class NodeObservableClosedException(s: String) extends RuntimeException(s)
