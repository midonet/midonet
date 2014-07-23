/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
import org.midonet.midolman.state.l4lb.VipSessionPersistence
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager.LoadBalancerConfig
import org.midonet.packets.IPv4Addr

object LoadBalancerManager {
    case class TriggerUpdate(cfg: LoadBalancerConfig, vips: Set[VIP])

    private def toSimulationVip(dataVip: VIP): simulation.VIP =
        new simulation.VIP(
            dataVip.getId,
            dataVip.getAdminStateUp,
            dataVip.getPoolId,
            IPv4Addr(dataVip.getAddress),
            dataVip.getProtocolPort,
            dataVip.getSessionPersistence == VipSessionPersistence.SOURCE_IP
        )
}

class LoadBalancerManager(val id: UUID, val clusterClient: Client) extends Actor
    with ActorLogWithoutPath {

    import LoadBalancerManager._
    import context.system

    override def preStart() {
        clusterClient.getLoadBalancer(id, new LoadBalancerBuilderImpl(self))
    }

    private def publishUpdate(loadBalancer: LoadBalancer) {
        log.debug("Publishing LoadBalancer {} to VTA.", id)
        VirtualTopologyActor ! loadBalancer
        VirtualTopologyActor ! InvalidateFlowsByTag(loadBalancer.deviceTag)
    }

    override def receive = {
        case TriggerUpdate(cfg, vips) => {
            // Send the VirtualTopologyActor an updated loadbalancer.
            log.debug("Update triggered for loadBalancer ID {}", id)

            // Convert data objects to simulation objects before creating LoadBalancer
            val simulationVips =
                vips.map(toSimulationVip)(scala.collection.breakOut(Array.canBuildFrom))

            publishUpdate(new LoadBalancer(id, cfg.adminStateUp, cfg.routerId,
                simulationVips, context.system.eventStream))
        }
    }

    class LoadBalancerBuilderImpl(val loadBalancerMgr: ActorRef)
        extends LoadBalancerBuilder {

        import LoadBalancerManager.TriggerUpdate

        private val cfg = new LoadBalancerConfig
        private var vipSet: Set[VIP] = null
        private var adminStateSet: Boolean = false

        override def setAdminStateUp(adminStateUp: Boolean) {
            cfg.setAdminStateUp(adminStateUp)
            adminStateSet = true
        }

        override def setRouterId(routerId: UUID) {
            cfg.routerId = routerId
        }

        override def setVips(vipMap: JMap[UUID, VIP]) {
            vipSet = vipMap.values().asScala.toSet
            build()
        }

        override def build() {
            if (adminStateSet && vipSet != null) {
                loadBalancerMgr ! TriggerUpdate(cfg, vipSet)
            }
        }
    }

}
