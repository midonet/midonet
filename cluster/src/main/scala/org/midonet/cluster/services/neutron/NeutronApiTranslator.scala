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
package org.midonet.cluster.services.neutron

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.services.c3po._
import org.midonet.cluster.services.c3po.OpType.OpType

/**
 * Defines an abstract base class for Neutron API request translator that
 * processes operations on Neutron model (Network, Subnet, etc.).
 */
abstract class NeutronApiTranslator[T <: Object](
        val neutronModelClass: Class[T],
        val storage: ReadOnlyStorage) extends ApiTranslator[T] {

    protected val log = LoggerFactory.getLogger(this.getClass)

    /**
     * Unified exception handling.
     */
    protected def processExceptions(e: Exception,
                                    op: OpType) = {
        throw new TranslationException(op, neutronModelClass, e)
    }
}

/**
 * Factors out boilerplate for translation of Neutron operations that
 * correspond to a single Midonet operation.
 * @param neutronModelClass Neutron model class.
 * @param midoModelClass Midonet model class.
 * @param converter Function to convert Neutron model to Midonet model.
 * @param storage
 * @tparam N Neutron model class.
 * @tparam M Midonet model class.
 */
class OneToOneNeutronApiTranslator[N <: Object, M <: Object](
        neutronModelClass: Class[N],
        midoModelClass: Class[M],
        converter: (N) => M,
        storage: ReadOnlyStorage)
    extends NeutronApiTranslator[N](neutronModelClass, storage) {

    /**
     * Converts an operation on an external model into the corresponding
     * operation on an internal MidoNet model.
     */
    @throws[TranslationException]
    override def toMido(op: C3POOp[N]): List[MidoModelOp[_ <: Object]] = {
        try op match {
            case c: C3POCreate[N] => List(MidoCreate(converter(c.model)))
            case u: C3POUpdate[N] => List(MidoUpdate(converter(u.model)))
            case d: C3PODelete[N] => List(MidoDelete(midoModelClass, d.id))
        } catch {
            case ex: Exception =>
                processExceptions(ex, op.opType)
        }
    }
}
