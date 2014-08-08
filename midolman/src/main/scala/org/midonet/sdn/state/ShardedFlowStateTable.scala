/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.sdn.state

import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

import akka.event.{NoLogging, LoggingAdapter}
import com.yammer.metrics.core.Clock

import org.midonet.util.concurrent.ConcurrentRefAccountant
import org.midonet.util.collection.Reducer

object ShardedFlowStateTable {
    def create[K <: IdleExpiration, V >: Null](): ShardedFlowStateTable[K, V] =
            new ShardedFlowStateTable[K, V]()

    def create[K <: IdleExpiration, V >: Null](clock: Clock):
            ShardedFlowStateTable[K, V] = new ShardedFlowStateTable[K, V](clock)
}

/**
 * A sharded per-flow state table.
 *
 * It aggregates the data contain in a number of children shards, its goal is
 * to allow for single-writer / multiple-reader synchronization semantics across
 * the aggregated table, by assigning ownership of different shards to different
 * threads.
 *
 * THREADING SEMANTICS:
 *
 * Puts on a shard do not touch other shards. So two writes on the same key
 * performed on different shards result in undefined behaviour. Clients should
 * distribute shards among threads in a way that results in no keyspace overlap.
 *
 * Gets, on the other hand, will fall back to the parent and the other shards
 * if a key is not found locally.
 *
 * unref() calls may require coordination, but they are meant to happen in an
 * external thread or pool, not a shard-owning thread.
 */
class ShardedFlowStateTable[K <: IdleExpiration, V >: Null]
        (val clock: Clock = Clock.defaultClock()) extends FlowStateTable[K, V] {

    private val shards = new ArrayList[FlowStateShard]()
    private val SHARD_NONE: Int = -1


    def addShard(log: LoggingAdapter = NoLogging) = {
        val s: FlowStateShard = new FlowStateShard(shards.size, log)
        shards.add(s)
        s
    }

    /**
     * Fetches a the value associated with a key, skipping the given shard
     * index.
     */
    private[state] def get(key: K, shardToSkip: Int): V = {
        assert(shardToSkip == SHARD_NONE ||
                (shardToSkip >= 0 && shardToSkip < shards.size))

        var i = 0
        while (i < shards.size) {
            if (i != shardToSkip) {
                val v: V = shards.get(i).shallowGet(key)
                if (v != null)
                    return v
            }
            i += 1
        }
        null
    }

    override def putAndRef(key: K, value: V): V = throw new IllegalArgumentException

    override def get(key: K): V = get(key, SHARD_NONE)

    override def ref(key: K): V = {
        var i = 0
        while (i < shards.size) {
            val v = shards.get(i).ref(key)
            if (v != null)
                return v
            i += 1
        }
        null
    }

    override def touch(key: K, value: V) {
        var i: Int = 0
        while (i < shards.size) {
            shards.get(i).touch(key, value)
            i += 1
        }
    }

    override def getRefCount(key: K): Int = {
        var count = 0
        var i = 0
        while (i < shards.size) {
            count += shards.get(i).getRefCount(key)
            i += 1
        }
        count
    }

    override def unref(key: K) {
        var i: Int = 0
        while (i < shards.size) {
            val v: V = shards.get(i).shallowGet(key)
            if (v != null)
                shards.get(i).unref(key)
            i += 1
        }
    }

    override def fold[U](acc: U, func: Reducer[K, V, U]): U = {
        var i = 0
        var seed = acc
        while (i < shards.size) {
            seed = shards.get(i).fold(seed, func)
            i += 1
        }
        seed
    }

    override def expireIdleEntries[U](acc: U, func: Reducer[K, V, U]): U = {
        var i = 0
        var seed = acc
        while (i < shards.size) {
            seed = shards.get(i).expireIdleEntries(seed, func)
            i += 1
        }
        seed
    }

    override def expireIdleEntries() {
        var i = 0
        while (i < shards.size) {
            shards.get(i).expireIdleEntries()
            i += 1
        }
    }

    /**
     * A shard within a ShardedFlowStateTable.
     *
     * It stores entries locally but forwards queries to the parent table for
     * aggregation. Reference counting is also delegated on the parent.
     */
    class FlowStateShard(val workerId: Int, override val log: LoggingAdapter)
            extends ConcurrentRefAccountant[K, V] with FlowStateTable[K, V] {

        private val data = new ConcurrentHashMap[K, V]()

        override def newRefCb(k: K, v: V) = ()

        override def deletedRefCb(k: K) = data.remove(k)

        override def expirationMillis(k: K) = k.expiresAfter().toMillis

        override def putAndRef(key: K, value: V): V = {
            val oldV = get(key)
            incRefCount(key, value)
            /* modifying data like this is not racy. ConcurrentRefAccountant
             * would place its call to newRefCb in the same place as this put()
             * ends up being. The difference is that we want putAndRef to
             * overwrite a pre-existing value, and ConcurrentRefAccountant
             * does not include that case in its contract. */
            data.put(key, value)
            oldV
        }

        override def get(key: K) = {
            val v = data.get(key)
            if (v != null)
                v
            else
                ShardedFlowStateTable.this.get(key, workerId)
        }

        def shallowGet(key: K): V = data.get(key)

        override def ref(key: K): V = {
            val v = data.get(key)
            if (v != null)
                incRefCount(key, v)
            v
        }

        override def getRefCount(key: K): Int = super.getRefCount(key)

        override def touch(key: K, value: V) {
            putAndRef(key, value)
            unref(key)
        }

        private def tickMillis = clock.tick / 1000000

        override def unref(key: K) = decRefCount(key, tickMillis)

        override def fold[U](seed: U, func: Reducer[K, V, U]): U = {
            var acc = seed
            val it = data.entrySet().iterator()
            while (it.hasNext) {
                val e = it.next()
                acc = func.apply(acc, e.getKey, e.getValue)
            }
            acc
        }

        override def expireIdleEntries() = doDeletions(tickMillis)

        override def expireIdleEntries[U](seed: U, func: Reducer[K, V, U]): U =
            doDeletionsAndFold(tickMillis,
                               seed,
                               (acc: U, k: K) => func.apply(acc, k, data.get(k)))
    }
}

