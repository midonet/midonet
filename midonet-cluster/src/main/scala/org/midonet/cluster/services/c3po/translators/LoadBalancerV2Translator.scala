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
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerV2
import org.midonet.cluster.models.Topology.{Port, Router, LoadBalancer}
import org.midonet.cluster.util.IPAddressUtil

/** Provides a Neutron model translator for NeutronLoadBalancerPool. */
class LoadBalancerV2Translator
    extends Translator[NeutronLoadBalancerV2] with LoadBalancerManager {

    override protected def translateCreate(tx: Transaction,
                                           nLb: NeutronLoadBalancerV2)
    : Unit = {
        if (!nLb.hasVipPortId || !nLb.hasVipAddress)
            throw new IllegalArgumentException(
                "VIP port must be created and specified along with the VIP address.")

        val newRouterId = lbRouterId(nLb.getId)

        log.debug(s"MIKEMIKE: newRouterId=$newRouterId")

        // If anything already exists, it's an error
        if (tx.exists(classOf[LoadBalancer], nLb.getId)) {
            throw new IllegalArgumentException(
                s"Load Balancer ${nLb.getId} already exists.")
        }

        if (tx.exists(classOf[Router], newRouterId)) {
            throw new IllegalArgumentException(
                s"Router for Load Balancer ${nLb.getId} (router ID=$newRouterId) already exists.")
        }

        val newRouterPortId = PortManager.routerInterfacePortPeerId(nLb.getVipPortId)
        if (tx.exists(classOf[Port], newRouterPortId)) {
            throw new IllegalArgumentException(
                s"Peer router port $newRouterPortId on router $newRouterId " +
                "already exists.")
        }

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

    override protected def translateDelete(tx: Transaction,
                                           nLb: NeutronLoadBalancerV2)
    : Unit = {
        if (!tx.exists(classOf[LoadBalancer], nLb.getId)) {
            return
        }
        val lb = tx.get(classOf[LoadBalancer], nLb.getId)
        val routerId = lb.getRouterId
        tx.delete(classOf[LoadBalancer], nLb.getId, ignoresNeo = true)

        if (!tx.exists(classOf[Router], routerId)) {
            return
        }
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
