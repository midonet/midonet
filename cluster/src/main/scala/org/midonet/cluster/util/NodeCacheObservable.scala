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

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.{NodeCacheListener, ChildData, NodeCache}
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}

import rx.subjects.PublishSubject
import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

import org.midonet.cluster.util.NodeCacheObservable.State
import org.midonet.cluster.util.NodeCacheObservable.State.State

object NodeCacheObservable {

    object State extends Enumeration {
        type State = Value
        val Latent, Started, Closed = Value
    }

    def create(curator: CuratorFramework, path: String): NodeCacheObservable = {
        new NodeCacheObservable(new OnSubscribeNodeCache(curator, path))
    }
}

private[util]
class OnSubscribeNodeCache(curator: CuratorFramework, path: String)
    extends OnSubscribe[ChildData] {

    private val state = new AtomicReference[State](State.Latent)
    private val cache = new NodeCache(curator, path)
    private val subject = PublishSubject.create[ChildData]()

    @volatile
    private var nodeListener = new NodeCacheListener {
        override def nodeChanged(): Unit = {
            if (state.get == State.Closed) {
                return
            }
            subject.onNext(cache.getCurrentData)
        }
    }

    @volatile
    private var connectionListener = new ConnectionStateListener {
        override def stateChanged(client: CuratorFramework,
                                  state: ConnectionState): Unit = {
            if (state == ConnectionState.LOST) {
                close()
            }
        }
    }

    override def call(child: Subscriber[_ >: ChildData]): Unit = {
        if (state.compareAndSet(State.Latent, State.Started)) {
            curator.getConnectionStateListenable.addListener(connectionListener)
            cache.getListenable.addListener(nodeListener)
            cache.start(true)
        }
        child.onNext(cache.getCurrentData)
        subject.subscribe(child)
    }

    @throws[IOException]
    def close(): Unit = {
        if (state.compareAndSet(State.Started, State.Closed)) {
            curator.getConnectionStateListenable.removeListener(connectionListener)
            cache.getListenable.removeListener(nodeListener)
            connectionListener = null
            nodeListener = null
            subject.onCompleted()
            cache.close()
        }
    }

    def current: ChildData = cache.getCurrentData
}

class NodeCacheObservable(onSubscribe: OnSubscribeNodeCache)
    extends Observable[ChildData](onSubscribe) {

    def current: ChildData = onSubscribe.current

    def close(): Unit = onSubscribe.close()

}
