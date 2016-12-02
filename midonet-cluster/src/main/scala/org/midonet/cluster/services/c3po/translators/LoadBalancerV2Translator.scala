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
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerV2
import org.midonet.cluster.models.Topology.{LoadBalancer, Port, Router}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation
import org.midonet.cluster.util.IPAddressUtil

/** Provides a Neutron model translator for NeutronLoadBalancerV2. */
class LoadBalancerV2Translator
    extends Translator[NeutronLoadBalancerV2] with LoadBalancerManager {
    /**
      *  Neutron does not maintain the back reference to the Floating IP, so we
      * need to do that by ourselves.
      */
    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronLoadBalancerV2])
    : List[Operation[NeutronLoadBalancerV2]] = List()

    override protected def translateCreate(tx: Transaction,
                                           nLb: NeutronLoadBalancerV2)
    : Unit = {
        if (!nLb.hasVipPortId || !nLb.hasVipAddress)
            throw new IllegalArgumentException(
                "VIP port must be created and specified along with the VIP address.")

        val newRouterId = lbV2RouterId(nLb.getId)
        val newRouterPortId = PortManager.routerInterfacePortPeerId(nLb.getVipPortId)

        // Create the router for this LB
        val newRouter = Router.newBuilder()
                              .setId(newRouterId)
                              .setAdminStateUp(nLb.getAdminStateUp)
                              .build

        // Create load balancer object with this new router
        val lb = LoadBalancer.newBuilder()
            .setId(nLb.getId)
            .setAdminStateUp(nLb.getAdminStateUp)
            .setRouterId(newRouterId)
            .build

        // Create a router-side port with the VIP address, and connect to the
        // already-created VIP port (specified by vipPortId)
        val newPort = Port.newBuilder()
                          .setId(newRouterPortId)
                          .setAdminStateUp(nLb.getAdminStateUp)
                          .setPortAddress(IPAddressUtil.toProto(nLb.getVipAddress))
                          .setPeerId(nLb.getVipPortId)
                          .setRouterId(newRouterId)
                          .build

        tx.create(newRouter)
        tx.create(lb)
        tx.create(newPort)
    }

    override protected def translateDelete(tx: Transaction, id: UUID): Unit = {
        val routerId = lbV2RouterId(id)
        tx.delete(classOf[LoadBalancer], id, ignoresNeo = true)
        tx.delete(classOf[Router], routerId, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           nLb: NeutronLoadBalancerV2)
    : Unit = {
        // Only adminStateUp is updateable for LoadBalancerV2
        val oldLb = tx.get(classOf[LoadBalancer], nLb.getId)
        val updatedLb = oldLb.toBuilder
            .setAdminStateUp(nLb.getAdminStateUp)
            .build()
        tx.update(updatedLb)
    }

}
