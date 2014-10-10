/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.sdn.state

import java.util.ArrayList

import com.typesafe.scalalogging.Logger
import com.yammer.metrics.core.Clock

import org.midonet.util.concurrent.TimedExpirationMap
import org.midonet.util.collection.Reducer
import org.slf4j.LoggerFactory

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

    private val defaultLogger =
        Logger(LoggerFactory.getLogger("org.midonet.state.table"))

    def addShard(log: Logger = defaultLogger) = {
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
    class FlowStateShard(workerId: Int, log: Logger) extends FlowStateTable[K, V] {
        private val map = new TimedExpirationMap[K, V](log, _.expiresAfter)

        override def putAndRef(key: K, value: V): V =
            map.putAndRef(key, value)

        override def get(key: K) = {
            val v = map.get(key)
            if (v != null)
                v
            else
                ShardedFlowStateTable.this.get(key, workerId)
        }

        def shallowGet(key: K): V =
            map.get(key)

        override def ref(key: K): V =
            map.ref(key)

        override def getRefCount(key: K): Int =
            map.getRefCount(key)

        override def touch(key: K, value: V): Unit = {
            putAndRef(key, value)
            unref(key)
        }

        private def tickMillis = clock.tick / 1000000

        override def unref(key: K) =
            map.unref(key, tickMillis)

        override def fold[U](seed: U, func: Reducer[K, V, U]): U =
            map.fold(seed, func)

        override def expireIdleEntries() =
            map.obliterateIdleEntries(tickMillis)

        override def expireIdleEntries[U](seed: U, func: Reducer[K, V, U]): U =
            map.obliterateIdleEntries(tickMillis, seed, func)
    }
}

