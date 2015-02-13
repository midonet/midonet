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
import org.midonet.cluster.models.Neutron.{PortBinding}
import org.midonet.cluster.models.Topology.{Host, Port}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.concurrent.toFutureOps

/**
 * Translate port binding.
 */
class PortBindingTranslator(storage: ReadOnlyStorage)
        extends NeutronTranslator[PortBinding] {
    override protected def translateCreate(binding: PortBinding): MidoOpList = {
        if (!storage.exists(classOf[Port], binding.getPortId).await())
            throw new TranslationException(neutron.Create(binding),
                    cause = null,
                    msg = "Trying to bind to a non-existing port ID = " +
                          UUIDUtil.fromProto(binding.getPortId))

        val midoOps = new MidoOpListBuffer
        val nHost = storage.get(classOf[Host], binding.getHostId).await()
        val updatedHost = nHost.toBuilder()

        nHost.getPortInterfaceMappingList.asScala.find { mapping =>
            mapping.getInterfaceName == binding.getInterfaceName ||
            mapping.getPortId == binding.getPortId}.foreach(_ =>
                    throw new TranslationException(neutron.Create(binding),
                    cause = null,
                    msg = s"Interface ${binding.getInterfaceName} or port " +
                          s"ID = ${UUIDUtil.fromProto(binding.getPortId)} " +
                          "is already bound"))

        updatedHost.addPortInterfaceMappingBuilder()
                   .setInterfaceName(binding.getInterfaceName)
                   .setPortId(binding.getPortId)

        midoOps += Update(updatedHost.build())
        midoOps.toList
    }

    override protected def translateUpdate(nm: PortBinding): MidoOpList = ???

    override protected def translateDelete(id: UUID): MidoOpList = {
        val midoOps = new MidoOpListBuffer
        val binding = storage.get(classOf[PortBinding], id).await()
        val nHost = storage.get(classOf[Host], binding.getHostId).await()
        val updatedHost = nHost.toBuilder()

        val mappingToDelete = updatedHost.getPortInterfaceMappingList.asScala
            .indexWhere({mapping =>
                mapping.getPortId == binding.getPortId &&
                mapping.getInterfaceName == binding.getInterfaceName})
        if (mappingToDelete < 0)
            throw new TranslationException(
                    neutron.Delete(classOf[PortBinding], binding.getId),
                    cause = null,
                    msg = "Trying to delete a non-existing port binding")

        updatedHost.removePortInterfaceMapping(mappingToDelete)

        midoOps += Update(updatedHost.build())
        midoOps.toList
    }
}