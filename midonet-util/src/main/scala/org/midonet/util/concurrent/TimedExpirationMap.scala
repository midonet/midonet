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

package org.midonet.util.concurrent

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.concurrent.duration.Duration

import org.midonet.util.PaddedAtomicInteger
import org.midonet.util.collection.Reducer
import org.midonet.util.logging.Logger

/**
 * A concurrent map where each entry has an associated reference counter, with
 * the removal of entries happening when the counter reaches 0 and after a
 * configurable delay. During this period of time the entry can be resurrected
 * by a new reference. All operations are thread-safe and non-blocking. The
 * obliterateIdleEntries() operation is limited to one caller at a time.
 *
 * The obliterateIdleEntries() receives a reducer to which we pass all the
 * removed entries. This way a caller can distinguish when a new entry is
 * inserted (putAndRef(), putIfAbsentAndRef(), and ref() all return the previous
 * value associated with a key or null if there was none) and, via the reducer,
 * if an entry is expired. Expiration of the key is only committed after the
 * call into the reducer, and during this time put() calls are prevented from
 * adding the same key back to the map. If an operation that the caller executes
 * when an entry is inserted happens-before the corresponding unref(), then it
 * also happens-before any operation the caller makes during entry expiration.
 *
 * Synchronization is lock-free, and this is how the possible races are
 * prevented. Most are trivial but explained for completeness:
 *
 *   + At reference creation time
 *      - Two ref() calls could race with each other. This is avoided
 *        by using putIfAbsent() to write to the ref-count map. The loser of
 *        the race will retry the operation through a recursive call.
 *
 *      - unref() is a no-op in this case.
 *
 *   + At reference expiration time
 *      - When unref decrements a count to zero, it will calculate the
 *        expiration time and put it in the expiring queue. Because the counts
 *        could vary (to one and back to zero) in the expiration interval, the
 *        canonical expiration time is kept in the ref count map. The queue
 *        simply marks that a key should be checked for expiration when
 *        doDeletions() is invoked. Note that change the expiration interval
 *        associated with the entry races with its removal, so it's possible an
 *        entry will be expired sooner than it should.
 *
 *      - When obliterateIdleEntries() goes through the queue and sees that
 *        an entry has a ref count of zero and is expired, it will atomically
 *        set the ref count to -1, conditionally on it being zero. This prevents
 *        a ref() operation from racing with the expiration of a key::
 *
 *          - if ref() wins, obliterateIdleEntries() will fail to set the count
 *            to -1 and will *not* expire the key.
 *
 *          - ref() will, in turn, increment the ref-count conditionally on
 *            it being larger than -1. If obliterateIdleEntries() wins the race
 *            and the count turns out to be -1, we spin and recursively retry the
 *            operation. This will cause ref() to eventually find that the entry
 *            has been deleted and it will be treated as a new one. Because of
 *            this, obliterateIdleEntries() calls into the reducer before
 *            removing the key from the map. Only afterwards can a ref() succeed,
 *            guaranteeing the happens-before relationship described above.
 */
trait TimedExpirationMap[K <: AnyRef, V >: Null] {
    def putAndRef(key: K, value: V): V
    def putIfAbsentAndRef(key: K, value: V): Int
    def get(key: K): V
    def fold[U](seed: U, func: Reducer[K, V, U]): U
    def ref(key: K): V
    def refAndGetCount(key: K): Int
    def refCount(key: K): Int
    def unref(key: K, currentTimeMillis: Long): V
    def obliterateIdleEntries[U](currentTimeMillis: Long): Unit
    def obliterateIdleEntries[U](currentTimeMillis: Long, seed: U,
                                 reducer: Reducer[K, V, U]): U
}

