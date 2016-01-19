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

import java.util.Arrays

import org.midonet.Util

trait ObjectPool[T >: Null] {
    def take: T
    def offer(element: T)
}

trait BoundedObjectPool[T >: Null] extends ObjectPool[T] {
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
}

final class ArrayObjectPool[T >: Null : Manifest](val capacity: Int,
                                                  val factory: BoundedObjectPool[T] => T)
    extends BoundedObjectPool[T] {

    var available = capacity
    private val pool = new Array[T](capacity)

    {
        var i = 0
        while (i < capacity) {
            pool(i) = factory(this)
            i += 1
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

object IndexableObjectPool {
    trait Indexable {
        private[IndexableObjectPool] var indexOrNextFree = -1

        def index: Int = indexOrNextFree
    }
}

final class IndexableObjectPool[T >: Null <: IndexableObjectPool.Indexable : Manifest](
        val initialCapacity: Int,
        val factory: ObjectPool[T] => T) extends ObjectPool[T] {

    private var pool = new Array[T](Util.findNextPositivePowerOfTwo(initialCapacity))
    private var free = -1

    fill(0)

    def take: T =
        if (free >= 0) {
            doTake()
        } else {
            val oldLength = pool.length
            val newCapacity = Util.findNextPositivePowerOfTwo(
                oldLength + (oldLength / 2))
            if (newCapacity < 0)
                throw new Exception("Out of memory for new ManagedFlows")

            val newPool = new Array[T](newCapacity)
            Array.copy(pool, 0, newPool, 0, oldLength)
            pool = newPool
            fill(oldLength)
            doTake()
        }

    def offer(element: T): Unit = {
        val index = element.indexOrNextFree
        element.indexOrNextFree = free
        free = index
    }

    def get(index: Int): T =
        pool(index)

    private def doTake(): T = {
        val index = free
        val element = pool(index)
        free = element.indexOrNextFree
        element.indexOrNextFree = index
        element
    }

    private def fill(from: Int): Unit = {
        var i = from
        while (i < pool.length / 2) { // Pre-allocate some objects
            pool(i) = factory(this)
            pool(i).indexOrNextFree = i - 1
            i += 1
        }
        free = i - 1
    }
}
