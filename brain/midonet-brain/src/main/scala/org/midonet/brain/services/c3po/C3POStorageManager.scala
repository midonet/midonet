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

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import com.google.protobuf.Message
import org.slf4j.LoggerFactory

import org.midonet.brain.services.c3po.translators.{NeutronTranslator, TranslationException}
import org.midonet.cluster.data.storage._
import org.midonet.cluster.services.c3po.C3POState

object C3POStorageManager {

    /* A constant indicating how many seconds C3POStorageManager waits on the
     * future returned from Storage. 10 seconds for now, which we think is
     * sufficient. */
    private val TIMEOUT = Duration.create(10, TimeUnit.SECONDS)

    // ID for the singleton C3POState object. All objects persisted via
    // the Storage interface need a property named id.
    private[c3po] val C3PO_STATE_ID = new JUUID(0L, 1L)
    private[c3po] val NO_C3PO_TASKS_PROCESSED = 0

    /** Defines types of operations on a single entity. */
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
    class ProcessingException(msg: String = "", cause: Throwable = null)
        extends RuntimeException("Failed to interpret/execute operation" +
                                 s"${if (msg == null) "" else ": " + msg}",
                                 cause)

    /* A utility method to generate the C3POState object holding the last
     * processed task ID. */
    private[c3po] def c3poState(lastProcessed: Int) =
        new C3POState(C3PO_STATE_ID, lastProcessed)
}

/** C3PO that translates an operation on an external model into corresponding
  * storage operations on internal Mido models. */
final class C3POStorageManager(storage: Storage) {
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

    private def initStorageManagerState(): Unit = {
        try {
            storage.create(c3poState(NO_C3PO_TASKS_PROCESSED))
            log.info("Initialized last processed task ID")
        } catch {
            case _: ObjectExistsException => // ok
            case e: Throwable =>
                throw new ProcessingException(
                        "Failure initializing C3PODataManager.", e)
        }
    }

    @throws[ProcessingException]
    def init(): Unit = try {
        initStorageManagerState()
        initialized = true
        log.info("Initialized last processed task ID.")
    } catch {
        case _: ObjectExistsException =>
            log.info(s"State node already exists")
            initialized = true
        case e: Throwable =>
            throw new ProcessingException("C3PODataManager initialisation", e)
    }

    /** Returns the ID of the last Task that was processed by the cluster */
    @throws[ProcessingException]
    def lastProcessedTaskId: Int = {
        assert(initialized)
        Await.result(storage.get(classOf[C3POState], C3PO_STATE_ID), TIMEOUT)
             .lastProcessedTaskId
    }

    /** Flushes the current storage preparing for a reimport. */
    @throws[ProcessingException]
    def flushTopology(): Unit = try {
        storage.flush()
        initStorageManagerState()
    } catch {
        case e: Throwable => throw new ProcessingException("Flushing failed", e)
    }

    /** Interprets a single transaction of external model operations,
      * translating into the corresponding operations in the internal model, and
      * executing them. */
    @throws[ProcessingException]
    def interpretAndExecTxn(txn: neutron.Transaction): Unit = {
        assert(initialized)
        try {
            val newState = c3poState(txn.lastTaskId)
            val midoOps = txn.tasks.flatMap { task =>
                toPersistenceOps(task.asInstanceOf[neutron.Task[Message]])
            } ++ List(UpdateOp(newState))

            storage.multi(midoOps)
            log.info(s"Executed a C3PO transaction with ID: ${txn.txnId}.")
        } catch {
            case te: TranslationException => throw new ProcessingException(
                        s"Failed to translate transaction: ${txn.txnId}", te)
            case se: StorageException => throw new ProcessingException(
                        s"Failed to persist transaction: ${txn.txnId}", se)
            case e: Throwable => throw new ProcessingException(
                        s"Failed to execute transaction: ${txn.txnId}", e)
        }
    }

    @throws[ProcessingException]
    private def toPersistenceOps[T <: Message](task: neutron.Task[T]) = {
        val modelClass = task.op match {
            case c: neutron.Create[T] => c.model.getClass
            case u: neutron.Update[T] => u.model.getClass
            case d: neutron.Delete[T] => d.clazz
        }
        if (!apiTranslators.containsKey(modelClass)) {
            throw new ProcessingException(s"No translator for $modelClass.")
        }

        Seq(task.op.toPersistenceOp) ++  // Persists the original model
                apiTranslators.get(modelClass)
                              .asInstanceOf[NeutronTranslator[T]]
                              .translate(task.op)
                              .map { midoOp => midoOp.toPersistenceOp }
    }
}