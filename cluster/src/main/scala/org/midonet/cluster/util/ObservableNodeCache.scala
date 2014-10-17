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

import com.google.inject.Inject

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.framework.recipes.cache.NodeCache
import org.apache.curator.framework.recipes.cache.NodeCacheListener

import rx.subjects.BehaviorSubject
import rx.subjects.Subject
import java.io.IOException

/** A wrapper around Curator's NodeCache that exposes updates as observables. */
@Inject
class ObservableNodeCache(zk: CuratorFramework) {

    private var nodeCache: NodeCache = _

    /* Stream of updates to the node */
    private var stream: Subject[ChildData, ChildData] = _

    private val listener = new NodeCacheListener() {
        override def nodeChanged() {
            nodeCache.getCurrentData match {
              case cd: ChildData => stream.onNext(cd)
              case _ => stream.onCompleted()
            }
        }
    }

    /** Connects to ZK and starts watching the node at path. It primes the cache
      * with initial state, and emits the current element.
      */
    def connect(path: String) {
        nodeCache = new NodeCache(zk, path)
        nodeCache.getListenable.addListener(listener)
        nodeCache.start(true)
        stream = BehaviorSubject.create(nodeCache.getCurrentData)
    }

    /** Return the current state of the watched node, or null if not
      * tracked */
    def current = nodeCache.getCurrentData

    /** Stops watching the node but does *not* complete the observable since the
      * node was not deleted.
      *
      * @throws IOException propagated from the underlying NodeCache
      */
    @throws[IOException]
    def close() = nodeCache.close()

    /** Exposes updates on this node as observables since the moment when the
      * connect method was called. Depending on what connect method you use you
      * will receive the initial state or not. The completion of the stream
      * indicates that the node was deleted. If close() is called, the
      * observable will remain open, but not emit any more elements.
      *
      * This observable respects the same guarantees as ZK watchers.
      */
    def observable = stream.asObservable

}
