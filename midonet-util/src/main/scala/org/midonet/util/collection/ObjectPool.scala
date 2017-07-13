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
    override def available: Int = Int.MaxValue
    override def offer(element: T): Unit = { }
    override def capacity: Int = Int.MaxValue
}

abstract class AbstractObjectPool[T >: Null : Manifest]
    (val capacity: Int,
     val factory: ObjectPool[T] => T) extends ObjectPool[T]{

    var available = capacity
    protected[collection] val pool = new Array[T](capacity)

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

final class ArrayObjectPool[T >: Null : Manifest]
    (override val capacity: Int,
     override val factory: ObjectPool[T] => T)
    extends AbstractObjectPool[T](capacity, factory) {

    {
        var i = 0
        while (i < capacity) {
            pool(i) = factory(this)
            i += 1
        }
    }

}

final class OnDemandArrayObjectPool[T >: Null : Manifest]
    (override val capacity: Int,
     override val factory: ObjectPool[T] => T)
    extends AbstractObjectPool[T](capacity, factory) {

    override def take: T =
        if (available == 0) {
            null
        } else {
            available -= 1
            if (pool(available) == null) {
                pool(available) = factory(this)
            }
            pool(available)
        }

    /** Set to null the unused objects for garbage collection */
    def release(): Unit = {
        var i = 0
        while (i < available) {
            pool(i) = null
            i += 1
        }
    }

}

/**
  * This class implements a reusable pool of objects that are initialized
  * using the factory provided.
  * The ReusablePool.next() method will loop through the object pool on demand.
  * The user of this class should be responsible of doing the necessary
  * clean/reset of the object and should be also responsible of preventing
  * reusing an object that is still under use, i.e. the object should be copied
  * if it's ment to be a persistent object (or rather not use this kind of
  * pool).
  */
final class ReusablePool[T](size: Int, factory: () => T) {

    private val buf = for (_ <- 0 until size) yield factory()

    private var idx = 0

    def next: T = {
        if (buf.isEmpty) {
            throw new Exception("Cannot iterate over an empty pool.")
        }
        val n = buf(idx % size)
        idx += 1
        n
    }

}
