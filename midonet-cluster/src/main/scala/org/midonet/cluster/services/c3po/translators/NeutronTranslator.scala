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

package org.midonet.cluster.services.c3po.translators

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import com.google.protobuf.Message

import org.slf4j.LoggerFactory

import org.midonet.cluster.c3poNeutronTranslatorLog
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.services.c3po.C3POStorageManager.Operation
import org.midonet.cluster.services.c3po.midonet.MidoOp
import org.midonet.cluster.services.c3po.neutron.NeutronOp
import org.midonet.cluster.services.c3po.{midonet, neutron}

/** Defines a class that is able to translate from an operation on the Neutron
  * model to a set of operations on the MidoNet model. */
trait NeutronTranslator[NeutronModel <: Message] {

    protected type MidoOpList = List[MidoOp[_ <: Message]]
    protected type MidoOpListBuffer = ListBuffer[MidoOp[_ <: Message]]

    protected val log =
        LoggerFactory.getLogger(c3poNeutronTranslatorLog(getClass))

    /**
     * Translate the Neutron operation on NeutronModel to a list of MidoNet
     * operations that:
     * - maintain (if necessary) the original model,
     * - and translate the model into a corresponding representation made up of
     * various MidoNet models.
     */
    @throws[TranslationException]
    def translateNeutronOp(op: NeutronOp[NeutronModel]): List[Operation] =
        retainNeutronModel(op) ++ translate(op)

    /* Keep the original model as is by default. Override if the model does not
     * need to be maintained, or need some special handling. */
    protected def retainNeutronModel(op: NeutronOp[NeutronModel])
    : List[MidoOp[NeutronModel]] = {
        op match {
            case neutron.Create(nm) => List(midonet.Create(nm))
            case neutron.Update(nm) => List(midonet.Update(nm))
            case neutron.Delete(clazz, id) => List(midonet.Delete(clazz, id))
        }
    }

    def translate(op: NeutronOp[NeutronModel]): List[Operation] = try {
        op match {
            case neutron.Create(nm) => translateCreate(nm)
            case neutron.Update(nm) => translateUpdate(nm)
            case neutron.Delete(_, id) => translateDelete(id)
        }
    } catch {
        case NonFatal(ex) =>
            throw new TranslationException(op, ex, ex.getMessage)
    }

    /* Implement the following for CREATE/UPDATE/DELETE of the model */
    protected def translateCreate(nm: NeutronModel): MidoOpList
    protected def translateUpdate(nm: NeutronModel): MidoOpList
    protected def translateDelete(id: UUID): MidoOpList
}

/** Thrown by by implementations when they fail to perform the requested
  * operation on the source model. */
class TranslationException(val op: neutron.NeutronOp[_],
                           val cause: Throwable = null, val msg: String = null)
    extends RuntimeException (s"Failed to $op; $msg", cause)
