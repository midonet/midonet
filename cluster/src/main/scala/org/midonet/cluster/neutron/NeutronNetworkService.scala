/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.cluster.neutron

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Devices.Network
import org.midonet.cluster.models.Neutron

/**
 * Defines the conversion logic.
 */
object NeutronNetworkService {
    implicit def toMido(network: Neutron.NeutronNetwork): Network = {
       Network.newBuilder()
              .setId(network.getId)
              .setTenantId(network.getTenantId)
              .setName(network.getName)
              .setAdminStateUp(network.getAdminStateUp)
              .build
    }
}

/**
 * Provides Neutron Network CRUD service.
 */
class NeutronNetworkService(val storage: Storage) {
    import NeutronNetworkService.toMido
    val log = LoggerFactory.getLogger(classOf[NeutronNetworkService])

    def create(network: Neutron.NeutronNetwork) = {
        // TODO Ensures the provider router if the network is external
        // TODO Creates and persists a new tunnel key ID
        // TODO Updates the tunnel key ID node with the data of the Network ID.
        // TODO Creates a tunnel key ID
        storage.create(toMido(network))
    }

    /**
     * Updates the Neutron Network. It's essentially idempotent; it will just
     * update the storage with the same data twice.
     */
    def update(network: Neutron.NeutronNetwork) = {
        // TODO Update the external network.
        storage.update(toMido(network))
    }

    /**
     * Deletes the Neutron Network. The delete operation is idempotent. That is,
     * deleting a non-existing network returns silently.
     */
    def delete(networkId: Commons.UUID) = {
        try {
            // TODO Update the external network. To do that, need to look up the
            // Mido Network data first.
            storage.delete(classOf[Network], networkId)
        } catch {
            case _: NotFoundException =>
                log.info("Deleting a non-existent neutron network with UUID = "
                         + s"${networkId}")
            case e: Throwable => throw e
        }
    }
}