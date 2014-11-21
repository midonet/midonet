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

package org.midonet.util.collection

trait ObjectPool[T >: Null] {
    def take: T

    def offer(element: T)

    def available: Int

    def capacity: Int
}

trait PooledObject {
    private[this] var refCount = 0

    def pool: ObjectPool[_ >: this.type]

    def clear(): Unit

    def ref(): Unit =
        refCount += 1

    def unref(): Unit = {
        refCount -= 1

        refCount match {
            case 0 =>
                if (pool != null)
                    pool.offer(this)
                clear()
            case neg if neg < 0 =>
                refCount = 0
                throw new IllegalArgumentException("Cannot set ref count below 0")
            case _ =>
        }
    }
}

sealed class ArrayObjectPool[T >: Null : Manifest](val capacity: Int,
                                                   factory: ObjectPool[T] => T)
    extends ObjectPool[T] {

    var available = capacity
    private val pool = new Array[T](capacity)

    {
        var i = 0
        while (i < capacity) {
            pool(i) = factory(this)
            i +=1
        }
    }

    def take: T =
        if (available == 0) {
            null
        } else {
            available -= 1
            pool(available)
        }

    def offer(element: T): Unit =
        if (available < capacity) {
            pool(available) = element
            available += 1
        }
}
