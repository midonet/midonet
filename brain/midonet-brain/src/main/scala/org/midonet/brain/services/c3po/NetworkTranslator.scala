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
package org.midonet.brain.services.c3po

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.Network

/** Provides a Neutron model translator for Network. */
class NetworkTranslator extends NeutronTranslator[NeutronNetwork] {

    @throws[TranslationException]
    override def translate(op: neutron.NeutronOp[NeutronNetwork])
    : List[midonet.MidoOp[_]] = try {
        op match {
            case c: neutron.Create[_] => create(c.model.asInstanceOf[NeutronNetwork])
            case u: neutron.Update[_] => update(u.model.asInstanceOf[NeutronNetwork])
            case d: neutron.Delete[_] => delete(d.id)
        }
    } catch {
        case e: Throwable => processExceptions(e, op)
    }

    private def create(n: NeutronNetwork)
    : List[midonet.MidoOp[_]] = List(midonet.Create(translate(n)))

    private def update(n: NeutronNetwork)
    : List[midonet.MidoOp[_]] = List(midonet.Update(translate(n)))

    private def delete(id: Commons.UUID)
    : List[midonet.MidoOp[_]] = List(midonet.Delete(classOf[Network], id))

    @inline
    def translate(network: NeutronNetwork) = Network.newBuilder()
        .setId(network.getId)
        .setTenantId(network.getTenantId)
        .setName(network.getName)
        .setAdminStateUp(network.getAdminStateUp)
        .build

}
