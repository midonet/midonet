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

package org.midonet.cluster.services.c3po.translators

import scala.reflect.ClassTag
import scala.util.control.NonFatal

import com.google.protobuf.Message

import org.slf4j.LoggerFactory

import org.midonet.cluster.c3poNeutronTranslatorLog
import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Operation, Update}
import org.midonet.util.concurrent.toFutureOps

/**
  * Defines a class that is able to translate from an operation from a high
  * level model to a set of operations on the MidoNet model.
  */
abstract class Translator[HighLevelModel <: Message](
        implicit ct: ClassTag[HighLevelModel]) {

    protected val log =
        LoggerFactory.getLogger(c3poNeutronTranslatorLog(getClass))

    protected def storage: ReadOnlyStorage

    /**
      * Translates the operation on a high level model to a list of operations
      * on translated models that:
      * - maintain (if necessary) the original model,
      * - and translate the model into a corresponding representation made up of
      *   various derived models.
      */
    @throws[TranslationException]
    def translateOp(op: Operation[HighLevelModel]): OperationList = {
        retainHighLevelModel(op) ++ translate(op)
    }

    /**
      * Translates the operation on a high level model to a list of operations
      * on translated models, without maintaining the representation of the
      * original model.
      */
    @throws[TranslationException]
    def translate(op: Operation[HighLevelModel]): OperationList = {
        try {
            op match {
                case Create(nm) => translateCreate(nm)
                case Update(nm, _) => translateUpdate(nm)
                case Delete(_, id) => translateDelete(id)
            }
        } catch {
            case NonFatal(ex) =>
                throw new TranslationException(op, ex, ex.getMessage)
        }
    }

    /**
      * Keep the original model as is by default. Override if the model does not
      * need to be maintained, or need some special handling.
      */
    protected def retainHighLevelModel(op: Operation[HighLevelModel])
    : List[Operation[HighLevelModel]] = {
        op match {
            case Create(nm) => List(Create(nm))
            case Update(nm, _) => List(Update(nm))
            case Delete(clazz, id) => List(Delete(clazz, id))
        }
    }

    /**
      * Translates a [[Create]] operation on the high-level model.
      */
    protected def translateCreate(nm: HighLevelModel): OperationList

    /**
      * Translates an [[Update]] operation on the high-level model.
      */
    protected def translateUpdate(nm: HighLevelModel): OperationList

    /**
      * Translates a [[Delete]] operation on the high-level model, with a given
      * object identifier. The default implementation uses idempotent deletion,
      * where the operation is ignored if the object does not exist.
      */
    protected def translateDelete(id: UUID): OperationList = {
        val nm = try storage.get(ct.runtimeClass, id).await() catch {
            case ex: NotFoundException =>
                log.warn("Received request to delete " +
                         s"${ct.runtimeClass.getSimpleName} " +
                         s"${getIdString(id)}, which " +
                         s"does not exist. Ignoring.")
                return List()
        }
        translateDelete(nm.asInstanceOf[HighLevelModel])
    }

    /**
      * Translates a [[Delete]] operation on the high-level model.
      */
    protected def translateDelete(nm: HighLevelModel): OperationList = List()
}

/** Thrown by by implementations when they fail to perform the requested
  * operation on the source model. */
class TranslationException(val op: Operation[_],
                           val cause: Throwable = null, val msg: String = null)
    extends RuntimeException (s"Failed to $op; $msg", cause)
