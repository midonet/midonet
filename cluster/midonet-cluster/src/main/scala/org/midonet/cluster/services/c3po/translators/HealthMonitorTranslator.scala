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

import org.midonet.cluster.data.storage.UpdateValidator
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.models.Commons.{LBStatus, UUID}
import org.midonet.cluster.models.Neutron.NeutronHealthMonitor
import org.midonet.cluster.models.Topology.HealthMonitor
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for NeutronHealthMonitor. */
class HealthMonitorTranslator extends NeutronTranslator[NeutronHealthMonitor]{


    private def translate(nhm: NeutronHealthMonitor)
    : HealthMonitor = {
        // Health Monitor status cannot be modified from the API. It is set by
        // Health Monitor directly.
        HealthMonitor.newBuilder()
                     .setId(nhm.getId)
                     .setAdminStateUp(nhm.getAdminStateUp)
                     .setDelay(nhm.getDelay)
                     .setMaxRetries(nhm.getMaxRetries)
                     .setTimeout(nhm.getTimeout).build
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
    : MidoOpList = List(Delete(classOf[HealthMonitor], id))

    /* Neutron will pass in the "pools" repeated field all the Pools associated
     * at the moment. The association between Health Monitor and Pool will never
     * be updated here from the Health Monitor side, and always be done from the
     * Pool side. We assume that Neutron will make sure that the correct values
     * are passed down and do not check here. */
    override protected def translateUpdate(nhm: NeutronHealthMonitor)
    : MidoOpList = {
        List(Update(translate(nhm), HealthMonitorUpdateValidator))
    }
}

private[translators] object HealthMonitorUpdateValidator
        extends UpdateValidator[HealthMonitor] {
    override def validate(oldHm: HealthMonitor, newHm: HealthMonitor)
    : HealthMonitor = {
        val validatedUpdateBldr = newHm.toBuilder
        if (oldHm.hasStatus) validatedUpdateBldr.setStatus(oldHm.getStatus)
        if (oldHm.hasPoolId) validatedUpdateBldr.setPoolId(oldHm.getPoolId)
        validatedUpdateBldr.build
    }
}