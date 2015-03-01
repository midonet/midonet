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

package org.midonet.midolman.state

import org.midonet.sdn.state.{FlowStateTable, IdleExpiration}
import org.midonet.util.collection.Reducer

class MockFlowStateTable[K <: IdleExpiration, V]()(implicit ev: Null <:< V)
            extends FlowStateTable[K,V] {

    var entries: Map[K, V] = Map.empty
    var unrefedKeys: Set[K] = Set.empty

    override def expireIdleEntries() {}

    override def expireIdleEntries[U](
        seed: U, func: Reducer[K, V, U]): U = {
        var s = seed
        for ((k, v) <- entries) {
            s = func(s, k, v)
        }
        entries = Map.empty
        s
    }

    override def getRefCount(key: K) = if (unrefedKeys.contains(key)) 0 else 1

    override def unref(key: K) {
        unrefedKeys += key
    }

    override def fold[U](seed: U, func: Reducer[K, V, U]): U = seed

    override def ref(key: K): V = get(key)

    override def get(key: K): V = entries.get(key).orNull

    override def putAndRef(key: K, value: V): V = {
        entries += key -> value
        value
    }

    override def touch(key: K, value: V): Unit = putAndRef(key, value)
}
