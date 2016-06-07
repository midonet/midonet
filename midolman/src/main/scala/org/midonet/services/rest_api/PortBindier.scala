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

package org.midonet.services.rest_api

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.NonFatal

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.data.util.ZkOpLock
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.util.UUIDUtil
import org.midonet.conf.HostIdGenerator
import org.midonet.util.concurrent.toFutureOps

trait PortBinder {

    def bindPort(portId: UUID, hostId: UUID, deviceName: String)
    def unbindPort(portId: UUID, hostId: UUID)

    def bindPort(portId: UUID, deviceName: String): Unit = {
        bindPort(portId, HostIdGenerator.getHostId, deviceName)
    }

    def unbindPort(portId: UUID): Unit = {
        unbindPort(portId, HostIdGenerator.getHostId)
    }
}

class ZoomPortBinder(storage: Storage,
                     lockFactory: ZookeeperLockFactory) extends PortBinder {

    import RestApiService.Log
    private val lockOpNumber = new AtomicInteger(0)

    private def getPortBuilder(portId: UUID): Topology.Port.Builder =
        storage.get(classOf[Topology.Port], portId).await().toBuilder

    def tryWrite(f: => Unit): Unit = {
        val lock = new ZkOpLock(lockFactory, lockOpNumber.getAndIncrement,
                                ZookeeperLockFactory.ZOOM_TOPOLOGY)
        try lock.acquire() catch {
            case NonFatal(e) =>
                Log.info("Could not acquire exclusive write access to " +
                         "storage.", e)
                throw e
        }
        try {
            f
        }
        finally {
            lock.release()
        }
    }

    override def bindPort(portId: UUID, hostId: UUID,
                          deviceName: String): Unit = {
        tryWrite {
            val p = getPortBuilder(portId)
                        .setHostId(UUIDUtil.toProto(hostId))
                        .setInterfaceName(deviceName)
                        .build()
            storage.update(p)
        }
    }

    override def unbindPort(portId: UUID, hostId: UUID): Unit = {
        val pHostId = UUIDUtil.toProto(hostId)

        tryWrite {
            val p = storage.get(classOf[Port], portId).await()

            // Unbind only if the port is currently bound to an interface on
            // the same host.  This is necessary since in Nova, bind on the
            // new host happens before unbind on live migration, and we don't
            // want remove the existing binding on the new host.
            if (p.hasHostId && pHostId == p.getHostId) {
                storage.update(p.toBuilder
                                .clearHostId()
                                .clearInterfaceName()
                                .build())
            }
        }
    }
}
