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
import org.midonet.cluster.data.storage.{CreateOp, DeleteOp, UpdateOp}
import org.midonet.cluster.data.storage.{Storage, StorageException}
import org.midonet.cluster.services.c3po.{ApiTranslator, TranslationException}
import org.midonet.cluster.services.c3po.{C3PODataManager, C3PODataManagerException, C3POOp}
import org.midonet.cluster.services.c3po.{MidoCreate, MidoDelete, MidoUpdate}
import org.midonet.cluster.services.c3po.{C3POCreate, C3PODelete, C3POUpdate}
import org.midonet.cluster.services.c3po.C3PODataManagerException

/**
 * C3PO that translates an operation on an external model into corresponding
 * storage operations on internal Mido models.
 */
class C3POStorageManager(val storage: Storage) extends C3PODataManager {
    val log = LoggerFactory.getLogger(classOf[C3POStorageManager])

    private val apiTranslators = new HashMap[Class[_], ApiTranslator[_]]()

    def registerTranslators(translators: JMap[Class[_], ApiTranslator[_]])  = {
        apiTranslators.putAll(translators)
    }

    def clearTranslators() = apiTranslators.clear()

    @throws[C3PODataManagerException]
    def flushTopology() {
        throw new NotImplementedError()
    }

    @throws[C3PODataManagerException]
    override def interpretAndExec[T](op: C3POOp[T]) =
        interpretAndExecTxn("", List(op.asInstanceOf[C3POOp[Object]]))

    /* This method is NOT idemponent on DELETE.
     * TODO Implement idempotent DELETE.
     */
    @throws[C3PODataManagerException]
    override def interpretAndExecTxn(txnId: String, ops: List[C3POOp[Object]]) {
        try {
            val midoOps = ops.map { op =>
                val modelClass = op match {
                    case c: C3POCreate[Object] => c.model.getClass
                    case u: C3POUpdate[Object] => u.model.getClass
                    case d: C3PODelete[Object] => d.clazz
                }

                if (!apiTranslators.containsKey(modelClass)) {
                    throw new C3PODataManagerException (
                            s"No translator for ${modelClass}.", null)
                }
                apiTranslators.get(modelClass)
                              .asInstanceOf[ApiTranslator[Object]]
                              .toMido(op)
                              .map { midoOp =>
                                   midoOp match {
                                       case c: MidoCreate[_] => CreateOp(c.model)
                                       case u: MidoUpdate[_] => UpdateOp(u.model)
                                       case d: MidoDelete[_] => DeleteOp(d.clazz,
                                                                         d.id)
                                   }
                              }
            }.flatten

            storage.multi(midoOps)
        } catch {
            case te: TranslationException =>
                    throw new C3PODataManagerException(
                            s"Failure in translating for a transaction $txnId",
                            te)
            case se: StorageException =>
                    throw new C3PODataManagerException(
                            s"Failure in persisting for a transaction $txnId",
                            se)
            case th: Throwable => throw th
        }
    }
}