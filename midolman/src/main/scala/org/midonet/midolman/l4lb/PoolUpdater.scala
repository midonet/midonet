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
import java.util.concurrent.atomic.AtomicLong
import java.util.{ConcurrentModificationException, UUID}

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import akka.actor.{ActorContext, ActorSystem, ActorRef}
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.{Storage, StorageException, ObjectExistsException, NotFoundException}
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus
import org.midonet.util.concurrent.toFutureOps

object PoolUpdater {
    case class RetryUpdatePoolStatus[T](poolId: UUID,
                                        status: PoolHealthMonitorMappingStatus,
                                        attempt: Int, lastPoolActivation: Long)
}

/**
 * This class allows to reliably update a pool by re-issuing the update when
 * a [[ConcurrentModificationException]] is thrown.
 */
// TODO: Move this functionality to the cluster and make it more generic.
class PoolUpdater(store: Storage, maxRetries: Int) {

    import PoolUpdater._

    private def log = LoggerFactory.getLogger("org.midonet.l4lb")

    private var sleepTime = 0L

    /* For each pool id, the last time the pool was updated. */
    private val lastPoolUpdateTSMap = new mutable.HashMap[UUID, Long]()

    private[l4lb] def setPoolMappingStatus(poolId: UUID,
        status: PoolHealthMonitorMappingStatus, attempt: Int, actor: ActorRef,
        context: ActorContext)
    : Unit = {

        lastPoolUpdateTSMap.synchronized {
            try {
                val pool = store.get(classOf[Pool], poolId).await()
                store.update(pool.toBuilder.setMappingStatus(status).build())
                lastPoolUpdateTSMap.put(poolId, System.nanoTime())
            } catch {
                case e: ConcurrentModificationException =>
                    if (attempt < maxRetries) {
                        sleepTime += Random.nextInt(100).toLong
                        val lastPoolUpdate = lastPoolUpdateTSMap(poolId)
                        val delay = new FiniteDuration(sleepTime,
                                                       TimeUnit.MILLISECONDS)
                        val msg = RetryUpdatePoolStatus(poolId, status,
                                                        attempt + 1,
                                                        lastPoolUpdate)
                        context.system.scheduler
                               .scheduleOnce(delay, actor, msg)(context.dispatcher)
                    } else {
                        log.warn("Write operation to Zoom failed after {} " +
                                 "attempts", Int.box(attempt))
                    }
                case e: NotFoundException =>
                    log.warn("Missing data", e)
                case e: ObjectExistsException =>
                    log.warn("Tried to overwrite existing data", e)
                case e: StorageException =>
                    log.error("Unexpected storage error", e)
            }
        }
    }

    /* TODO: Move this functionality to Zoom. We could have a method
         that allows to get an object with its version v and another
         one to modify an object only if its current version is v. */
    private[l4lb] def retryUpdateIfValid(poolId: UUID,
                                         status: PoolHealthMonitorMappingStatus,
                                         ts: Long, attempt: Int,
                                         actor: ActorRef, context: ActorContext)
    : Boolean = {
        lastPoolUpdateTSMap.synchronized {
            if (lastPoolUpdateTSMap(poolId) == ts) {
                setPoolMappingStatus(poolId, status, attempt, actor, context)
                true
            } else {
                false
            }
        }
    }
}
