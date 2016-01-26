/*
 * Copyright 2016 Midokura SARL
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
import org.apache.curator.framework.state.{ConnectionStateListener, ConnectionState}
import org.slf4j.LoggerFactory.getLogger

import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe
import rx.subjects.BehaviorSubject

import org.midonet.cluster.util.ConnectionObservable.State
import org.midonet.cluster.util.ConnectionObservable.State.State

object ConnectionObservable {

    /**
     * An enumeration with the observable state: [[State.Stopped]] when the
     * observable has not been subscribed to yet, and a connection listener has
     * not been set yet, [[State.Started]] after the first subscriber subscribed
     * to the observable, and [[State.Closed]] after the observable is closed
     * by calling the `close()` method.
     *
     * Unlike the [[NodeObservable]], the [[ConnectionObservable]] does not
     * close when the last subscriber unsubscribes.
     */
    object State extends Enumeration {
        type State = Value
        val Stopped, Started, Closed = Value
    }

    /**
     * Creates an [[Observable]] that emits updates for the connection state
     * of the underlying Curator client.
     */
    def create(curator: CuratorFramework): ConnectionObservable = {
        new ConnectionObservable(new OnSubscribeToConnection(curator))
    }

}

private[util]
class OnSubscribeToConnection(curator: CuratorFramework)
    extends OnSubscribe[ConnectionState] {

    private val log = getLogger(s"org.midonet.nsdb.zk-connection-state")

    private val state = new AtomicReference[State](State.Stopped)
    private val subject = BehaviorSubject.create[ConnectionState]

    @volatile
    private var connectionListener = new ConnectionStateListener {
        override def stateChanged(client: CuratorFramework,
                                  state: ConnectionState): Unit = {
            processStateChanged(state)
        }
    }

    override def call(child: Subscriber[_ >: ConnectionState]): Unit = {
        if (state.get == State.Closed) {
            child.onError(new ConnectionObservableClosedException)
            return
        }
        if (state.compareAndSet(State.Stopped, State.Started)) {
            curator.getConnectionStateListenable.addListener(connectionListener)
            subject.onNext(if (curator.getZookeeperClient.isConnected)
                ConnectionState.CONNECTED else ConnectionState.LOST)
        }
        subject.subscribe(child)
    }

    private def processStateChanged(state: ConnectionState): Unit = {
        log.debug("Connection state changed {}", state)
        subject onNext state
    }

    private def close(e: Throwable): Unit = {
        state.set(State.Closed)

        curator.getConnectionStateListenable.removeListener(connectionListener)
        subject.onError(e)

        connectionListener = null
    }

    def close(): Unit = close(new ConnectionObservableClosedException)

}

/**
 * An [[Observable]] that emits notifications when the ZooKeeper connection
 * state changes. A call to `close()` will disconnect the observable from the
 * underlying Curator instance and complete it with a
 * [[NodeObservableClosedException]] error. After the observable becomes closed
 * the observable cannot be used anymore.
 */
class ConnectionObservable(onSubscribe: OnSubscribeToConnection)
    extends Observable[ConnectionState](onSubscribe) {

    /** Completes the observable for all subscribers. An observer subscribing to
      * the observable after this method is called will receive a
      * [[ConnectionObservableClosedException]] error. */
    def close(): Unit = onSubscribe.close()

}

/** Signals that the [[Observable]] is not longer able to emit notifications for
  * the ZooKeeper connection state because it was closed. */
class ConnectionObservableClosedException extends Exception