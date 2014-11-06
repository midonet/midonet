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

/**
 * A common interface for the API translator.
 */
trait ApiTranslator[T] {

    /**
     * Converts a Neutron operation on a high-level Neutron model into 1 or more
     * operations on lower-level MidoNet model.
     */
    @throws[TranslationException]
    def toMido(op: OpType.OpType, inputModel: T): List[MidoModelOp]
}

/**
 * Defines a set of operations to be performed by the Neutron data importer on a
 * single high-level Neutron model object or the entire set of Neutron model
 * data.
 */
object OpType extends Enumeration {
    type OpType = Value

    val Create = Value(1)
    val Delete = Value(2)
    val Update = Value(3)
    val Flush = Value(4)

    private val ops = Array(Create, Delete, Update, Flush)
    def valueOf(i: Int) = ops(i - 1)
}

case class MidoModelOp(op: OpType.OpType, midoModel: Object)

/**
 * Thrown by NeutronAPIService implementations when they fail to perform the
 * requested operation on the Neutron model.
 */
class TranslationException(
        val operation: OpType.OpType, val model: Class[_], val cause: Throwable)
        extends RuntimeException(
                s"Failed to ${operation} ${model.getSimpleName}.", cause) {
}

/**
 * Defines an external interface of C3PO, which converts an operation on an
 * external data model into corresponding operations on internal MidoNet models.
 */
trait C3PO {
    import OpType.OpType

    /**
     * Translates an operation on an external model into corresponding internal
     * model operations.
     */
    @throws[TranslationException]
    def translate[T](op: OpType, inputModel: T): Unit
}