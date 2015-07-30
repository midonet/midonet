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

import org.midonet.cluster.data.storage.{ReadOnlyStorage, UpdateValidator}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronHealthMonitor
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus.PENDING_CREATE
import org.midonet.cluster.models.Topology.{HealthMonitor, Pool}
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.util.concurrent.toFutureOps


/** Provides a Neutron model translator for NeutronHealthMonitor. */
class HealthMonitorTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronHealthMonitor]{

    private def translate(nhm: NeutronHealthMonitor)
    : HealthMonitor = {
        if (nhm.getPoolsCount != 1) throw new IllegalArgumentException(
            "Health monitor must have exactly one pool association.")

        // Health Monitor status cannot be modified from the API. It is set by
        // Health Monitor directly.
        HealthMonitor.newBuilder()
            .setId(nhm.getId)
            .setAdminStateUp(nhm.getAdminStateUp)
            .setDelay(nhm.getDelay)
            .setMaxRetries(nhm.getMaxRetries)
            .setTimeout(nhm.getTimeout)
            .setPoolId(nhm.getPools(0).getPoolId)
            .build()
    }

    override protected def translateCreate(nhm: NeutronHealthMonitor)
    : MidoOpList = {
        val hm = translate(nhm)

        // Initialize associated pool's mapping status.
        val pb = storage.get(classOf[Pool], hm.getPoolId).await().toBuilder
        val pool = pb.setMappingStatus(PENDING_CREATE)
            .setHealthMonitorId(hm.getId)
            .build()

        List(Create(hm), Update(pool))
    }

    override protected def translateDelete(id: UUID)
    : MidoOpList = List(Delete(classOf[HealthMonitor], id))

    override protected def translateUpdate(nhm: NeutronHealthMonitor)
    : MidoOpList = {
        List(Update(translate(nhm), HealthMonitorUpdateValidator))
    }
}

private[translators] object HealthMonitorUpdateValidator
        extends UpdateValidator[HealthMonitor] {
    override def validate(oldHm: HealthMonitor, newHm: HealthMonitor)
    : HealthMonitor = {
        if (oldHm.getPoolId != newHm.getPoolId)
            throw new IllegalStateException(
                "A health monitor's pool association cannot be changed.")

        val validatedUpdateBldr = newHm.toBuilder
        if (oldHm.hasStatus) validatedUpdateBldr.setStatus(oldHm.getStatus)
        validatedUpdateBldr.build
    }
}
