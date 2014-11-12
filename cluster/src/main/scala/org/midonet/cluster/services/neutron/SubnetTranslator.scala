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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Neutron.NeutronSubnet
import org.midonet.cluster.services.c3po.{MidoModelOp, C3POOp}

class SubnetTranslator(storage: ReadOnlyStorage)
    extends NeutronApiTranslator(classOf[NeutronSubnet], storage) {

    /**
     * Converts an operation on an external model into 1 or more corresponding
     * operations on internal MidoNet models.
     */
    override def toMido(op: C3POOp[NeutronSubnet])
    : List[MidoModelOp[_ <: Object]] = {
        // TODO: Everything.
        List()
    }
}
