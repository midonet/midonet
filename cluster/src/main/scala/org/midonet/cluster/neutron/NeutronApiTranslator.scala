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

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.services.c3po.ApiTranslator
import org.midonet.cluster.services.c3po.MidoModelOp
import org.midonet.cluster.services.c3po.OpType.OpType
import org.midonet.cluster.services.c3po.TranslationException
import org.midonet.cluster.util.UUIDUtil

/**
 * Defines an abstract base class for Neutron API request translator that
 * processes operations on high-level Neutron model (Network, Subnet, etc.).
 */
abstract class NeutronApiTranslator[T](
        val neutronModelClass: Class[T],
        val midoModelClass: Class[_],
        val storage: ReadOnlyStorage) extends ApiTranslator[T] {

    /**
     * Unified exception handling.
     */
    protected def processExceptions(e: Exception,
                                    op: OpType) = {
        throw new TranslationException(op, neutronModelClass, e)
    }
}
