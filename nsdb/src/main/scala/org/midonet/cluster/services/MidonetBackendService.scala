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
package org.midonet.cluster.services

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject

import org.apache.curator.framework.CuratorFramework
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.{OwnershipType, Storage, StorageWithOwnership, ZookeeperObjectMapper}
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.C3POState
import org.midonet.cluster.storage.MidonetBackendConfig

/** The trait that models the new Midonet Backend, managing all relevant
  * connections and APIs to interact with backend storages. */
abstract class MidonetBackend extends AbstractService {
    /** Indicates whether the new backend stack is active */
    def isEnabled = false
    /** Provides access to the Topology storage API */
    def store: Storage
    def ownershipStore: StorageWithOwnership

    /** Configures a brand new ZOOM instance with all the classes and bindings
      * supported by MidoNet. */
    final def setupBindings(): Unit = {
        List(classOf[AgentMembership],
             classOf[BGP],
             classOf[BGPRoute],
             classOf[C3POState],
             classOf[Chain],
             classOf[Dhcp],
             classOf[FloatingIp],
             classOf[HealthMonitor],
             classOf[IPAddrGroup],
             classOf[LoadBalancer],
             classOf[Network],
             classOf[NeutronConfig],
             classOf[NeutronHealthMonitor],
             classOf[NeutronLoadBalancerPool],
             classOf[NeutronLoadBalancerPoolMember],
             classOf[NeutronNetwork],
             classOf[NeutronPort],
             classOf[NeutronRouter],
             classOf[NeutronRouterInterface],
             classOf[NeutronSubnet],
             classOf[NeutronVIP],
             classOf[Pool],
             classOf[PoolMember],
             classOf[Port],
             classOf[PortBinding],
             classOf[PortGroup],
             classOf[Route],
             classOf[Router],
             classOf[Rule],
             classOf[TunnelZone],
             classOf[SecurityGroup],
             classOf[VIP],
             classOf[Vtep],
             classOf[VtepBinding]
        ).foreach(store.registerClass)

        ownershipStore.registerClass(classOf[Host], OwnershipType.Exclusive)

        store.declareBinding(classOf[Network], "port_ids", CASCADE,
                             classOf[Port], "network_id", CLEAR)
        store.declareBinding(classOf[Network], "dhcp_ids", CASCADE,
                             classOf[Dhcp], "network_id", CLEAR)

        store.declareBinding(classOf[Port], "peer_id", CLEAR,
                             classOf[Port], "peer_id", CLEAR)
        store.declareBinding(classOf[Port], "dhcp_id", CLEAR,
                             classOf[Dhcp], "router_gw_port_id", CLEAR)
        store.declareBinding(classOf[Port], "host_id", CLEAR,
                             classOf[Host], "port_ids", CLEAR)
        store.declareBinding(classOf[Port], "port_group_ids", CLEAR,
                             classOf[PortGroup], "port_ids", CLEAR)
        store.declareBinding(classOf[Port], "route_ids", CASCADE,
                             classOf[Route], "next_hop_port_id", CLEAR)

        store.declareBinding(classOf[Router], "port_ids", CASCADE,
                             classOf[Port], "router_id", CLEAR)
        store.declareBinding(classOf[Router], "route_ids", CASCADE,
                             classOf[Route], "router_id", CLEAR)

        store.declareBinding(classOf[Host], "tunnel_zone_ids", CLEAR,
                             classOf[TunnelZone], "host_ids", CLEAR)

        store.declareBinding(classOf[LoadBalancer], "pool_ids", CASCADE,
                             classOf[Pool], "load_balancer_id", CLEAR)
        store.declareBinding(classOf[Pool], "pool_member_ids", CASCADE,
                             classOf[PoolMember], "pool_id", CLEAR)

        store.declareBinding(classOf[Router], "load_balancer_id", CASCADE,
                             classOf[LoadBalancer], "router_id", CLEAR)

        store.declareBinding(classOf[LoadBalancer], "vip_ids", ERROR,
                             classOf[VIP], "load_balancer_id", CLEAR)

        store.declareBinding(classOf[Port], "bgp_id", CLEAR,
                             classOf[BGP], "port_id", CLEAR)
        store.declareBinding(classOf[BGP], "bgp_route_ids", CASCADE,
                             classOf[BGPRoute], "bgp_id", CLEAR)

        store.build()
    }

}

/** Class responsible for providing services to access to the new Storage
  * services. */
class MidonetBackendService @Inject() (cfg: MidonetBackendConfig,
                                       curator: CuratorFramework)
    extends MidonetBackend {

    private val zoom =
        new ZookeeperObjectMapper(cfg.rootKey + "/zoom", curator)

    override def store: Storage = zoom
    override def ownershipStore: StorageWithOwnership = zoom
    override def isEnabled = cfg.useNewStack

    protected override def doStart(): Unit = {
        LoggerFactory.getLogger(this.getClass).info(s"Starting backend ${this}")
        try {
            if (cfg.curatorEnabled || cfg.useNewStack) {
                curator.start()
            }
            if (cfg.useNewStack) {
                setupBindings()
            }
            notifyStarted()
        } catch {
            case e: Exception => this.notifyFailed(e)
        }
    }

    protected def doStop(): Unit = {
        curator.close()
        notifyStopped()
    }
}
