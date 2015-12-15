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

package org.midonet.sdn.state

import org.midonet.util.collection.Reducer

class NoOpFlowStateTable[K >: Null <: AnyRef, V >: Null <: AnyRef]
    extends FlowStateTable[K, V] {

    override def expireIdleEntries(): Unit = {}
    override def ref(key: K): V = { null }
    override def unref(key: K): Unit = {}
    override def get(key: K): V = { null }
    override def expireIdleEntries[U](seed: U, func: Reducer[K, V, U]): U = {
        null.asInstanceOf[U]
    }
    override def touch(key: K, value: V): Unit = {}
    override def fold[U](seed: U, func: Reducer[K, V, U]): U = {
        null.asInstanceOf[U]
    }
    override def putAndRef(key: K, value: V): V = { null }
    override def getRefCount(key: K): Int = { 0 }
}
