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
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronHealthMonitorV2
import org.midonet.cluster.models.Topology.HealthMonitor
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation

/** Provides a Neutron model translator for NeutronHealthMonitorV2. */
class HealthMonitorV2Translator extends Translator[NeutronHealthMonitorV2] {

    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronHealthMonitorV2])
    : List[Operation[NeutronHealthMonitorV2]] = List()

    override protected def translateCreate(tx: Transaction,
                                           nHm: NeutronHealthMonitorV2)
    : Unit = {
        tx.create(convertHm(nHm))
    }

    override protected def translateDelete(tx: Transaction, id: UUID): Unit = {
        tx.delete(classOf[HealthMonitor], id, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           nHm: NeutronHealthMonitorV2)
    : Unit = {
        val hm = tx.get(classOf[HealthMonitor], nHm.getId)
        val hmUpdated = hm.toBuilder.setAdminStateUp(nHm.getAdminStateUp)
                                    .setDelay(nHm.getDelay)
                                    .setTimeout(nHm.getTimeout)
                                    .setMaxRetries(nHm.getMaxRetries)
                                    .build()
        tx.update(hmUpdated)
    }

    private def convertHm(nHm: NeutronHealthMonitorV2): HealthMonitor = {
        val hm = HealthMonitor.newBuilder
            .setAdminStateUp(nHm.getAdminStateUp)
            .setDelay(nHm.getDelay)
            .setMaxRetries(nHm.getMaxRetries)
            .setTimeout(nHm.getTimeout)
            .addAllPoolIds(nHm.getPoolsList)
            .setId(nHm.getId)

        hm.build
    }
}
