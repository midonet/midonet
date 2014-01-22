/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
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
