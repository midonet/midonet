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

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.util.UUIDUtil
import org.midonet.conf.HostIdGenerator

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

class ZoomPortBinder(storage: Storage, stateStorage: StateStorage,
                     lockFactory: ZookeeperLockFactory) extends PortBinder {

    override def bindPort(portId: UUID, hostId: UUID,
                          deviceName: String): Unit = {
        val tx = storage.transaction()
        val oldPort = tx.get(classOf[Port], portId)
        val newPort = oldPort.toBuilder
                             .setHostId(UUIDUtil.toProto(hostId))
                             .setInterfaceName(deviceName)
                             .build()
        tx.update(newPort)
        tx.commit()
    }

    override def unbindPort(portId: UUID, hostId: UUID): Unit = {
        val pHostId = UUIDUtil.toProto(hostId)

        val tx = storage.transaction()
        val oldPort = tx.get(classOf[Port], portId)

        // Unbind only if the port is currently bound to an interface on
        // the same host.  This is necessary since in Nova, bind on the
        // new host happens before unbind on live migration, and we don't
        // want remove the existing binding on the new host.
        if (oldPort.hasHostId && oldPort.getHostId == pHostId) {
            val newPort = oldPort.toBuilder
                                 .clearHostId()
                                 .clearInterfaceName()
                                 .setPreviousHostId(pHostId)
                                 .build()
            tx.update(newPort)
            tx.commit()
        }
    }

}
