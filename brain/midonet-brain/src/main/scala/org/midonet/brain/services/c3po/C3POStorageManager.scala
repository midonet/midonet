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
import java.util.{UUID => JUUID}

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.{CreateOp, DeleteOp, UpdateOp}
import org.midonet.cluster.data.storage.{Storage, StorageException}
import org.midonet.cluster.models.C3PO.{C3POStorageManager => StorageManagerState}
import org.midonet.cluster.services.c3po.{ApiTranslator, TranslationException}
import org.midonet.cluster.services.c3po.{C3PODataManager, C3PODataManagerException}
import org.midonet.cluster.services.c3po.{C3POCreate, C3PODelete, C3POOp, C3POTask, C3POUpdate}
import org.midonet.cluster.services.c3po.{MidoCreate, MidoDelete, MidoUpdate}
import org.midonet.cluster.util.UUIDUtil.toProto

object C3POStorageManager {
    /* All C3POStorageManager workers access the same last processed C3PO task
     * ID information. This ID is used when persisting that information as C3PO
     * data in the Storage.
     */
    private[c3po] val commonId = toProto(new JUUID(0, 1))

    /* A utility method to generate a C3POStorageManager Proto holding the last
     * processed C3PO task ID.
     */
    private[c3po] def storageManagerState(lastProcessed: Int) =
        StorageManagerState.newBuilder
                           .setId(commonId)
                           .setLastProcessedTaskId(lastProcessed)
                           .build
}

/**
 * C3PO that translates an operation on an external model into corresponding
 * storage operations on internal Mido models.
 */
class C3POStorageManager(val storage: Storage) extends C3PODataManager {
    import C3POStorageManager.commonId
    import C3POStorageManager.storageManagerState
    val log = LoggerFactory.getLogger(classOf[C3POStorageManager])

    private val apiTranslators = new HashMap[Class[_], ApiTranslator[_]]()
    private var initialized = false

    def registerTranslators(translators: JMap[Class[_], ApiTranslator[_]])  = {
        apiTranslators.putAll(translators)
    }

    def clearTranslators() = apiTranslators.clear()

    def init() {
        try {
            if (! storage.exists(classOf[StorageManagerState], commonId)
                    .value.get.get) {
                storage.create(storageManagerState(0))
            }
        } catch {
            case se: StorageException =>
                throw new C3PODataManagerException(
                        "Failure initializing C3PODataManager.", se)
        }
        initialized = true
    }

    /**
     * Returns the last processed C3PO task ID.
     */
    @throws[C3PODataManagerException]
    override def lastProcessedC3POTaskId(): Int = {
        assert(initialized)
        try {
            storage.get(classOf[StorageManagerState], commonId)
                   .value.get.get.getLastProcessedTaskId
        } catch {
            case se: StorageException =>
                    throw new C3PODataManagerException(
                            "Failure in looking up the last processed C3PO ID.",
                            se)
        }
    }

    @throws[C3PODataManagerException]
    override def flushTopology() {
        try {
            storage.flush()
        } catch {
            case se: StorageException =>
                    throw new C3PODataManagerException(
                            "Failure in flushing the storage", se)
        }
    }

    @throws[C3PODataManagerException]
    override def interpretAndExec[T](task: C3POTask[T]) =
        interpretAndExecTxn("", List(task.asInstanceOf[C3POTask[Object]]))

    /* This method is NOT idemponent on DELETE.
     * TODO Implement idempotent DELETE.
     */
    @throws[C3PODataManagerException]
    override def interpretAndExecTxn(txnId: String,
                                     tasks: List[C3POTask[Object]]) {
        assert(initialized)
        try {
            val newState = storageManagerState(tasks.last.taskId)
            val midoOps = tasks.map { task =>
                val modelClass = task.op match {
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
                              .toMido(task.op)
                              .map { midoOp =>
                                   midoOp match {
                                       case c: MidoCreate[_] => CreateOp(c.model)
                                       case u: MidoUpdate[_] => UpdateOp(u.model)
                                       case d: MidoDelete[_] => DeleteOp(d.clazz,
                                                                         d.id)
                                   }
                              }
            }.flatten ++ List(UpdateOp(newState))

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
        }
    }
}