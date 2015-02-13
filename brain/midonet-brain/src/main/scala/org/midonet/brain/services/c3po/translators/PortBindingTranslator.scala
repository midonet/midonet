/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.brain.services.c3po.translators

import scala.collection.JavaConverters._

import org.midonet.brain.services.c3po.midonet.Update
import org.midonet.brain.services.c3po.neutron
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.PortBinding
import org.midonet.cluster.models.Topology.{Host, Port}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.concurrent.toFutureOps

/**
 * Translate port binding.
 */
class PortBindingTranslator(storage: ReadOnlyStorage)
        extends NeutronTranslator[PortBinding] {
    /**
     * Creates a new port binding of a port to a host / interface, producing
     * an UPDATE operation on a corresponding host.
     *
     * It first checks if the port exists and throws an exception if it
     * doesn't. It is assumed that the corresponding host already exists and
     * throws an exception if it doesn't. Also throws an exception if the port
     * or the interface is already bound on the host.
     */
    override protected def translateCreate(binding: PortBinding): MidoOpList = {
        val hostFtr = storage.get(classOf[Host], binding.getHostId)
        val portFtr = storage.get(classOf[Port], binding.getPortId)

        val updatedHost = hostFtr.await().toBuilder
        if (updatedHost.getPortInterfaceMappingList.asScala.exists(mapping =>
            mapping.getInterfaceName == binding.getInterfaceName ||
            mapping.getPortId == binding.getPortId)) {
            throw new TranslationException(neutron.Create(binding),
            msg = s"Interface ${binding.getInterfaceName} or port " +
                  s"ID = ${UUIDUtil.fromProto(binding.getPortId)} " +
                  "is already bound")
        }

        val pimBldr = updatedHost.addPortInterfaceMappingBuilder()
        pimBldr.setInterfaceName(binding.getInterfaceName)
               .setPortId(binding.getPortId)

        val updatedPort = portFtr.await().toBuilder
        updatedPort.setHostId(updatedHost.getId)

        List(Update(updatedHost.build()), Update(updatedPort.build()))
    }

    /**
     * Update is not allowed for port binding.
     */
    override protected def translateUpdate(binding: PortBinding): MidoOpList =
        throw new TranslationException(neutron.Update(binding),
                msg = "Port binding UPDATE is not allowed")

    /**
     * Deletes a port binding of a port to a host / interface, producing an
     * UPDATE operation on a corresponding host.
     *
     * It is assumed that the corresponding host exists, and throws an exception
     * if it doesn't. Also throws an exception if the binding doesn't exist with
     * the host.
     */
    override protected def translateDelete(id: UUID): MidoOpList = {
        val binding = storage.get(classOf[PortBinding], id).await()
        val hostFtr = storage.get(classOf[Host], binding.getHostId)
        val portFtr = storage.get(classOf[Port], binding.getPortId)

        val updatedHost = hostFtr.await().toBuilder
        val mappingToDelete = updatedHost.getPortInterfaceMappingList.asScala
            .indexWhere({mapping =>
                mapping.getPortId == binding.getPortId &&
                mapping.getInterfaceName == binding.getInterfaceName})
        if (mappingToDelete < 0)
            throw new TranslationException(
                    neutron.Delete(classOf[PortBinding], binding.getId),
                    msg = "Trying to delete a non-existing port binding")

        updatedHost.removePortInterfaceMapping(mappingToDelete)

        val updatedPort = portFtr.await().toBuilder
        updatedPort.clearHostId()

        List(Update(updatedHost.build()), Update(updatedPort.build()))
    }
}