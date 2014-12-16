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

package org.midonet.cluster.services.c3po

import com.google.protobuf.Message

import org.midonet.cluster.services.c3po.neutron.NeutronOp

/** Defines a class that is able to translate from an operation on the Neutron
  * model to a set of operations on the MidoNet model. */
trait NeutronTranslator[NeutronModel <: Message] {

    /** Translate the operation on NeutronModel to a list of TO operations */
    @throws[TranslationException]
    def translate(op: NeutronOp[NeutronModel]): List[Operation[_]]

    /** Unified exception handling. */
    protected def processExceptions(e: Throwable, op: NeutronOp[_]) = {
        throw new TranslationException(op, e)
    }

}

/** Thrown by by implementations when they fail to perform the requested
  * operation on the source model. */
class TranslationException(val op: NeutronOp[_], val cause: Throwable = null,
                           val msg: String = "")
    extends RuntimeException (s"Failed to $op; $msg", cause)

