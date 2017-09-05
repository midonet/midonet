/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.topology_cache

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, ScheduledExecutorService}

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}

import org.midonet.cluster.cache.{ObjectCache, StateCache}
import org.midonet.cluster.services.endpoint.comm.HttpByteBufferProvider
import org.midonet.cluster.topology.snapshot._
import org.midonet.util.functors.makeRunnable
import org.midonet.util.logging.Logger

import io.netty.buffer.{ByteBuf, Unpooled}

final class SnapshotInProgress extends Exception

class TopologySnapshotProvider(objectCache: ObjectCache,
                               stateCache: StateCache,
                               executor: ScheduledExecutorService,
                               log: Logger)
    extends HttpByteBufferProvider {

    implicit private val ec = ExecutionContext.fromExecutor(executor)

    private[topology_cache] val refs = new AtomicInteger(0)

    private[topology_cache] val pendingRequests =
        new ConcurrentLinkedQueue[Promise[ByteBuf]]()

    @volatile
    private[topology_cache] var outstandingRequests = 0

    @volatile
    private[topology_cache] var outstandingRequestsDone: Promise[Unit] = _

    private[topology_cache] val serializedTopology =
        new Array[Byte](100 * 1024 * 1024)

    @volatile
    private[topology_cache] var serializedLength: Int = _

    override def getAndRef(): Future[ByteBuf] = {
        getAndRefRec()
    }

    @tailrec
    private def getAndRefRec(): Future[ByteBuf] = {
        val promise = Promise[ByteBuf]()
        val currentRefs = refs.get()
        if (currentRefs == -1) {
            log.debug(s"getAndRef: Snapshot in progress, enqueue request " +
                      s"(${pendingRequests.size() + 1} pending requests).")
            pendingRequests add promise
            promise.future
        } else {
            if (refs.compareAndSet(currentRefs, currentRefs + 1)) {
                log.debug("getAndRef: Snapshot ready, complete promise.")
                val buf = Unpooled.wrappedBuffer(serializedTopology, 0,
                                                 serializedLength)
                promise.success(buf)
                promise.future
            } else {
                log.debug("getAndRef: Unref method raced with us, retry.")
                getAndRefRec()
            }
        }
    }

    override def unref(): Unit = {
        unrefRec()
    }

    @tailrec
    private def unrefRec(): Unit = {
        val currentRefs = refs.get()
        if (currentRefs == -1) {
            synchronized {
                outstandingRequests -= 1
                log.debug("Unref: snapshot in progress, decrement outstanding " +
                          s"requests to $outstandingRequests.")
                if (outstandingRequests <= 0)
                    outstandingRequestsDone.trySuccess()
            }
        } else {
            if (!refs.compareAndSet(currentRefs, currentRefs - 1)) {
                log.debug(s"Unref: Snapshot ready, but refAndGet raced with " +
                          s"us, retry.")
                unrefRec()
            } else {
                log.debug(s"Unref: Snapshot ready, decrement refs to " +
                          s"${currentRefs - 1}.")
            }
        }
    }

    private def awaitOutstandingRequests(): Future[Unit] = synchronized {
        // Flag serialization, enqueueing pending requests and wait for
        // outstanding requests to finish
        outstandingRequests = refs.getAndSet(-1)
        if (outstandingRequests > 0) {
            log.debug(s"Awaiting $outstandingRequests outstanding requests " +
                      s"currently in progress before encoding the snapshot.")
            outstandingRequestsDone = Promise[Unit]()
            outstandingRequestsDone.future
        } else if (outstandingRequests < 0) {
            log.debug("Snapshot in progress, skipping snapshot encoding.")
            Future.failed(new SnapshotInProgress)
        } else {
            log.debug("No outstanding requests in progress, ready to encode " +
                      "the snapshot.")
            Future.successful()
        }
    }

    private def notifyPendingRequests(): Unit = synchronized {
        // Unflag serialization and complete pending requests
        refs.set(0)
        log.debug(s"Notifying ${pendingRequests.size()} pending requests " +
                  s"that the snapshot is ready.")
        val requests = pendingRequests.iterator()
        while (requests.hasNext) {
            val request = requests.next()
            serializedTopology.synchronized {
                val buf = Unpooled.wrappedBuffer(
                    serializedTopology, 0, serializedLength)
                request.success(buf)
            }
        }
    }


    /**
      * Periodic task to refresh the topology snapshot. No requests may
      * be served while a snapshot is in progress to avoid corrupted data.
      * @return
      */
    val snapshot: Runnable = makeRunnable {
        log.debug("Starting topology snapshot request.")
        val mark1 = System.nanoTime()
        val objectSnaphot = objectCache.snapshot()
        val stateSnapshot = stateCache.snapshot()
        log.debug(
            "Topology snapshot request finished successfully in " +
            s"${(System.nanoTime() - mark1) / 1000000} ms. " +
            s"Encoding ...")

        // NOTE: this future is assumed to finish before the
        // next scheduled snapshot. Not safe otherwise.
        for { _ <- awaitOutstandingRequests() } yield {
            val mark2 = System.nanoTime()
            val snapshot = TopologySnapshot(
                objectSnaphot, stateSnapshot)
            val topologySerializer = new TopologySnapshotSerializer
            serializedTopology.synchronized {
                serializedLength = topologySerializer.serialize(
                    serializedTopology, snapshot)
            }

            notifyPendingRequests()

            log.debug("Topology snapshot serialization finished. " +
                      s"Serialization of $serializedLength bytes took " +
                      s"${(System.nanoTime() - mark2) / 1000000} ms. " +
                      s"Complete request finished in " +
                      s"${(System.nanoTime() - mark1) / 1000000} ms.")
        }
    }
}
