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

package org.midonet.brain.services.c3po.translators

import org.midonet.brain.services.c3po.C3POStorageManager.Operation
import org.midonet.brain.services.c3po.neutron.NeutronOp
import org.midonet.cluster.models.Neutron.NeutronPort

class PortTranslator extends NeutronTranslator[NeutronPort] {

    /** Translate the operation on NeutronModel to a list of operations applied
      * to a different model that represent the complete translation of the
      * first to the latter. */
    override def translate(op: NeutronOp[NeutronPort]): List[Operation[_]] = {
        //TODO: Magic goes here.
        List()
    }
}
