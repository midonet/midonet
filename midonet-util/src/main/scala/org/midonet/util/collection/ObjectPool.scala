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

trait ObjectPool[T] {
    def take: Option[T]

    def offer(element: T)

    def size: Int

    def capacity: Int
}

trait PooledObject {
    private[this] var refCount = 0

    type PooledType

    def self: PooledType

    def pool: ObjectPool[PooledType]

    def clear()

    def ref() { refCount += 1 }

    def unref() {
        refCount -= 1

        refCount match {
            case 0 =>
                if (pool != null)
                    pool.offer(self)
                clear()
            case neg if neg < 0 =>
                refCount = 0
                throw new IllegalArgumentException("Cannot set ref count below 0")
            case _ =>
        }
    }
}

abstract class ArrayObjectPool[T:Manifest](override val capacity: Int) extends ObjectPool[T] {
    private var floating = 0 // total number of allocated objects
    private var avail = 0    // number of allocated objects present in the pool
    private val pool = new Array[T](capacity)

    def allocate: T

    override def take: Option[T] = {
        if (avail == 0 && floating >= capacity) {
            None
        } else if (avail == 0) {
            floating += 1
            Some(allocate)
        } else {
            avail -= 1
            Some(pool(avail))
        }
    }

    override def offer(element: T) {
        if (avail < capacity) {
            pool(avail) = element
            avail += 1
        }
    }

    override def size: Int = capacity - floating + avail
}
