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

import org.midonet.brain.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronTunnelZone
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.util.concurrent.toFutureOps

/**
 * Translates NeutronTunnelZone to MidoNet's equivalent.
 */
class TunnelZoneTranslator(val storage: ReadOnlyStorage)
extends NeutronTranslator[NeutronTunnelZone] {
    override protected def translateCreate(nTz: NeutronTunnelZone)
    : MidoOpList = {
        List(Create(translateTunnelZone(nTz).build()))
    }

    protected override def translateUpdate(nTz: NeutronTunnelZone)
    : MidoOpList = {
        val oldTz = storage.get(classOf[TunnelZone], nTz.getId).await()

        // Need to copy the host-IP mappings from the old Tunnel Zone.
        List(Create(translateTunnelZone(nTz)
                    .addAllHosts(oldTz.getHostsList)
                    .build()))
    }

    protected override def translateDelete(tzId: UUID): MidoOpList = {
        List(Delete(classOf[TunnelZone], tzId))
    }

    /**
     * Translates NeutronTunnelZone to MidoNet TunnelZone.
     */
    def translateTunnelZone(nTz: NeutronTunnelZone): TunnelZone.Builder = {
        val tzType = nTz.getType match {
            case NeutronTunnelZone.Type.GRE => TunnelZone.Type.GRE
            case NeutronTunnelZone.Type.VXLAN => TunnelZone.Type.VXLAN
            case NeutronTunnelZone.Type.VTEP => TunnelZone.Type.VTEP
        }
        TunnelZone.newBuilder()
                  .setId(nTz.getId)
                  .setName(nTz.getName)
                  .setType(tzType)
    }
}