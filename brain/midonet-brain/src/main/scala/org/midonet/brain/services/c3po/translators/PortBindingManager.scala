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

import com.google.protobuf.Message

import scala.collection.JavaConverters._

import org.midonet.brain.services.c3po.midonet.{MidoOp, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology.Host
import org.midonet.util.concurrent.toFutureOps

/**
 * Contains port-binding-related operations shared by multiple translator classes.
 */
trait PortBindingManager {

    protected val storage: ReadOnlyStorage

    def removePortBinding(hostId: UUID, portId: UUID,
                          ifName: String): Option[MidoOp[_ <: Message]] = {
        val updatedHost = storage.get(classOf[Host], hostId).await().toBuilder
        val bindingToDelete = updatedHost.getPortBindingsList.asScala
            .indexWhere(bdg => bdg.getPortId == portId &&
                               bdg.getInterfaceName == ifName)
        if (bindingToDelete < 0) None
        else {
            updatedHost.removePortBindings(bindingToDelete)
            Some(Update(updatedHost.build()))
        }
    }
}