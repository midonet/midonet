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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.services.c3po.midonet.{Create, Update}
import org.midonet.cluster.models.Commons.{LBStatus, UUID}
import org.midonet.cluster.models.Neutron.NeutronHealthMonitor
import org.midonet.cluster.models.Topology.HealthMonitor
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for NeutronHealthMonitor. */
class HealthMonitorTranslator(protected val storage: ReadOnlyStorage)
        extends NeutronTranslator[NeutronHealthMonitor]{


    private def translate(nhm: NeutronHealthMonitor, status: LBStatus = null)
    : HealthMonitor = {
        // Health Monitor status is ACTIVE by default, and cannot be modified
        // from the API and is to be set by HM directly.
        val hmBldr = HealthMonitor.newBuilder()
                                 .setId(nhm.getId)
                                 .setAdminStateUp(nhm.getAdminStateUp)
                                 .setDelay(nhm.getDelay)
                                 .setTimeout(nhm.getTimeout)
                                 .setMaxRetries(nhm.getMaxRetries)
        if (status != null) hmBldr.setStatus(status)
        hmBldr.build
    }

    override protected def translateCreate(nhm: NeutronHealthMonitor)
    : MidoOpList = {
        // The legacy plugin doesn't allow Load Balancer Pools to be associated
        // with a Health Monitor in creation (for simplifying implementations.)
        if (nhm.getPoolsCount > 0) throw new IllegalArgumentException(
                "Load Balancer Pool cannot be associated.")

        List(Create(translate(nhm)))
    }

    override protected def translateDelete(id: UUID)
    : MidoOpList = List()

    override protected def translateUpdate(nhm: NeutronHealthMonitor)
    : MidoOpList = {
        val currHm = storage.get(classOf[HealthMonitor], nhm.getId).await()
        val currStatus = if (currHm.hasStatus) currHm.getStatus else null

        List(Update(translate(nhm, currStatus)))
    }
}