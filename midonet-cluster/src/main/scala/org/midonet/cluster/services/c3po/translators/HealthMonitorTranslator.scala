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

import scala.collection.JavaConverters._

import org.midonet.cluster.data.storage.{ReadOnlyStorage, UpdateValidator}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronHealthMonitor
import org.midonet.cluster.models.Topology.{HealthMonitor, Pool}
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.util.concurrent.toFutureOps


/** Provides a Neutron model translator for NeutronHealthMonitor. */
class HealthMonitorTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronHealthMonitor]{

    private def translate(nhm: NeutronHealthMonitor, setPoolId: Boolean = false)
    : HealthMonitor = {
        // Health Monitor status cannot be modified from the API. It is set by
        // Health Monitor directly.
        val bldr = HealthMonitor.newBuilder
            .setId(nhm.getId)
            .setAdminStateUp(nhm.getAdminStateUp)
            .setDelay(nhm.getDelay)
            .setMaxRetries(nhm.getMaxRetries)
            .setTimeout(nhm.getTimeout)

        if (setPoolId) {
            bldr.clearPoolIds()
            bldr.addAllPoolIds(nhm.getPoolsList.asScala.map(_.getPoolId).asJava)
        }

        bldr.build()
    }

    override protected def translateCreate(nhm: NeutronHealthMonitor)
    : MidoOpList = {
        val hm = translate(nhm)

        val pools = for (pool <- nhm.getPoolsList.asScala) yield {
            val pb = storage.get(classOf[Pool], pool.getPoolId).await().toBuilder
            pb.setHealthMonitorId(hm.getId).build()
        }

        List(Create(hm)) ++ pools.map(Update(_))
    }

    override protected def translateDelete(id: UUID)
    : MidoOpList = List(Delete(classOf[HealthMonitor], id))

    override protected def translateUpdate(nhm: NeutronHealthMonitor)
    : MidoOpList = {
        List(Update(translate(nhm, setPoolId = true),
                    HealthMonitorUpdateValidator))
    }
}

private[translators] object HealthMonitorUpdateValidator
        extends UpdateValidator[HealthMonitor] {
    override def validate(oldHm: HealthMonitor, newHm: HealthMonitor)
    : HealthMonitor = {
        if (oldHm.getPoolIdsList != newHm.getPoolIdsList)
            throw new IllegalStateException(
                "A health monitor's pool association cannot be changed.")

        val validatedUpdateBldr = newHm.toBuilder
        if (oldHm.hasStatus) validatedUpdateBldr.setStatus(oldHm.getStatus)
        validatedUpdateBldr.build
    }
}
