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

package org.midonet.cluster.services.c3po.translators

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronConfig
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.util.concurrent.toFutureOps

/**
 * Contains custom logic specific to Tunnel Zones in one place.
 */
trait TunnelZoneManager {
    def neutronDefaultTunnelZone(config: NeutronConfig) : TunnelZone = {
        // Create the singleton Neutron default Tunnel Zone
        val tzType = config.getTunnelProtocol match {
            case NeutronConfig.TunnelProtocol.GRE => TunnelZone.Type.GRE
            case NeutronConfig.TunnelProtocol.VXLAN => TunnelZone.Type.VXLAN
        }

        TunnelZone.newBuilder().setId(getTunnelZoneId(config))
                               .setName("DEFAULT")
                               .setType(tzType)
                               .build()
    }

    /**
     * Looks up the singleton Neutron Config object and finds the ID of the
     * default Tunnel Zone used by Neutron.
     */
    def getNeutronDefaultTunnelZone(storage: ReadOnlyStorage): TunnelZone = {
        val tzs = storage.getAll(classOf[NeutronConfig]).await()
        if (tzs.length == 0)
            throw new IllegalStateException("Cannot find a Neutron Config.")
        else if (tzs.length > 1)
            throw new IllegalStateException("Found more than 1 Neutron Config.")

        // NeutronConfig.id is used to create a default Tunnel Zone for Neutron.
        storage.get(classOf[TunnelZone], getTunnelZoneId(tzs(0))).await()
    }

    /* We use NeutronConfig ID as Neutron's default Tunnel Zone ID (at least
     * for now)
     */
    @inline
    private def getTunnelZoneId(config: NeutronConfig): UUID = config.getId
}