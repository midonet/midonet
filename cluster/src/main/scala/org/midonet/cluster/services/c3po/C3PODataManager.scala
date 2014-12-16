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

import org.midonet.cluster.data.storage.PersistenceOp

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

/**
 * Defines an API for processing operations on an extermal model by translating
 * then into MidoNet models.
 */
trait C3PODataManager {

    /** Returns the last processed task ID. */
    def lastProcessedTaskId: Int

    /** Flushes the current storage preparing for a reimport. */
    @throws[ProcessingFailure]
    def flushTopology(): Unit

    /** Interprets a single transaction of external model operations,
      * translating into the corresponding operations in the internal model, and
      * executing them. */
    @throws[ProcessingFailure]
    def interpretAndExecTxn(txn: neutron.Transaction): Unit
}
