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

package org.midonet.brain.services.c3po.translators

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

        TunnelZone.newBuilder().setId(config.getId)
                               .setName("DEFAULT")
                               .setType(tzType).build()
    }

    /**
     * Looks up all the tunnel zone and returns the first Tunnel Zone that is
     * not VTEP. There must exist exactly one such Tunnel Zone by spec.
     */
    def getNeutronDefaultTunnelZone(storage: ReadOnlyStorage): TunnelZone = {
        val tzs = storage.getAll(classOf[TunnelZone]).await()
        if (tzs.isEmpty)
            throw new RuntimeException("No tunnel zone's been configured.")
        val nonVtep = tzs.filter(_.getType != TunnelZone.Type.VTEP)
        if (nonVtep.length > 1)
            throw new RuntimeException(
                    "Multiple non-VTEP tunnel zones. Cannot find default.")

        return nonVtep(0)
    }

}