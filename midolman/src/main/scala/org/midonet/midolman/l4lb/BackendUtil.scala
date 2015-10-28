/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.l4lb

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.{ConcurrentModificationException, UUID}

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.util.Random
import scala.util.control.NonFatal

import akka.actor.ActorContext
import org.slf4j.LoggerFactory

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.data.storage._
import org.midonet.cluster.data.util.ZkOpLock
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus
import org.midonet.util.concurrent.toFutureOps

/**
 * This class allows to reliably update a Zoom topology by re-issuing the update
 * when a [[ConcurrentModificationException]] is thrown. It also allows to
 * perform updates under mutual exclusion by acquiring a ZooKeeper lock.
 */
// TODO: Move this functionality to the cluster and make it more generic
//       (mna-1054).
class BackendUtil(store: Storage, lockFactory: ZookeeperLockFactory,
                  maxRetries: Int, actorContext: ActorContext) {

    private def log = LoggerFactory.getLogger("org.midonet.l4lb")

    private final val lockOpNumber = new AtomicInteger(1)

    private val sleepTime = new AtomicLong(0L)

    /* For each zoom object, the last time the object was updated. */
    private val lastObjectUpdateTS = new TrieMap[(Class[_], UUID), Long]()

    private[l4lb] def setPoolMappingStatus(poolId: UUID,
        status: PoolHealthMonitorMappingStatus): Unit = {

        writeWithLock{
            val pool = store.get(classOf[Pool], poolId).await()
            store.update(pool.toBuilder.setMappingStatus(status).build())
            lastObjectUpdateTS.put((classOf[Pool], poolId), System.nanoTime())
        }
    }

    private[l4lb] def tryWrite[T](f: => Unit, attempt: Int = 1, clazz: Class[T],
                                  id: UUID, ts: Long = 0): Unit = {
        try {
            if (lastObjectUpdateTS.getOrElse((clazz, id), 0l) == ts) {
                f
                lastObjectUpdateTS.put((clazz, id), System.nanoTime())
                return
            }
        } catch {
            case e: ConcurrentModificationException =>
                if (attempt < maxRetries) {
                    sleepTime.set(sleepTime.get + 50 + Random.nextInt(100))
                    val delay = new FiniteDuration(sleepTime.get,
                                                   TimeUnit.MILLISECONDS)
                    actorContext.system.scheduler.scheduleOnce(delay)(
                        tryWrite(f, attempt + 1, clazz, id, ts))(actorContext.dispatcher)
                } else {
                    log.warn("Write operation to storage failed after {} " +
                             "attempts", Int.box(attempt))
                }
            case e: NotFoundException =>
                log.warn("Data to be updated is missing from storage", e)
            case e: ObjectExistsException =>
                log.warn("Tried to overwrite existing data in storage", e)
            case e: StorageException =>
                log.error("Unexpected storage error", e)
        }
    }

    private[l4lb] def writeWithLock(f: => Unit): Unit = {
        val lock = new ZkOpLock(lockFactory, lockOpNumber.getAndIncrement,
                                ZookeeperLockFactory.ZOOM_TOPOLOGY)
        try lock.acquire() catch {
            case NonFatal(e) =>
                log.error("Could not acquire exclusive write access to storage.",
                          e)
                return
        }

        try {
            return f
        } catch {
            case NonFatal(e) =>
                log.warn("Write to storage failed", e)
        } finally {
            lock.release()
        }
    }
}
