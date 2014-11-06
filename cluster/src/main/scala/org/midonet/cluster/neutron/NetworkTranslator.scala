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

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.services.c3po.MidoModelOp
import org.midonet.cluster.services.c3po.NetworkConverter
import org.midonet.cluster.services.c3po.OpType.OpType
import org.midonet.cluster.services.c3po.TranslationException

/**
 * Provides a Neutron model translator for Network.
 */
class NetworkTranslator(override val storage: Storage)
        extends NeutronApiTranslator[NeutronNetwork](
                classOf[NeutronNetwork], classOf[Network], storage) {
    val log = LoggerFactory.getLogger(classOf[NetworkTranslator])

    @throws[TranslationException]
    override def toMido(op: OpType, neutronModel: NeutronNetwork) = {
        try {
            List(MidoModelOp(op, NetworkConverter.toMido(neutronModel)))
        } catch {
            case e: Exception =>
                processExceptions(e, op)
        }
    }
}
