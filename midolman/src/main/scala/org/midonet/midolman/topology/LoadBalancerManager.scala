/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import akka.actor.{ActorRef, Actor}
import collection.JavaConverters._
import java.util.{Map => JMap, UUID}

import org.midonet.cluster.Client
import org.midonet.cluster.client.LoadBalancerBuilder
import org.midonet.cluster.data.l4lb.VIP
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.simulation
import org.midonet.midolman.simulation.LoadBalancer
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager.LoadBalancerConfig

object LoadBalancerManager {
    case class TriggerUpdate(cfg: LoadBalancerConfig, vips: Set[VIP])
}

class LoadBalancerManager(val id: UUID, val clusterClient: Client) extends Actor
    with ActorLogWithoutPath {

    import LoadBalancerManager._
    import context.system // Used implicitly. Don't delete.

    override def preStart() {
        clusterClient.getLoadBalancer(id, new LoadBalancerBuilderImpl(self))
    }

    private def publishUpdate(loadBalancer: LoadBalancer) {
        VirtualTopologyActor ! loadBalancer
        VirtualTopologyActor ! InvalidateFlowsByTag(
            FlowTagger.invalidateFlowsByDevice(id))
    }

    override def receive = {
        case TriggerUpdate(cfg, vips) => {
            // Send the VirtualTopologyActor an updated loadbalancer.
            log.debug("Update triggered for loadBalancer ID {}", id)

            // Convert data objects to simulation objects before creating LoadBalancer
            val simulationVips = vips.map(vip => new simulation.VIP(vip))

            publishUpdate(new LoadBalancer(id, cfg.adminStateUp, simulationVips,
                context.system.eventStream))
        }
    }

    class LoadBalancerBuilderImpl(val loadBalancerMgr: ActorRef)
        extends LoadBalancerBuilder {

        import LoadBalancerManager.TriggerUpdate

        private val cfg = new LoadBalancerConfig
        private var vipSet: Set[VIP] = null

        override def setAdminStateUp(adminStateUp: Boolean) {
            cfg.setAdminStateUp(adminStateUp)
        }

        override def setVips(vipMap: JMap[UUID, VIP]) {
            vipSet = vipMap.values().asScala.toSet
        }

        override def build() {
            loadBalancerMgr ! TriggerUpdate(cfg, vipSet)
        }
    }

}
