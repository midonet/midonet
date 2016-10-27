/*
 * Copyright 2016 Midokura SARL
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

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Neutron.PortBinding
import org.midonet.cluster.models.Topology.Port

/**
 * Translate port binding.
 */
class PortBindingTranslator extends Translator[PortBinding] with PortManager {
    /**
     * Creates a new port binding of a port to a host / interface, producing
     * an UPDATE operation on the port. Updates to the host are handled by
     * Zoom bindings.
     */
    @throws[IllegalStateException]
    override protected def translateCreate(tx: Transaction,
                                           binding: PortBinding): Unit = {
        val port = tx.get(classOf[Port], binding.getPortId)
        bindPort(tx, port, binding.getHostId, binding.getInterfaceName)
    }

    /**
      * Update is not allowed for port binding.
      */
    override protected def translateUpdate(tx: Transaction,
                                           binding: PortBinding): Unit = {
        throw new UnsupportedOperationException(
            "Updating a port binding is not allowed")
    }

    /**
      * Deletes a port binding of a port to a host / interface, producing an
      * UPDATE operation on the binding's port. The updates to the host is
      * handled by the Zoom bindings.
      */
    override protected def translateDelete(tx: Transaction,
                                           binding: PortBinding)
    : Unit = {
        val port = tx.get(classOf[Port], binding.getPortId)
        tx.update(port.toBuilder.clearHostId().clearInterfaceName().build())
    }
}