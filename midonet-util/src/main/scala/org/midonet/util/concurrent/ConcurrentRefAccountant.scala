/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util.concurrent

import java.util.concurrent.{ConcurrentLinkedQueue, ConcurrentHashMap}
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

import akka.event.LoggingAdapter

/**
 * A generic concurrent registry of reference counts. Keeps track of refCounts
 * to resources and expires them. All operations are thread safe and
 * non-blocking. With the caveat that doDeletions is not reentrant.
 *
 * Notes on thread safety:
 *
 * incRefCount() and decRefCount() update the reference count for a key. When
 * a reference to a key is created for the first time incRefCount will invoke
 * the newRefCb. Symmetrically doDeletions() will invoke deletedRefCb() when
 * a key is finally expired.
 *
 * IMPORTANT: Calls to newRefCb and deletedCallsCb will have a happens-before
 * relationship among them. Implementations of the callbacks must preserve
 * this happens-before relationship, meaning that any side effects that
 * newRefCb must be seen by deletedRefCb if called for the same key one after
 * the other. Again, this class guarantees that deletedRefCb will not be called
 * until newRefCb has been called and has returned.
 *
 * Synchronization is lock-less, and this is how the possible races are
 * prevented, most are trivial but explained for completeness:
 *
 *   + At reference creation time
 *      - Two incRefCount() calls could race with each other. This is avoided
 *        by using putIfAbsent() to write to the ref-count map. The loser of
 *        the race will retry the operation through a recursive call.
 *
 *      - decRefCount() is a no-op in this case.
 *
 *   + At reference expiration time
 *      - When decRefCount decrements a count to zero, it will calculate the
 *        expiration time and put it in the expiring queue. Because the counts
 *        could vary (to one and back to zero) in the expiration interval, the
 *        canonical expiration time is kept in the ref count map. The queue
 *        simply marks that a key should be checked or expiration when
 *        doDeletions() is invoked.
 *
 *      - When doDeletions goes through the queue and sees that an entry has
 *        a ref count of zero and is expired, it will atomically set the ref
 *        count to -1, conditional to it being zero. This prevents incRefCount
 *        from racing with doDeletions:
 *
 *          - if incRefCount wins, doDeletions will fail to set the count to -1
 *            and will *not* expire the key.
 *
 *          - incRefCount will in turn, increment the ref-count conditional to
 *            it being larger than -1. If doDeletions wins the race and
 *            the count turns out to be -1 one, the operation is retried
 *            recursively. This will cause incRefCount to eventually find that
 *            the entry has been deleted and it will be treated as a new
 *            ref. Because of this, doDeletion must call deletedRefCb() before
 *            removing the key from the ref count map, it is only then that the
 *            incRefCount retry will succeed, guaranteeing a happens-before
 *            relationship.
 */
abstract class ConcurrentRefAccountant[K, V]() {

    protected def log: LoggingAdapter
    protected def newRefCb(k: K, v: V)
    protected def deletedRefCb(k: K)
    protected def expirationMillis(k: K): Long

    case class Metadata(refCount: AtomicInteger, var expiration: Long)

    private val refCountMap = new ConcurrentHashMap[K, Metadata]()

    /*
     * Track entries that need to be deleted and the time at which they
     * should be deleted.
     *
     * A queue provides traversal using insertion order. Expiring entries means
     * iterating the queue until an non-expired time is found. When an entry is
     * taken from the queue, the canonical up-to-date expiration time is found
     * in the ref count map. The queue is just a flag to say "check this entry
     * it's probably expired".
     *
     * An entry will only be present this queue if it is also present
     * in the refCountMap.
     */
    private val expiring = new ConcurrentLinkedQueue[(K, Long)]

    private def incrementIfGreaterThanAndGet(atomic: AtomicInteger, value: Int): Int = {
        while (true) {
            val i = atomic.get()
            if (i <= value)
                return i
            if (atomic.compareAndSet(i, i+1))
                return i+1
        }
        atomic.get // not reached
    }

    @tailrec
    final def incRefCount(key: K, v: V): Unit =
        refCountMap.get(key) match {
            case m@Metadata(count, _) if count != null =>
                val newValue = incrementIfGreaterThanAndGet(count, -1)

                if (newValue == -1) {
                    /* Retry, a deletion raced with us and won */
                    incRefCount(key, v)
                } else {
                    log.debug("Incrementing ref count of {} to {}", key, newValue)
                    m.expiration = Long.MaxValue
                    if (newValue == 1)
                        log.debug("Unscheduling removal of {}", key)
                }

            case _ =>
                val count = new AtomicInteger(1)
                val was = refCountMap.putIfAbsent(key, Metadata(count, Long.MaxValue))
                if (was ne null) {
                    incRefCount(key, v)
                } else {
                    log.debug("Incrementing reference count of {} to 1", key)
                    newRefCb(key, v)
                }
        }

    def getRefCount(key: K) = {
        refCountMap.get(key) match {
            case metadata if metadata eq null => 0
            case metadata => metadata.refCount.get()
        }
    }

    def decRefCount(key: K, currentTime: Long): Unit =
        refCountMap.get(key) match {
            case metadata if metadata eq null =>
                log.error("Decrement ref count for unlearned entry: {}", key)

            case m@Metadata(count, _) if count.get <= 0 =>
                log.error("Decrement a ref count past {} for {}", count.get, key)

            case m@Metadata(count, _) =>
                val newVal = count.decrementAndGet()
                log.debug("Decrementing reference count of {} to {}", key, newVal)

                if (newVal == 0) {
                    log.debug("Scheduling removal of {} ", key)
                    m.expiration = currentTime + expirationMillis(key)
                    expiring.offer((key, currentTime + expirationMillis(key)))
                } else if (newVal < 0) {
                    log.error("Decrement a ref count past {} for {}", count.get, key)
                    count.incrementAndGet()
                }
        }


    /**
     * Cleans up resources that have had their reference count at 0 for longer
     * than expirationMillis.
     *
     * WARNING: This method is not reentrant.
     */
    def doDeletions(currentTime: Long) = doDeletionsAndFold(currentTime, (), (acc: Unit, k: K) => ())

    def doDeletionsAndFold[U](currentTime: Long, seed: U, func: (U, K) => U): U = {
        var acc = seed
        while (true) {
            val pair = expiring.peek()
            if ((pair eq null) || (pair._2 > currentTime))
                return acc

            val metadata = refCountMap.get(pair._1)

            if (metadata != null &&
                metadata.expiration <= currentTime &&
                metadata.refCount.compareAndSet(0, -1)) {

                log.debug("Forgetting entry {} ", pair._1)
                acc = func(acc, pair._1)
                deletedRefCb(pair._1)
                /* removal from refCountMap must happen after
                 * the removal callback, so that a thread doing ref()
                 * orders its operations correctly with regards to the
                 * removals thread */
                refCountMap.remove(pair._1)
            }
            expiring.poll()
        }
        acc
    }
}
