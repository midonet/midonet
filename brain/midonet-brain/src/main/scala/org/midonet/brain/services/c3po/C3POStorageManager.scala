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

import java.util.concurrent.TimeUnit
import java.util.{HashMap => JHashMap, Map => JMap, UUID => JUUID}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.{ObjectExistsException, Storage, StorageException, UpdateOp}
import org.midonet.cluster.models.C3PO.StorageManagerState
import org.midonet.cluster.services.c3po.{ApiTranslator, C3POCreate, C3PODataManager, C3PODataManagerException, C3PODelete, C3POTask, C3POTransaction, C3POUpdate, TranslationException}
import org.midonet.cluster.util.UUIDUtil.toProto

object C3POStorageManager {
    /* A constant indicating how many seconds C3POStorageManager waits on the
     * future returned from Storage. 10 seconds for now, which we think is
     * sufficient.
     */
    private val FUTURE_TIMEOUT = Duration.create(10, TimeUnit.SECONDS)

    /* C3PO.StorageManagerState is a container Protobuf message for storing the
     * last processed C3PO task ID in Storage, which is used to tell the next
     * cluster node where to pick up if this one fails. It is needed because the
     * Storage service only accepts objects with an ID property, and this
     * "stateId" is used to access that common state object in Storage.
     */
    private[c3po] val stateId = toProto(new JUUID(0, 1))

    /* A utility method to generate a C3POStorageManager Proto holding the last
     * processed C3PO task ID.
     */
    private[c3po] def storageManagerState(lastProcessed: Int) =
        StorageManagerState.newBuilder
                           .setId(stateId)
                           .setLastProcessedTaskId(lastProcessed)
                           .build

    private def await[T](f: Future[T]) = Await.result(f, FUTURE_TIMEOUT)
}

/**
 * C3PO that translates an operation on an external model into corresponding
 * storage operations on internal Mido models.
 */
class C3POStorageManager(val storage: Storage) extends C3PODataManager {
    import C3POStorageManager._
    val log = LoggerFactory.getLogger(classOf[C3POStorageManager])

    private val apiTranslators = new JHashMap[Class[_], ApiTranslator[_]]()
    private var initialized = false

    def registerTranslator[T <: Object](clazz: Class[T],
                                        translator: ApiTranslator[T]): Unit = {
        apiTranslators.put(clazz, translator)
    }

    def registerTranslators(translators: JMap[Class[_], ApiTranslator[_]]) = {
        apiTranslators.putAll(translators)
    }

    def clearTranslators(): Unit = apiTranslators.clear()

    def init(): Unit = {
        try {
            storage.create(storageManagerState(0))
            log.info("Initialized last processed task ID to 0.")
        } catch {
            case _: ObjectExistsException =>
                log.info(s"Found last processed task ID: $lastProcessed")
            case e: Throwable =>
                    throw new C3PODataManagerException(
                            "Failure initializing C3PODataManager.", e)
        }
        initialized = true
    }

    /**
     * Returns the last processed C3PO task ID.
     */
    @throws[C3PODataManagerException]
    override def lastProcessedC3POTaskId: Int = {
        assert(initialized)
        lastProcessed
    }

    private def lastProcessed: Int = {
        try {
            await(storage.get(classOf[StorageManagerState], stateId))
                    .getLastProcessedTaskId
        } catch {
            case e: Throwable =>
                    throw new C3PODataManagerException(
                            "Failure in looking up the last processed C3PO ID.",
                            e)
        }
    }

    @throws[C3PODataManagerException]
    override def flushTopology() {
        try {
            storage.flush()
        } catch {
            case e: Throwable =>
                    throw new C3PODataManagerException(
                        "Failure in flushing the storage.", e)
        }
    }

    /* This method is NOT idemponent on DELETE.
     * TODO Implement idempotent DELETE.
     */
    @throws[C3PODataManagerException]
    override def interpretAndExecTxn(txn: C3POTransaction) {
        assert(initialized)
        try {
            val newState = storageManagerState(txn.lastTaskId)
            val midoOps = txn.tasks.flatMap { task =>
                translateC3POOpsToPersistenceOps(
                        task.asInstanceOf[C3POTask[Object]])
            } ++ List(UpdateOp(newState))

            storage.multi(midoOps)
            log.info(s"Executed a C3PO transaction with ID: ${txn.txnId}.")
        } catch {
            case te: TranslationException =>
                    throw new C3PODataManagerException(
                            "Failure in translating for a transaction " +
                            s"${txn.txnId}", te)
            case se: StorageException =>
                    throw new C3PODataManagerException(
                            "Failure in persisting for a transaction " +
                            s"${txn.txnId}", se)
            case e: Throwable =>
                    throw new C3PODataManagerException(
                            "Failure in executing a transaction", e)
        }
    }

    @throws[C3PODataManagerException]
    private def translateC3POOpsToPersistenceOps[T <: Object](
            task: C3POTask[T]) = {
        val modelClass = task.op match {
            case c: C3POCreate[T] => c.model.getClass
            case u: C3POUpdate[T] => u.model.getClass
            case d: C3PODelete[T] => d.clazz
        }
        if (!apiTranslators.containsKey(modelClass)) {
            throw new C3PODataManagerException (
                    s"No translator for $modelClass.", null)
        }

        Seq(task.op.toPersistenceOp) ++  // Persists the original model
                apiTranslators.get(modelClass)
                              .asInstanceOf[ApiTranslator[T]]
                              .toMido(task.op)
                              .map { midoOp => midoOp.toPersistenceOp }
    }
}