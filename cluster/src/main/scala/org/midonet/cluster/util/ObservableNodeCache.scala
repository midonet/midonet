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

import java.io.{Closeable, IOException}
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.{ChildData, NodeCache, NodeCacheListener}
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.NoNodeException
import rx.Observable
import rx.subjects.BehaviorSubject

/** A wrapper around Curator's NodeCache that exposes updates as
  * observables, guaranteeing that every subscriber will receive the
  * latest known state of the node, as well as further updates until
  * the node is deleted, when it'll complete the Observable. If the node
  * doesn't exist at the moment when the Observable is created, it will
  * immediately emit an onError with a NoNodeException.
  *
  * The underlying NodeCache will manage the connection to ZK according
  * to the configuration set in the CuratorFramework instance provided.
  * Whenever the retry mechanism gives up and the connection is
  * permanently lost, the Observable will emit an onError with a
  * NodeCacheDisconnected. Any new subscribers will get the same onError
  * immediately after subscribing. This implies that the Observable is
  * unusable, users wanting to keep monitoring the same node should
  * create a new instance.
  *
  * A single call to connect() is required before the observable starts
  * emitting elements. Further calls to connect() will be ignored.
  *
  * A call to close() will forcefully disconnect the underlying NodeCache.
  * The existing subscribers will be notified through a NodeCacheDisconnected
  * exception on the Observable as we're no longer able to report updates
  * from ZK.
  *
  * Setting emitNoNodeAsEmpty to 'true', will change the behaviour of the
  * ObservableNodeCache so that no onError or onCompleted events are emitted
  * if the zookeeper node does not exist or becomes non-existent. Curator's
  * NodeCache informs listeners across those situations, and the observable
  * will behave like that. Under this operation mode, NoNode situations will
  * translate in onNext() events that contain a ChildData object with null
  * stat and data fields. These can be identity-compared with the EMPTY_DATA
  * attribute in the ObservableNodeCache.
  */
class ObservableNodeCache(zk: CuratorFramework,
                          path: String,
                          emitNoNodeAsEmpty: Boolean = false) extends Closeable {

    /* Signals when the connection is stablished and the cache primed */
    private val connected = new AtomicBoolean(false)

    // Indicates whether the node cache is fully closed
    private val terminated = new AtomicBoolean(false)

    // Stream of updates to the node
    private val stream = BehaviorSubject.create[ChildData]()

    // The underlying Node cache
    private val nodeCache = new NodeCache(zk, path)

    val EMPTY_DATA = new ChildData(path, null, null)

    /* The listener that will deal with data changes, and run always single
     * threaded in the zookeeper Event thread, but may run concurrently to a
     * connection events.  If a notification arrives concurrent to a
     * disconnection, we don't bother emitting it since the observable is
     * possibly closed already, and the client will be forced to create a new
     * ObservableNodeCache instance. For any other connection events we can
     * safely emit the element.
     */
    private var nodeCacheListener = new NodeCacheListener {
        override def nodeChanged(): Unit = {
            if (terminated.get()) {
                return
            }
            nodeCache.getCurrentData match {
                case cd: ChildData => stream.onNext(cd)
                case null =>
                    if (emitNoNodeAsEmpty)
                        stream.onNext(EMPTY_DATA)
                    else
                        stream.onCompleted()
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
                case ConnectionState.LOST => close()
                case _ =>
            }
        }
    }

    nodeCache.getListenable.addListener(nodeCacheListener)
    zk.getConnectionStateListenable.addListener(connListener)

    /** Return the current state of the watched node, or null if the node is
      * not tracked, or has been deleted. */
    def current = nodeCache.getCurrentData

    /** Instruct the observable to start emitting node states. This is a
      * BLOCKING operation. */
    def connect(): Unit = {
        if (!connected.compareAndSet(false, true)) {
            return
        }
        nodeCache.start(true)
        if (current == null) {
            if (emitNoNodeAsEmpty)
                stream.onNext(EMPTY_DATA)
            else
                stream.onError(new NoNodeException(s"Absent path: $path"))
        } else {
            stream.onNext(current)
        }
    }

    /** Stops watching the node but does *not* complete the observable since the
      * node was not deleted. Instead, it will emit an onError with a
      * NodeCacheDisconnected exception in order to signal subscribers that they
      * must request a new connection, offering in practise the same behaviour
      * as a connection loss from ZK.
      *
      * Calling close renders the ObservableNodeCache unusable.
      *
      * @throws IOException propagated from the underlying NodeCache if the
      *                     connection close fails.
      */
    @throws[IOException]
    def close(): Unit = {
        if (!terminated.compareAndSet(false, true)) {
            return  // we're already closed
        }
        stream.onError(new NodeCacheDisconnected(path))
        nodeCache.getListenable.removeListener(nodeCacheListener)
        zk.getConnectionStateListenable.removeListener(connListener)
        nodeCacheListener = null
        connListener = null
        nodeCache.close()
    }

    /** Exposes updates on this node as observables since the moment when the
      * connect method was called. The completion of the stream indicates that
      * the node was deleted. If close() is called, the observable will remain
      * open, but not emit any more elements.
      *
      * Note that all onNext notifications are processed directly on the thread
      * that ZK uses to process the server's notification so you can assume that
      * data notifications emitted from the Observable will be serialized.
      *
      * Connection loss events will be notified on a separate thread.
      */
    def observable: Observable[ChildData] = stream.asObservable

}

/** Signals that the Observable is no longer able to emit notifications from
  * ZK, either because we lost the connection to ZK, or because the owner of the
  * ObservableNodeCache closed it. */
class NodeCacheDisconnected(s: String) extends RuntimeException(s)