final class OnHeapTimedExpirationMap[K <: AnyRef, V >: Null]
    (log: Logger, expirationFor: K => Duration) extends TimedExpirationMap[K, V] {

    case class Metadata(var value: V, refCount: AtomicInteger, var expiration: Long)

    private val refCountMap = new ConcurrentHashMap[K, Metadata]()
    private def logger = log.wrapper

    /*
     * Track entries that need to be deleted and the time at which they
     * should be deleted.
     *
     * A queue provides traversal using insertion order. Expiring entries means
     * iterating the queue until an non-expired time is found. When an entry is
     * taken from the queue, the canonical up-to-date expiration time is found
     * in the ref count map. The queue is just a flag to say "check this entry,
     * it's probably expired".
     *
     * An entry will only be present in this queue if it is also present
     * in the refCountMap.
     */
    private val expiring = new ConcurrentHashMap[Long, ConcurrentLinkedQueue[(K, Long)]]()

    private def tryIncIfGreaterThan(atomic: AtomicInteger, threshold: Int): Int = {
        do {
            val i = atomic.get
            if (i > threshold) {
                if (atomic.compareAndSet(i, i + 1)) {
                    return i + 1
                }
            } else {
                return -1
            }
        } while (true)
        -1 // not reached
    }

    private def insert(key: K, v: V) = {
        val metadata = Metadata(v, new PaddedAtomicInteger(1), Long.MaxValue)
        val old = refCountMap.putIfAbsent(key, metadata)
        if (old eq null)
            logger.debug(log.marker, s"Incrementing reference count of $key to 1")
        old
    }

    @tailrec
    override def putAndRef(key: K, value: V): V =
        refCountMap.get(key) match {
            case m@Metadata(oldValue, count, _) =>
                if (ref(key) != null) {
                    m.value = value
                    oldValue
                } else {
                    /* Retry, a deletion raced with us and won */
                    putAndRef(key, value)
                }
            case _ if insert(key, value) eq null => null
            case _ => putAndRef(key, value)
        }

    @tailrec
    override def putIfAbsentAndRef(key: K, value: V): Int =
        refCountMap.get(key) match {
            case m: Metadata =>
                val count = refAndGetCount(key)
                if (count != 0) {
                    count
                } else {
                    /* Retry, a deletion raced with us and won */
                    putIfAbsentAndRef(key, value)
                }
            case _ if insert(key, value) eq null => 1
            case _ => putIfAbsentAndRef(key, value)
        }

    override def get(key: K): V = {
        val metadata = refCountMap.get(key)
        if ((metadata eq null) || metadata.refCount.get == -1) null
        else metadata.value
    }

    override def fold[U](seed: U, func: Reducer[K, V, U]): U = {
        var acc = seed
        val it = refCountMap.entrySet().iterator()
        while (it.hasNext) {
            val e = it.next()
            acc = func.apply(acc, e.getKey, e.getValue.value)
        }
        acc
    }

    override def ref(key: K): V =
        refCountMap.get(key) match {
            case m@Metadata(oldValue, count, _) =>
                val newCount = tryIncIfGreaterThan(count, -1)
                if (newCount == -1) {
                    null
                } else {
                    logger.debug(log.marker,
                                 s"Incrementing ref count of $key to $newCount")
                    if (newCount == 1)
                        logger.debug(log.marker, s"Unscheduling removal of $key")
                    oldValue
                }

            case _ => null
        }

    override def refAndGetCount(key: K): Int =
        refCountMap.get(key) match {
            case m@Metadata(oldValue, count, _) =>
                val newCount = tryIncIfGreaterThan(count, -1)
                if (newCount == -1) {
                    0
                } else {
                    logger.debug(log.marker,
                                 s"Incrementing ref count of $key to $newCount")
                    if (newCount == 1)
                        logger.debug(log.marker, s"Unscheduling removal of $key")
                    newCount
                }
            case _ => 0
        }

    override def refCount(key: K) = {
        refCountMap.get(key) match {
            case null => 0
            case metadata => metadata.refCount.get()
        }
    }

    override def unref(key: K, currentTimeMillis: Long): V =
        refCountMap.get(key) match {
            case null =>
                null

            case m@Metadata(value, count, _) if count.get <= 0 =>
                logger.error(log.marker, s"Decrement a ref count past 0 for $key")
                value

            case m@Metadata(value, count, _) =>
                val newVal = count.decrementAndGet()
                logger.debug(log.marker,
                             s"Decrementing reference count of $key to $newVal")

                if (newVal == 0) {
                    logger.debug(log.marker, s"Scheduling removal of $key")
                    val expirationPeriod = expirationFor(key).toMillis
                    m.expiration = currentTimeMillis + expirationPeriod
                    getExpirationQueueFor(expirationPeriod).offer((key, m.expiration))
                } else if (newVal < 0) {
                    logger.warn(log.marker,
                                s"Decrement a ref count past 0 for $key")
                    count.incrementAndGet()
                }

                value
        }

    private def getExpirationQueueFor(expirationPeriod: Long) = {
        var expirationQ = expiring.get(expirationPeriod)
        if (expirationQ eq null) {
            expirationQ = new ConcurrentLinkedQueue[(K, Long)]()
            val oldQ = expiring.putIfAbsent(expirationPeriod, expirationQ)
            if (oldQ ne null)
                expirationQ = oldQ
        }
        expirationQ
    }


    /**
     * Cleans up resources that have had their reference count at 0 for longer
     * than the configured expiration.
     *
     * WARNING: This method is not thread-safe for multiple callers.
     */

    val identityReducer = new Reducer[K, V, Unit] {
        override def apply(acc: Unit, key: K, value: V): Unit = ()
    }

    override def obliterateIdleEntries[U](currentTimeMillis: Long): Unit =
        obliterateIdleEntries(currentTimeMillis, (), identityReducer)

    override def obliterateIdleEntries[U](currentTimeMillis: Long, seed: U,
                                          reducer: Reducer[K, V, U]): U = {
        var acc = seed
        val it = expiring.elements()
        while (it.hasMoreElements) {
            acc = obliterateIdleEntries(
                it.nextElement(), currentTimeMillis, acc, reducer)
        }
        acc
    }

    private def obliterateIdleEntries[U](
            expirationQ: ConcurrentLinkedQueue[(K, Long)],
            currentTimeMillis: Long,
            seed: U,
            reducer: Reducer[K, V, U]): U = {
        var acc = seed
        while (true) {
            val pair = expirationQ.peek()
            if ((pair eq null) || (pair._2 > currentTimeMillis))
                return acc

            val metadata = refCountMap.get(pair._1)

            if (metadata != null &&
                metadata.expiration <= currentTimeMillis &&
                metadata.refCount.compareAndSet(0, -1)) {

                logger.debug(log.marker, s"Forgetting entry ${pair._1}")
                /* The following operations are precisely ordered as explained
                 * in the header. */
                acc = reducer(acc, pair._1, metadata.value)
                refCountMap.remove(pair._1)
            }
            expirationQ.poll()
        }
        acc
    }
}

