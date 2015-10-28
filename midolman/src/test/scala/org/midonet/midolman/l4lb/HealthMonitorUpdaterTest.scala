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

import scala.concurrent.Future

import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex
import org.mockito.{Matchers, Mockito}
import org.scalatest.{BeforeAndAfter, FlatSpec}

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.util.UUIDUtil

class HealthMonitorUpdaterTest extends FlatSpec with BeforeAndAfter {

    var backend: Storage = _
    var lockFactory: ZookeeperLockFactory = _
    var hmUpdater: HealthMonitorUpdater = _
    var poolId: UUID = _

    before {
        backend = Mockito.mock(classOf[Storage])
        lockFactory = Mockito.mock(classOf[ZookeeperLockFactory])
        hmUpdater = new HealthMonitorUpdater(backend, lockFactory)
        poolId = UUID.randomUUID()
    }

    private def getMutexMockAndSetReturnValues(lockFactory: ZookeeperLockFactory,
                                               acquireSuccessful: Boolean)
    : InterProcessSemaphoreMutex = {
        val mutex = Mockito.mock(classOf[InterProcessSemaphoreMutex])
        Mockito.when(lockFactory.createShared(ZookeeperLockFactory.ZOOM_TOPOLOGY))
               .thenReturn(mutex)
        Mockito.when(mutex.acquire(Matchers.anyLong(), Matchers.anyObject()))
               .thenReturn(acquireSuccessful)
        mutex
    }

    "writeWithLock" should "acquire and release the lock" in {
        var called = false
        def someFunction(): Unit = {
            called = true
        }
        val mutex = getMutexMockAndSetReturnValues(lockFactory,
                                                   acquireSuccessful = true)

        hmUpdater.writeWithLock(someFunction, poolId)

        /* Specify the order in which methods on the mocks should be called. */
        val order = Mockito.inOrder(lockFactory, mutex, mutex)

        order.verify(lockFactory, Mockito.times(1))
             .createShared(Matchers.anyString())
        order.verify(mutex, Mockito.times(1))
             .acquire(Matchers.anyLong(), Matchers.anyObject())
        assert(called)
        order.verify(mutex, Mockito.times(1)).release()
    }

    "When the lock cannot be acquired then the method" should "not be called" in {
        var called = false
        def someFunction(): Unit = {
            called = true
        }
        val mutex = getMutexMockAndSetReturnValues(lockFactory,
                                                   acquireSuccessful = false)
        hmUpdater.writeWithLock(someFunction, poolId)

        /* Specify the order in which methods on the mocks should be called. */
        val order = Mockito.inOrder(lockFactory, mutex, mutex)

        order.verify(lockFactory, Mockito.times(1))
             .createShared(Matchers.anyString())
        order.verify(mutex, Mockito.times(1))
            .acquire(Matchers.anyLong(), Matchers.anyObject())
        assert(!called)
        order.verify(mutex, Mockito.times(0)).release()
    }

    "setPoolMappingActive/Inactive/Error" should "update the pool in storage" in {
        val poolId = UUID.randomUUID()
        val pool = Pool.newBuilder().setId(UUIDUtil.toProto(poolId)).build()
        val statuses = List(ACTIVE, INACTIVE, ERROR)
        for (status <- statuses) {
            Mockito.reset(backend)

            getMutexMockAndSetReturnValues(lockFactory,
                                           acquireSuccessful = true)
            Mockito.when(backend.get(classOf[Pool], poolId))
                   .thenReturn(Future.successful(pool))

            status match {
                case ACTIVE => hmUpdater.setPoolMappingActive(poolId)
                case INACTIVE => hmUpdater.setPoolMappingInactive(poolId)
                // To avoid a warning
                case _ => hmUpdater.setPoolMappingError(poolId)
            }

            Mockito.verify(backend, Mockito.times(1))
                   .get(classOf[Pool], poolId)
            val updatedPool = pool.toBuilder.setMappingStatus(status).build()
            Mockito.verify(backend, Mockito.times(1))
                   .update(updatedPool)
        }
    }
}
