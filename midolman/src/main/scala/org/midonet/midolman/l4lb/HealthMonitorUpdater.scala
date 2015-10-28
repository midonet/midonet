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

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.NonFatal

import org.slf4j.LoggerFactory

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.data.storage._
import org.midonet.cluster.data.util.ZkOpLock
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.util.concurrent.toFutureOps

/**
 * This class allows to perform an update to storage under mutual exclusion by
 * acquiring a ZooKeeper lock. It also offers the ability to change the status
 * of a pool.
 */
// TODO: Move this functionality to the cluster and make it more generic
//       (mna-1054).
class HealthMonitorUpdater(store: Storage, lockFactory: ZookeeperLockFactory) {

    private def log = LoggerFactory.getLogger("org.midonet.l4lb")

    private final val lockOpNumber = new AtomicInteger(1)

    private[l4lb] def setPoolMappingError(poolId: UUID): Unit = {
        setPoolMappingStatus(poolId, ERROR)
    }

    private[l4lb] def setPoolMappingActive(poolId: UUID,
                                           lock: ZkOpLock = null): Unit = {
        setPoolMappingStatus(poolId, ACTIVE, lock)
    }

    private[l4lb] def setPoolMappingInactive(poolId: UUID): Unit = {
        setPoolMappingStatus(poolId, INACTIVE)
    }

    private def setPoolMappingStatus(poolId: UUID,
        status: PoolHealthMonitorMappingStatus, lock: ZkOpLock = null): Unit = {

        if (lock != null) {
            val pool = store.get(classOf[Pool], poolId).await()
            store.update(pool.toBuilder.setMappingStatus(status).build())
        } else {
            writeWithLock({
                val pool = store.get(classOf[Pool], poolId).await()
                store.update(pool.toBuilder.setMappingStatus(status).build())
            }, poolId)
        }
    }

    private[l4lb] def writeWithLock(f: => Unit, poolId: UUID): Unit = {
        val lock = new ZkOpLock(lockFactory, lockOpNumber.getAndIncrement,
                                ZookeeperLockFactory.ZOOM_TOPOLOGY)
        try lock.acquire() catch {
            case NonFatal(e) =>
                log.info("Could not acquire exclusive write access to " +
                         "storage.", e)
                throw e
        }

        try {
            return f
        } catch {
            case NonFatal(e) =>
                log.info("Updating the topology related to pool: {} failed " +
                         " exception: {}", Array(poolId, e.getMessage):_*)
        } finally {
            lock.release()
        }
    }

    /**
      * Acquires a ZooKeeper lock and returns the lock if the lock
      * was granted to us. Otherwise, a [[RuntimeException]] is thrown.
      * Do not forget to release the lock once you do not need it anymore.
      */
    private[l4lb] def acquireLock(): ZkOpLock = {
        val lock = new ZkOpLock(lockFactory, lockOpNumber.getAndIncrement,
                                ZookeeperLockFactory.ZOOM_TOPOLOGY)
        try lock.acquire() catch {
            case NonFatal(e) =>
                log.info("Could not acquire exclusive write access to " +
                         "storage.", e)
                throw e
        }
        lock
    }
}
