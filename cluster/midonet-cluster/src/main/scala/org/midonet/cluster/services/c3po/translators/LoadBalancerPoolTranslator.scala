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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerPool
import org.midonet.cluster.models.Topology.Pool.PoolHealthMonitorMappingStatus._
import org.midonet.cluster.models.Topology.{LoadBalancer, Pool}
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for NeutronLoadBalancerPool. */
class LoadBalancerPoolTranslator(protected val storage: ReadOnlyStorage)
        extends NeutronTranslator[NeutronLoadBalancerPool] {

    override protected def translateCreate(nPool: NeutronLoadBalancerPool)
    : MidoOpList = {
        if (!nPool.hasRouterId)
            throw new IllegalArgumentException("No router ID is specified.")

        // If no Load Balancer has been created for the Router, create one.
        val lbId = nPool.getRouterId // LB and Router share the same ID.
        val midoOps = new MidoOpListBuffer
        if (!storage.exists(classOf[LoadBalancer], lbId).await()) {
            val lb = LoadBalancer.newBuilder()
                                 .setId(lbId)
                                 .setAdminStateUp(nPool.getAdminStateUp)
                                 .setRouterId(nPool.getRouterId).build()
            midoOps += Create(lb)
        }

        // Create a MidoNet Pool.
        val midoPool = translatePool(Pool.newBuilder(), nPool, lbId)
        midoOps += Create(midoPool)

        midoOps.toList
    }

    override protected def translateDelete(id: UUID)
    : MidoOpList = List(Delete(classOf[Pool], id))

    override protected def translateUpdate(nPool: NeutronLoadBalancerPool)
    : MidoOpList = {
        val pool = storage.get(classOf[Pool], nPool.getId).await()
        val updatedPool = translatePool(
                pool.toBuilder(), nPool, pool.getLoadBalancerId)

        if (pool.hasHealthMonitorId && updatedPool.hasHealthMonitorId &&
            pool.getHealthMonitorId != updatedPool.getHealthMonitorId)
            throw new IllegalStateException(
                    "A Health Monitor cannot be updated to a different one.")
        List(Update(updatedPool))
    }

    private def translatePool(poolBldr: Pool.Builder,
                              neutronPool: NeutronLoadBalancerPool,
                              loadBalancerId: UUID): Pool = {
        poolBldr.setId(neutronPool.getId)
                .setLoadBalancerId(loadBalancerId)
                .setAdminStateUp(neutronPool.getAdminStateUp)
        // In practice there's at most 1 Health Monitor associated.
        if (neutronPool.getHealthMonitorsCount > 0) {
            poolBldr.setHealthMonitorId(neutronPool.getHealthMonitors(0))
            // Don't overwrite HM mapping status if it has already been set.
            // Check against replacing the Health Monitor is done above.
            if (!poolBldr.hasMappingStatus)
                poolBldr.setMappingStatus(PENDING_CREATE)
        } else {
            poolBldr.clearHealthMonitorId().clearMappingStatus()
        }
        poolBldr.build()
    }
}