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

package org.midonet.cluster.services.c3po.translators

import com.google.protobuf.Message

import org.midonet.cluster.services.c3po.midonet.Create
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronHealthMonitor
import org.midonet.cluster.models.Topology.HealthMonitor

/** Provides a Neutron model translator for NeutronHealthMonitor. */
class HealthMonitorTranslator extends NeutronTranslator[NeutronHealthMonitor]{
    override protected def translateCreate(nhm: NeutronHealthMonitor)
    : MidoOpList = {
        // The legacy plugin doesn't allow Load Balancer Pools to be associated
        // with a Health Monitor in creation (for simplifying implementations.)
        if (nhm.getPoolsCount > 0) throw new IllegalArgumentException(
                "Load Balancer Pool cannot be associated.")

        List(Create(HealthMonitor.newBuilder()
                                 .setId(nhm.getId)
                                 .setAdminStateUp(nhm.getAdminStateUp)
                                 .setDelay(nhm.getDelay)
                                 .setTimeout(nhm.getTimeout)
                                 .setMaxRetries(nhm.getMaxRetries).build()))
    }

    override protected def translateDelete(id: UUID)
    : MidoOpList = List()

    override protected def translateUpdate(nm: NeutronHealthMonitor)
    : MidoOpList = List()
}