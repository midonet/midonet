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
package org.midonet.cluster.neutron

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.neutron.OpType.OpType
import org.midonet.cluster.util.UUIDUtil

/**
 * Thrown by NeutronAPIService implementations when they fail to perform the
 * requested operation on the Neutron model.
 */
class TranslationException(val operation: OpType.Value,
                                    val model: Class[_], val cause: Throwable)
        extends RuntimeException(
                s"Failed to ${operation} ${model.getSimpleName}.", cause) {
}

case class MidoModelOp(op: OpType, midoModel: Object)

/**
 * Defines an abstract base class for Neutron API request translator that
 * processes operations on high-level Neutron model (Network, Subnet, etc.).
 */
abstract class NeutronApiTranslator[T, M <: Object](
        val neutronModelClass: Class[T],
        val midoModelClass: Class[M],
        val storage: ReadOnlyStorage) {
    val log = LoggerFactory.getLogger(classOf[NeutronApiTranslator[T,M]])

    /**
     * Converts a Neutron operation on a high-level Neutron model into 1 or more
     * operations on lower-level MidoNet model.
     */
    @throws[TranslationException]
    def toMido(op: OpType, neutronModel: T): List[MidoModelOp]

    /**
     * Unified exception handling.
     */
    protected def processExceptions(e: Exception,
                                   op: OpType.Value) = {
        throw new TranslationException(op, neutronModelClass, e)
    }

}