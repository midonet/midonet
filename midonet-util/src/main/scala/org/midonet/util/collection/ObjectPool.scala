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

    def currentRefCount = refCount

    val pool: ObjectPool[_ >: this.type]

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

final class NoOpPool[T >: Null](factory: ObjectPool[T] => T) extends ObjectPool[T] {
    override def take: T = factory(this)
    override def offer(element: T): Unit = { }
    override def available: Int = Int.MaxValue
    override def capacity: Int = Int.MaxValue
}

final class ArrayObjectPool[T >: Null : Manifest](
        val initialCapacity: Int,
        val factory: ObjectPool[T] => T,
        val max: Int = Int.MaxValue)
    extends ObjectPool[T] {

    var available = initialCapacity
    var capacity = initialCapacity
    private var pool = new Array[T](capacity)
    fill(0)

    def take: T =
        if (available > 0) {
            available -= 1
            pool(available)
        } else {
            grow()
            take
        }

    def offer(element: T): Unit =
        if (available < capacity) {
            pool(available) = element
            available += 1
        }

    private def fill(from: Int): Unit = {
        var i = from
        while (i < pool.length) {
            pool(i) = factory(this)
            i += 1
        }
    }

    private def grow(): Unit = {
        val oldCapacity = pool.length
        val newCapacity = oldCapacity + oldCapacity / 2
        if (newCapacity < 0 || newCapacity > max)
            throw new Exception("Out of memory for new ManagedFlows " + newCapacity)

        val newPool = new Array[T](newCapacity)
        Array.copy(pool, 0, newPool, 0, oldCapacity)
        pool = newPool
    }
}
