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

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, CuratorListener}
import org.apache.curator.framework.recipes.cache.{ChildData, NodeCache, NodeCacheListener}
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.NoNodeException
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.BehaviorSubject

/** A wrapper around Curator's NodeCache that exposes updates as 
  * observables, guaranteeing that every subscriber will receive the
  * latest known state of the node, as well as further updates until
  * the node is deleted, when it'll complete the Observable. If the node
  * doen't exist at the moment when the Observable is created, it will
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
  * A call to close() will forcefully disconnect the underlying
  * NodeCache. The existing subscribers will be notified through a
  * NodeCacheDisconnected exception on the Observable.
  */
class ObservableNodeCache(zk: CuratorFramework, path: String) {

    private val connected = new AtomicBoolean(false)
    private val terminated = new AtomicBoolean(false)
    private val stream = BehaviorSubject.create[ChildData]()

    /* Stream of updates to the node */
    private val nodeCache = new NodeCache(zk, path)
    nodeCache.getListenable.addListener(new NodeCacheListener {
        override def nodeChanged() {
            if (!terminated.get()) {
                nodeCache.getCurrentData match {
                    case cd: ChildData => stream.onNext(cd)
                    case _ => stream.onCompleted()
                }
            }
        }
    })

    zk.getConnectionStateListenable.addListener(new ConnectionStateListener {
        override def stateChanged(client: CuratorFramework,
                                  newState: ConnectionState): Unit = {
            newState match {
                case ConnectionState.LOST =>
                    terminated.set(true)
                    try {
                        nodeCache.close()
                    } finally {
                        stream.onError(new NodeCacheDisconnected(path))
                    }
                case _ =>
            }
        }
    })

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
            stream.onError(new NoNodeException(s"Absent path: $path"))
        } else {
            stream.onNext(current)
        }
    }

    /** Stops watching the node but does *not* complete the observable since the
      * node was not deleted. Instead, it will emit an onError with a
      * NodeCacheDisconnected exception in order to signal subscribers that they
      * must request a new connection.
      *
      * Calling close renders the ObservableNodeCache unusable.
      *
      * @throws IOException propagated from the underlying NodeCache if the
      *                     connection close fails.
      */
    @throws[IOException]
    def close(): Unit = {
        if (!terminated.compareAndSet(false, true)) {
            return
        }
        stream.onError(new NodeCacheDisconnected(path))
        nodeCache.close()
    }

    /** Exposes updates on this node as observables since the moment when the
      * connect method was called. The completion of the stream indicates that
      * the node was deleted. If close() is called, the observable will remain
      * open, but not emit any more elements. */
    def observable: Observable[ChildData] = stream.asObservable

}

/** Signals that the underlying NodeCache has lost the connection to ZK. */
class NodeCacheDisconnected(s: String) extends RuntimeException(s)
