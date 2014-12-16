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

import com.google.protobuf.Message
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.C3PO.StorageManagerState
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

    /** Defines a types of operations on a single entity. */
    object OpType extends Enumeration {
        type OpType = Value

        val Create = Value(1)
        val Delete = Value(2)
        val Update = Value(3)

        private val ops = Array(Create, Delete, Update)
        def valueOf(i: Int) = ops(i - 1)
    }

    /** A generic operation on a model */
    trait Operation[T <: Message] {
        def opType: OpType.OpType
        def toPersistenceOp: PersistenceOp
    }

    /** A failure occurred when interpreting or executing an operation. */
    class ProcessingFailure(val msg: String = "",
                            val cause: Throwable = null)
        extends RuntimeException("Failed to interpret/execute operation" +
                                 s"${if (msg == null) "" else ": " + msg}",
                                 cause) {
    }

}

/**
 * C3PO that translates an operation on an external model into corresponding
 * storage operations on internal Mido models.
 */
final class C3POStorageManager(private val storage: Storage) {
    import org.midonet.brain.services.c3po.C3POStorageManager._

    private val log = LoggerFactory.getLogger(classOf[C3POStorageManager])

    private val apiTranslators = new JHashMap[Class[_], NeutronTranslator[_]]()
    private var initialized = false

    def registerTranslator[T <: Message](clazz: Class[T],
                                         translator: NeutronTranslator[T])
    : Unit = apiTranslators.put(clazz, translator)

    def registerTranslators(translators: JMap[Class[_], NeutronTranslator[_]])
    : Unit = apiTranslators.putAll(translators)

    def clearTranslators(): Unit = apiTranslators.clear()

    def init(): Unit = try {
        storage.create(storageManagerState(0))
        log.info("Initialized last processed task ID to 0.")
    } catch {
        case _: ObjectExistsException =>
            log.info(s"Found last processed task ID: $lastProcessed")
        case e: Throwable =>
            throw new ProcessingFailure("C3PODataManager initialisation", e)
    } finally {

        initialized = true
    }

    /** Returns the ID of the last Task that was processed. */
    @throws[ProcessingFailure]
    def lastProcessedTaskId: Int = {
        assert(initialized)
        lastProcessed
    }

    private def lastProcessed: Int = {
        try {
            await(storage.get(classOf[StorageManagerState], stateId))
                    .getLastProcessedTaskId
        } catch {
            case e: Throwable =>
                    throw new ProcessingFailure(
                        "When looking up last processed task ID.", e)
        }
    }

    /** Flushes the current storage preparing for a reimport. */
    @throws[ProcessingFailure]
    def flushTopology(): Unit = try {
        storage.flush()
    } catch {
        case e: Throwable =>
            throw new ProcessingFailure("Could not flush the storage.", e)
    }

    /** Interprets a single transaction of external model operations,
      * translating into the corresponding operations in the internal model, and
      * executing them.
      *
      * TODO Implement idempotent DELETE.
      */
    @throws[ProcessingFailure]
    def interpretAndExecTxn(txn: org.midonet.brain.services.c3po.neutron.Transaction): Unit = {
        assert(initialized)
        try {
            val newState = storageManagerState(txn.lastTaskId)
            val midoOps = txn.tasks.flatMap { task =>
                translateC3POOpsToPersistenceOps(
                        task.asInstanceOf[org.midonet.brain.services.c3po.neutron.Task[Message]])
            } ++ List(UpdateOp(newState))

            storage.multi(midoOps)
            log.info(s"Executed a C3PO transaction with ID: ${txn.txnId}.")
        } catch {
            case te: TranslationException =>
                    throw new ProcessingFailure(
                            "Failure in translating for a transaction " +
                            s"${txn.txnId}", te)
            case se: StorageException =>
                    throw new ProcessingFailure(
                            "Failure in persisting for a transaction " +
                            s"${txn.txnId}", se)
            case e: Throwable =>
                    throw new ProcessingFailure(
                            "Failure in executing a transaction", e)
        }
    }

    @throws[ProcessingFailure]
    private def translateC3POOpsToPersistenceOps[T <: Message](
            task: org.midonet.brain.services.c3po.neutron.Task[T]) = {
        val modelClass = task.op match {
            case c: org.midonet.brain.services.c3po.neutron.Create[T] => c.model.getClass
            case u: org.midonet.brain.services.c3po.neutron.Update[T] => u.model.getClass
            case d: org.midonet.brain.services.c3po.neutron.Delete[T] => d.clazz
        }
        if (!apiTranslators.containsKey(modelClass)) {
            throw new ProcessingFailure (
                    s"No translator for $modelClass.", null)
        }

        Seq(task.op.toPersistenceOp) ++  // Persists the original model
                apiTranslators.get(modelClass)
                              .asInstanceOf[NeutronTranslator[T]]
                              .translate(task.op)
                              .map { midoOp => midoOp.toPersistenceOp }
    }
}