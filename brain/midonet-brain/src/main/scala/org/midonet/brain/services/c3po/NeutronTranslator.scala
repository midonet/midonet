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

import com.google.protobuf.Message

import org.midonet.brain.services.c3po.C3POStorageManager.Operation
import org.midonet.brain.services.c3po.midonet.MidoOp
import org.midonet.brain.services.c3po.neutron.NeutronOp
import org.midonet.cluster.models.Commons

/** Defines a class that is able to translate from an operation on the Neutron
  * model to a set of operations on the MidoNet model. */
trait NeutronTranslator[NeutronModel <: Message] {

    /** Translate the operation on NeutronModel to a list of operations applied
      * to a different model that represent the complete translation of the
      * first to the latter. */
    @throws[TranslationException]
    def translate(op: neutron.NeutronOp[NeutronModel]): List[Operation[_]]

    /** Unified exception handling. */
    protected def processExceptions(e: Throwable, op: neutron.NeutronOp[_]) = {
        throw new TranslationException(op, e)
    }

}

/** Thrown by by implementations when they fail to perform the requested
  * operation on the source model. */
class TranslationException(val op: neutron.NeutronOp[_],
                           val cause: Throwable = null, val msg: String = "")
    extends RuntimeException (s"Failed to $op; $msg", cause)
