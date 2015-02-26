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

import org.midonet.brain.services.c3po.midonet.Create
import org.midonet.brain.services.c3po.neutron
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronConfig
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.util.concurrent.toFutureOps

/** Provides a translator for Neutron Config. */
class ConfigTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronConfig] {

    override protected def translateCreate(c: NeutronConfig): MidoOpList = {

        // Create the singleton Tunnel Zone
        if (!storage.exists(classOf[TunnelZone], c.getId).await()) {
            val tzType = c.getTunnelProtocol match {
                case NeutronConfig.TunnelProtocol.GRE => TunnelZone.Type.GRE
                case NeutronConfig.TunnelProtocol.VXLAN => TunnelZone.Type.VXLAN
                case _ => throw new TranslationException(
                    neutron.Create(c),
                    msg = "Unsupported tunnel protocol type.")
            }

            List(Create(TunnelZone.newBuilder()
                            .setId(c.getId)
                            .setName("DEFAULT")
                            .setType(tzType).build()))
        } else {
            List()
        }
    }

    override protected def translateUpdate(c: NeutronConfig): MidoOpList =
        List()

    override protected def translateDelete(id: UUID): MidoOpList = List()
}