object OffHeapTimedExpirationMap {
    var loaded = false
    def loadNativeLibrary() = synchronized {
        if (!loaded) {
            System.loadLibrary("nativeTimedExpirationMap")
            loaded = true
        }
    }
}

final class OffHeapTimedExpirationMap[K <: AnyRef, V >: Null]
    (log: Logger,
     expirationFor: K => Duration,
     serializeKey: K => Array[Byte],
     deserializeKey: Array[Byte] => K,
     serializeValue: V => Array[Byte],
     deserializeValue: Array[Byte] => V
     ) extends TimedExpirationMap[K, V] {

    OffHeapTimedExpirationMap.loadNativeLibrary()
    val native = new NativeTimedExpirationMap()
    val pointer = native.create()

    override def putAndRef(key: K, value: V): V = {
        deserializeValue(native.putAndRef(
            pointer, serializeKey(key), serializeValue(value)))
    }

    override def putIfAbsentAndRef(key: K, value: V): Int =
        native.putIfAbsentAndRef(
            pointer, serializeKey(key), serializeValue(value))

    override def get(key: K): V =
        deserializeValue(native.get(pointer, serializeKey(key)))

    override def fold[U](seed: U, func: Reducer[K, V, U]): U = {
        val iterator = native.iterator(pointer)
        var ret = seed
        try {
            while (!native.iteratorAtEnd(iterator)) {
                val key = deserializeKey(native.iteratorCurKey(iterator));
                val value = deserializeValue(
                    native.iteratorCurValue(iterator))
                ret = func(ret, key, value)
                native.iteratorNext(iterator)
            }
        } finally {
            native.iteratorClose(pointer)
        }
        ret
    }
    override def ref(key: K): V =
        deserializeValue(native.ref(pointer, serializeKey(key)))

    override def refAndGetCount(key: K): Int =
        native.refAndGetCount(pointer, serializeKey(key))
    override def refCount(key: K): Int =
        native.refCount(pointer, serializeKey(key))

    override def unref(key: K, currentTimeMillis: Long): V = {
        val value = native.unref(pointer, serializeKey(key),
                                 currentTimeMillis)
        deserializeValue(value)
    }

    def obliterateIdleEntries[U](currentTimeMillis: Long): Unit = {}
    def obliterateIdleEntries[U](currentTimeMillis: Long, seed: U,
                                 reducer: Reducer[K, V, U]): U = seed

}
