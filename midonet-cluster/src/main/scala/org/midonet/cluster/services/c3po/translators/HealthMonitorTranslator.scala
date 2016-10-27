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

import org.midonet.cluster.data.storage.{Transaction, UpdateValidator}
import org.midonet.cluster.models.Neutron.NeutronHealthMonitor
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus.PENDING_CREATE
import org.midonet.cluster.models.Topology.{HealthMonitor, Pool}


/** Provides a Neutron model translator for NeutronHealthMonitor. */
class HealthMonitorTranslator extends Translator[NeutronHealthMonitor]{

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

    override protected def translateCreate(tx: Transaction,
                                           nhm: NeutronHealthMonitor): Unit = {
        val hm = translate(nhm)
        tx.create(hm)
        for (pool <- nhm.getPoolsList.asScala)  {
            val pb = tx.get(classOf[Pool], pool.getPoolId).toBuilder
                .setMappingStatus(PENDING_CREATE)
                .setHealthMonitorId(hm.getId)
            tx.update(pb.build())
        }
    }

    override protected def translateDelete(tx: Transaction,
                                           nhm: NeutronHealthMonitor)
    : Unit = {
        tx.delete(classOf[HealthMonitor], nhm.getId, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           nhm: NeutronHealthMonitor)
    : Unit = {
        tx.update(translate(nhm, setPoolId = true),
                  HealthMonitorUpdateValidator.asInstanceOf[UpdateValidator[Object]])
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
