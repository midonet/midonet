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

import java.util.{HashMap, Map => JMap}

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.services.c3po.OpType.{Create, OpType}
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.neutron.NetworkTranslator

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

    override def translate[T](op: OpType, inputModel: T) {
        val modelClass: Class[_ <: T] = inputModel.getClass
        if (!apiTranslators.containsKey(modelClass)) {
            log.error(s"C3PO cannot understand ${modelClass}.")
            return
        }

        val translator: ApiTranslator[T] =
                apiTranslators.get(modelClass).asInstanceOf[ApiTranslator[T]]
        val midoOps = translator.toMido(op, inputModel)

        // TODO Use multi() instead.
        for (midoOp <- midoOps) {
            midoOp.op match {
                case Create => storage.create(midoOp.midoModel)
                case _ => 
            }
        }
    }
}