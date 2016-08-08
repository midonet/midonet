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
import org.midonet.util.ImmediateRetriable

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
                     lockFactory: ZookeeperLockFactory) extends PortBinder
                                                        with ImmediateRetriable {

    import RestApiService.log

    override def maxRetries = 3

    override def bindPort(portId: UUID, hostId: UUID,
                          deviceName: String): Unit = {
        val update = retry(log.underlying, s"Binding port $portId") {
            val tx = storage.transaction()
            val oldPort = tx.get(classOf[Port], portId)
            val newPortBldr = oldPort.toBuilder
                .setHostId(UUIDUtil.toProto(hostId))
                .setInterfaceName(deviceName)
            // If binding from a different host before unbinding,
            // update previous host. This is necessary since in Nova, bind
            // on the new host happens before unbind on live migration, and
            // we need to update the previous host to know where to fetch the
            // flow state from.
            if (oldPort.hasHostId && oldPort.getHostId != hostId) {
                newPortBldr.setPreviousHostId(oldPort.getHostId)
            }
            tx.update(newPortBldr.build)
            tx.commit()
        }

        if (update.isLeft) {
            val e = update.left.get
            log.error(s"Unable to bind port $portId to host $hostId", e)
            throw e
        }
    }

    override def unbindPort(portId: UUID, hostId: UUID): Unit = {
        val pHostId = UUIDUtil.toProto(hostId)

        val update = retry(log.underlying, s"Unbinding port $portId") {
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

        if (update.isLeft) {
            val e = update.left.get
            log.error(
                s"Unable to unbind port $portId from host $hostId", e)
            throw e
        }
    }

}
