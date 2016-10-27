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

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerPool
import org.midonet.cluster.models.Topology.{LoadBalancer, Pool}

/** Provides a Neutron model translator for NeutronLoadBalancerPool. */
class LoadBalancerPoolTranslator
    extends Translator[NeutronLoadBalancerPool] with LoadBalancerManager {

    override protected def translateCreate(tx: Transaction,
                                           nPool: NeutronLoadBalancerPool)
    : Unit = {
        if (!nPool.hasRouterId)
            throw new IllegalArgumentException("No router ID is specified.")
        if (nPool.getHealthMonitorsCount > 0)
            throw new IllegalArgumentException(
                "A health monitor may be associated with a pool only at the " +
                "time of the health monitor's creation.")

        // If no Load Balancer has been created for the Router, create one.
        val lbId = loadBalancerId(nPool.getRouterId)
        val midoOps = new OperationListBuffer
        if (!tx.exists(classOf[LoadBalancer], lbId)) {
            val lb = LoadBalancer.newBuilder()
                                 .setId(lbId)
                                 .setAdminStateUp(nPool.getAdminStateUp)
                                 .setRouterId(nPool.getRouterId).build()
            tx.create(lb)
        }

        tx.create(Pool.newBuilder
                      .setId(nPool.getId)
                      .setLoadBalancerId(loadBalancerId(nPool.getRouterId))
                      .setAdminStateUp(nPool.getAdminStateUp)
                      .build())
    }

    override protected def translateDelete(tx: Transaction,
                                           npool: NeutronLoadBalancerPool)
    : Unit = {
        val pool = tx.get(classOf[Pool], npool.getId)
        val lbId = pool.getLoadBalancerId // if !hasLoadBalancerId it's a bug
        val lb = tx.get(classOf[LoadBalancer], lbId)
        tx.delete(classOf[Pool], npool.getId, ignoresNeo = true)
        if (lb.getPoolIdsCount == 1) {
            tx.delete(classOf[LoadBalancer], lbId, ignoresNeo = true)
        }
    }

    override protected def translateUpdate(tx: Transaction,
                                           nPool: NeutronLoadBalancerPool)
    : Unit = {
        val oldPool = tx.get(classOf[Pool], nPool.getId)
        val updatedPool = oldPool.toBuilder
            .setAdminStateUp(nPool.getAdminStateUp)
            .build()
        tx.update(updatedPool)
    }

}
