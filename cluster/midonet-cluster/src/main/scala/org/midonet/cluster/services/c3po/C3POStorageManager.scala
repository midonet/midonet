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

import java.util.concurrent.TimeUnit
import java.util.{HashMap => JHashMap, Map => JMap}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import com.google.protobuf.Message

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Neutron.NeutronRouterInterface
import org.midonet.cluster.services.c3po.translators.{NeutronTranslator, TranslationException}

object C3POStorageManager {

    /* A constant indicating how many seconds C3POStorageManager waits on the
     * future returned from Storage. 10 seconds for now, which we think is
     * sufficient. */
    private val TIMEOUT = Duration.create(10, TimeUnit.SECONDS)

    /** Defines types of operations on a single entity. */
    object OpType extends Enumeration {
        type OpType = Value

        val Create = Value(1)
        val Delete = Value(2)
        val Update = Value(3)
        val CreateNode = Value(4)
        val DeleteNode = Value(5)
        val UpdateNode = Value(6)

        private val ops = Array(Create, Delete, Update,
                                CreateNode, DeleteNode, UpdateNode)
        def valueOf(i: Int) = ops(i - 1)
    }

    /** A generic operation on a model */
    trait Operation {
        def opType: OpType.OpType
        def toPersistenceOp: PersistenceOp
    }

    /** A failure occurred when interpreting or executing an operation. */
    class ProcessingException(msg: String = "", cause: Throwable = null)
        extends RuntimeException("Failed to interpret/execute operation" +
                                 s"${if (msg == null) "" else ": " + msg}",
                                 cause)
}

/** C3PO that translates an operation on an external model into corresponding
  * storage operations on internal Mido models. */
final class C3POStorageManager(storage: Storage) {
    import org.midonet.cluster.services.c3po.C3POStorageManager._

    private val log = LoggerFactory.getLogger(classOf[C3POStorageManager])

    private val apiTranslators = new JHashMap[Class[_], NeutronTranslator[_]]()
    private var initialized = false

    // NeutronRouterInterface objects are not persisted because they don't have
    // unique IDs, so persisting would cause errors.
    private val neutronClassesNotStored: Set[Class[_]] =
        Set(classOf[NeutronRouterInterface])

    def registerTranslator[T <: Message](clazz: Class[T],
                                         translator: NeutronTranslator[T])
    : Unit = apiTranslators.put(clazz, translator)

    def registerTranslators(translators: JMap[Class[_], NeutronTranslator[_]])
    : Unit = apiTranslators.putAll(translators)

    def clearTranslators(): Unit = apiTranslators.clear()

    private def initStorageManagerState(): Unit = {
        try {
            storage.create(C3POState.noTasksProcessed())
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
        Await.result(storage.get(classOf[C3POState], C3POState.ID), TIMEOUT)
             .lastProcessedTaskId
    }

    /** Flushes the current storage preparing for a reimport. */
    @throws[ProcessingException]
    def flushTopology(): Unit = // try {
        throw new NotImplementedError("Flush is not implemented")
        // TODO:
        //
        // - GET the curr storage version node from ZK (e.g., /zoom/currVer)
        // - Create a new root path in ZK at currVersion + 1
        // - WRITE the curr storage version node to ZK at /zoom/currVer
        //
        // Changes in the /zoom/currVer node should be watched by clients, not
        // by zoom itself. When a new version appears, they should close their
        // zoom instances and reinitialize a new one pointing at the new root.
        //
        // In most cases, this will imply a restart (e.g, the agent restarts
        // and starts pointing at the right place)
        //
        // old code:
        //    initStorageManagerState()
        // } catch {
        //     case e: Throwable => throw new ProcessingException("Flushing failed", e)
        // }

    /** Interprets a single transaction of external model operations,
      * translating into the corresponding operations in the internal model, and
      * executing them. */
    @throws[ProcessingException]
    def interpretAndExecTxn(txn: neutron.Transaction): Unit = {
        assert(initialized)

        // Changing this to process one task at a time instead of processing
        // all tasks in a transaction atomically. Although the latter would
        // technically be more correct, the tasks within multi-task Neutron
        // transactions are ordered such that there's no problem with executing
        // them non-atomically.
        //
        // For example, adding a gateway port to a router consists of two tasks:
        // creating the gateway port and then updating the router to use it.
        // There's no problem with having the gateway port exist without
        // updating the router; it simply won't be usable. Since after creating
        // the gateway port we immediately try to process the next task (update
        // the router) and do not attempt to process any other tasks until this
        // succeeds, there's no problem.
        //
        // Why not execute the tasks atomically anyway? There's a problem when
        // the translator for the second task asks the topology store for the
        // object created in the first task. Since the first task isn't
        // committed yet, the topology store can't find it. We plan to address
        // this in the future, but it will likely involve significant changes to
        // Storage interface and implementing classes.
        for (task <- txn.tasks) try {
            val newState = C3POState.at(task.taskId)
            val midoOps = toPersistenceOps(task) :+ UpdateOp(newState)
            storage.multi(midoOps)
            log.info(s"Executed a C3PO task with ID: ${task.taskId}.")
        } catch {
            case te: TranslationException => throw new ProcessingException(
                s"Failed to translate task ${task.taskId} " +
                s"in transaction ${txn.txnId}.", te)
            case se: StorageException => throw new ProcessingException(
                s"Failed to persist task ${task.taskId} " +
                s"in transaction ${txn.txnId}.", se)
            case NonFatal(e) => throw new ProcessingException(
                s"Failed to execute task ${task.taskId} " +
                s"in transaction ${txn.txnId}.", e)
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

        val neutronModelOp = // Persist the original model if appropriate.
            if (neutronClassesNotStored.contains(modelClass)) Seq()
            else Seq(task.op.toPersistenceOp)

        neutronModelOp ++ apiTranslators.get(modelClass)
            .asInstanceOf[NeutronTranslator[T]]
            .translate(task.op)
            .map { midoOp => midoOp.toPersistenceOp }
    }
}
