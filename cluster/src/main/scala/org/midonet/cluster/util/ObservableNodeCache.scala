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

import java.util.concurrent.atomic.AtomicBoolean

import com.google.common.base.Function
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.{ChildData, NodeCache, NodeCacheListener}
import org.slf4j.LoggerFactory
import rx.Observable.OnSubscribe
import rx.functions.Action1
import rx.observables.ConnectableObservable
import rx.subscriptions.Subscriptions
import rx.{Subscriber, Subscription}

object ObservableNodeCache {

    private val log = LoggerFactory.getLogger(classOf[ObservableNodeCache])

    /** Exposes updates on this node as observables since the moment when the
      * connect method was called. The Observable will always emit a first
      * update containing the current state of the node.
      *
      * The completion of the stream indicates that the node was deleted. If
      * close() is called, the observable will remain open, but not emit any
      * more elements.
      *
      * This observable respects the same guarantees as ZK watchers.
      */
    def create(zk: CuratorFramework, path: String): ObservableNodeCache = {
        new ObservableNodeCache(new NodeCache(zk, path))
    }
}

/** A wrapper around Curator's NodeCache that exposes updates as observables. */
class ObservableNodeCache(val nodeCache: NodeCache) extends
    ConnectableObservable[ChildData]( // oh Scala, why?
        new OnSubscribe[ChildData] {
            override def call(s: Subscriber[_ >: ChildData]): Unit = {
                val listener = new NodeCacheListener() {
                    override def nodeChanged() {
                        ObservableNodeCache.log.info("DATA")
                        nodeCache.getCurrentData match {
                            case cd: ChildData => s.onNext(cd)
                            case _ => s.onCompleted()
                        }
                    }
                }
                // TODO: this is racey, see in connect. Not sure whether to be
                // more clever here and either lock or whatever to ensure that
                // we never lose the first element, or simply tell clients to
                // take care and ensure that subscribe() and connect() don't
                // happen concurrently. I lean towards the latter.
                val current = nodeCache.getCurrentData
                if (current != null) s.onNext(current) // prime
                nodeCache.getListenable.addListener(listener)
            }
        }
    ) {

    private val isConnected = new AtomicBoolean(false)

    /** Return the current state of the watched node, null if not yet connected
      * or if the node does not exist */
    def current = nodeCache.getCurrentData

    /** Stop the connection to ZK, we don't send an onComplete because the
      * observable because the element was not deleted. */
    def close() = nodeCache.close()

    private val emptyAction = new Action1[Subscription]() {
        override def call(t1: Subscription): Unit = {}
    }

    /** Actions passed to the connection are useless, the Subscription returned
      * on the callback won't really control the connection. To close the
      * underlying connection use close() */
    override def connect(connection: Action1[_ >: Subscription] = emptyAction)
    : Unit = {
        if (isConnected.compareAndSet(false, true)) {
            // subscribe, passing the subscription to the caller
            connection.call(Subscriptions.empty())
            // now connect the node cache, and seed the initial state to
            // existing subscribers
            ObservableNodeCache.log.info(s"CONNECT ---- ")
            nodeCache.start(true)
            // TODO: review this, we're racing with new subscribers that may
            // arrive just after we fetch the Listenable - those may lose
            // the initial state.
            nodeCache.getListenable.forEach(
                new Function[NodeCacheListener, Void]() {
                    override def apply(input: NodeCacheListener): Void = {
                        input.nodeChanged()
                        null
                    }
            })
        }
    }

}
