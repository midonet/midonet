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

import java.util.{HashMap, Map => JMap}

import org.slf4j.LoggerFactory

import org.midonet.brain.services.c3po.OpType.{Create, Delete, OpType, Update}
import org.midonet.brain.services.neutron.NetworkTranslator
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.data.storage.{CreateOp, DeleteOp, UpdateOp}
import org.midonet.cluster.data.storage.PersistenceOp
import org.midonet.cluster.models.Neutron.NeutronNetwork

/**
 * C3PO that translates an operation on an external model into corresponding
 * storage operations on internal Mido models.
 */
class StorageC3PO(val storage: Storage) extends C3PO {
    val log = LoggerFactory.getLogger(classOf[StorageC3PO])
    private val apiTranslators = new HashMap[Class[_], ApiTranslator[_]]()

    def registerTranslators(translators: JMap[Class[_], ApiTranslator[_]]) {
        apiTranslators.putAll(translators)
    }

    @throws[TranslationException]
    override def interpretAndExec[T](op: C3POOp[T]) {
        val modelClass = op match {
            case c: ModelCreate[_] => c.model.getClass
            case u: ModelUpdate[_] => u.model.getClass
            case d: ModelDelete[_] => d.clazz
        }
        if (!apiTranslators.containsKey(modelClass)) {
            throw new TranslationException(
                    op.opType, modelClass, s"No translator for ${modelClass}.")
        }

        val translator: ApiTranslator[T] =
                apiTranslators.get(modelClass).asInstanceOf[ApiTranslator[T]]
        val midoOps = translator.toMido(op)
        //storage.multi(translator.toMido(op, inputModel).map { midoOp =>
        //    midoOp.op match {
        //        case Create => CreateOp(midoOp.midoModel)
        //        case Update => UpdateOp(midoOp.midoModel)
        //        case Delete => null
        //            // TODO Cannot handle properly now. Need to turn the Enum
        //            // to case classes similar to PersistentOp.
        //    }
        //})

        // TODO Use multi() instead.
        for (midoOp <- midoOps) {
            midoOp match {
                case c: MidoCreate[_] => storage.create(c.model)
                case _ =>
            }
        }
    }
}