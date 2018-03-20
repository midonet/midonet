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

import org.midonet.cluster.data.ZoomMetadata.ZoomOwner
import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.Neutron.HostPortBinding
import org.midonet.cluster.util.UUIDUtil
import org.midonet.services.rest_api.BindingApiService.log

class PortBinder(storage: Storage, stateStorage: StateStorage) {

    def bindPort(portId: UUID, hostId: UUID, interfaceName: String): Unit = {
        log debug s"Binding port $portId at $hostId with $interfaceName"


        val bindingId = UUIDUtil.mix(UUIDUtil.toProto(hostId),
                                     UUIDUtil.toProto(portId))

        val binding = HostPortBinding.newBuilder
            .setId(bindingId)
            .setInterfaceName(interfaceName)
            .build

        try storage.tryTransaction(ZoomOwner.AgentBinding) { tx =>
            if (tx.exists(classOf[HostPortBinding], bindingId)) {
                tx.update(binding)
            } else {
                tx.create(binding)
            }
        } catch {
            case NonFatal(e) =>
                log.error(s"Unable to bind port $portId to host $hostId", e)
                throw e
        }
    }

    def unbindPort(portId: UUID, hostId: UUID): Unit = {
        log debug s"Unbinding port $portId at $hostId"


        val bindingId = UUIDUtil.mix(UUIDUtil.toProto(hostId),
                                     UUIDUtil.toProto(portId))

        try storage.tryTransaction(ZoomOwner.AgentBinding) { tx =>
            tx.delete(classOf[HostPortBinding], bindingId)
        } catch {
            case NonFatal(e) =>
                log.error(s"Unable to unbind port $portId to host $hostId", e)
                throw e
        }
    }
}
