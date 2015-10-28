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

import java.util.{ConcurrentModificationException, UUID}

import scala.collection.mutable

import akka.actor.ActorRef
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.{Storage, StorageException, ObjectExistsException, NotFoundException}
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus
import org.midonet.util.concurrent.toFutureOps

/**
 * This object allows classes in this package to re-issue
 * writes to Zoom when a [[ConcurrentModificationException]] is thrown.
 */
// TODO: Move this functionality to the cluster and make it more generic.
object BackendUtils {

    private def log = LoggerFactory.getLogger("org.midonet.l4lb")

    case class RetryUpdatePoolStatus[T](poolId: UUID,
                                        status: PoolHealthMonitorMappingStatus,
                                        attempt: Int, lastPoolActivation: Long)

    /* For each pool id, the last time the pool was activated. */
    private val lastPoolUpdateTSMap = new mutable.HashMap[UUID, Long]()

    private[l4lb] def setPoolMappingStatus(poolId: UUID,
        status: PoolHealthMonitorMappingStatus, attempt: Int, maxRetries: Int,
        store: Storage, actor: ActorRef): Unit = {

        this.synchronized {
            val op = {
                val pool = store.get(classOf[Pool], poolId).await()
                store.update(pool.toBuilder.setMappingStatus(status).build())
                lastPoolUpdateTSMap.put(poolId, System.nanoTime())
            }
            val onRetry = {
                if (attempt < maxRetries) {
                    val lastPoolUpdate = lastPoolUpdateTSMap(poolId)
                    actor ! RetryUpdatePoolStatus(poolId, status, attempt + 1,
                                                  lastPoolUpdate)
                } else {
                    log.warn("Write operation to Zoom failed after {} attempts",
                             Int.box(attempt))
                }
            }
            retryOnContention(op, onRetry)
        }
    }

    /* TODO: Move this functionality to Zoom. We could have a method
         that allows to get an object with its version v and another
         one to modify an object only if its current version is v. */
    private[l4lb] def retryIfValid(poolId: UUID,
                                   status: PoolHealthMonitorMappingStatus,
                                   ts: Long, attempt: Int, maxRetries: Int,
                                   store: Storage, actor: ActorRef): Boolean = {
        this.synchronized {
            if (lastPoolUpdateTSMap(poolId) == ts) {
                setPoolMappingStatus(poolId, status, attempt, maxRetries, store,
                                     actor)
                true
            } else {
                false
            }
        }
    }

    private def retryOnContention(op: => Unit, onRetry: => Unit): Unit = {
        try {
            op
        } catch {
            case e: ConcurrentModificationException =>
                onRetry
            case e: NotFoundException =>
                log.warn("Missing data", e)
            case e: ObjectExistsException =>
                log.warn("Tried to overwrite existing data", e)
            case e: StorageException =>
                log.error("Unexpected storage error", e)
        }
    }
}
