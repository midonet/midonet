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
package org.midonet.cluster.services.neutron

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.services.c3po.{C3POCreate, C3PODelete, C3POOp, C3POUpdate, MidoCreate, MidoDelete, MidoUpdate, NetworkConverter, TranslationException}

/**
 * Provides a Neutron model translator for Network.
 */
class NetworkTranslator(override val storage: ReadOnlyStorage)
        extends NeutronApiTranslator[NeutronNetwork](
                classOf[NeutronNetwork], classOf[Network], storage) {
    val log = LoggerFactory.getLogger(classOf[NetworkTranslator])

    @throws[TranslationException]
    override def toMido(op: C3POOp[NeutronNetwork]) = {
        try {
            op match {
                case c: C3POCreate[_] =>
                    List(MidoCreate(NetworkConverter.toMido(c.model)))
                case u: C3POUpdate[_] =>
                    List(MidoUpdate(NetworkConverter.toMido(u.model)))
                case d: C3PODelete[_] =>
                    List(MidoDelete(classOf[Network], d.id))
            }
        } catch {
            case e: Exception =>
                processExceptions(e, op.opType)
        }
    }
}
