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

import org.midonet.cluster.models.Commons

/**
 * Defines a set of operations to be performed by the data importer on a single
 * external model object.
 */
object OpType extends Enumeration {
    type OpType = Value

    val Create = Value(1)
    val Delete = Value(2)
    val Update = Value(3)

    private val ops = Array(Create, Delete, Update)
    def valueOf(i: Int) = ops(i - 1)
}

sealed trait C3POOp[T] {
    def opType(): OpType.OpType
}

case class ModelCreate[T](model: T) extends C3POOp[T] {
    override def opType = OpType.Create
}

case class ModelUpdate[T](model: T) extends C3POOp[T] {
    override def opType = OpType.Update
}
case class ModelDelete[T](clazz: Class[T], id: Commons.UUID) extends C3POOp[T] {
    override def opType = OpType.Delete
}

/**
 * Defines an operation on a MidoNet model.
 */
sealed trait MidoModelOp[T <: Object] {
    def opType(): OpType.OpType
}

case class MidoCreate[T <: Object](model: T) extends MidoModelOp[T] {
    override def opType = OpType.Create
}

case class MidoUpdate[T <: Object](model: T) extends MidoModelOp[T] {
    override def opType = OpType.Update
}

case class MidoDelete[T <: Object](clazz: Class[T], id: Commons.UUID)
        extends MidoModelOp[T] {
    override def opType = OpType.Delete
}

/**
 * A common interface for the API translator.
 */
trait ApiTranslator[T] {

    /**
     * Converts an operation on an external model into 1 or more corresponding
     * operations on internal MidoNet models.
     */
    @throws[TranslationException]
    def toMido(op: C3POOp[T]): List[MidoModelOp[_ <: Object]]
}

/**
 * Thrown by NeutronAPIService implementations when they fail to perform the
 * requested operation on the Neutron model.
 */
class TranslationException(val operation: OpType.OpType,
                           val model: Class[_],
                           val msg: String,
                           val cause: Throwable)
        extends RuntimeException(
                s"Failed to ${operation} ${model.getSimpleName}: ${msg}",
                cause) {

    def this(operation: OpType.OpType, model: Class[_], msg: String) {
        this(operation, model, msg, null)
    }

    def this(operation: OpType.OpType, model: Class[_], cause: Throwable) {
        this(operation, model, "", cause)
    }
}

/**
 * Defines an external interface of C3PO, which converts an operation on an
 * external data model into corresponding operations on internal MidoNet models.
 */
trait C3PO {
    import OpType.OpType

    /**
     * Interprets an operation on an external model into corresponding internal
     * model operations and execute them.
     */
    @throws[TranslationException]
    def interpretAndExec[T](op: C3POOp[T]): Unit
}