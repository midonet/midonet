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

import scala.util.control.NonFatal

import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.services.rest_api.BindingApiService.log

class PortBinder(storage: Storage, stateStorage: StateStorage) {

    def bindPort(portId: UUID, hostId: UUID, deviceName: String): Unit = {
        val protoHostId = hostId.asProto
        log debug s"Binding port $portId"
        try storage.tryTransaction { tx =>
            val oldPort = tx.get(classOf[Port], portId)
            val newPortBldr = oldPort.toBuilder
                .setHostId(protoHostId)
                .setInterfaceName(deviceName)
            // If binding from a different host before unbinding,
            // update previous host. This is necessary since in Nova, bind
            // on the new host happens before unbind on live migration, and
            // we need to update the previous host to know where to fetch the
            // flow state from.
            if (oldPort.hasHostId && (oldPort.getHostId != protoHostId)) {
                newPortBldr.setPreviousHostId(oldPort.getHostId)
            }
            tx.update(newPortBldr.build)
        } catch {
            case NonFatal(e) =>
                log.error(s"Unable to bind port $portId to host $hostId", e)
                throw e
        }
    }

    def unbindPort(portId: UUID, hostId: UUID): Unit = {
        val pHostId = hostId.asProto

        log debug s"Unbinding port $portId"
        try storage.tryTransaction { tx =>
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
            }
        } catch {
            case NonFatal(e) =>
                log.error(s"Unable to unbind port $portId from host $hostId", e)
                throw e
        }
    }

}
