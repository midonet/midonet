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
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.slf4j.LoggerFactory.getLogger

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.c3po.C3POState
import org.midonet.cluster.storage.MidonetBackendConfig

object MidonetBackend {

    final val AliveKey = "alive"
    final val HostsKey = "hosts"
    final val HostKey = "host"
    final val RoutesKey = "routes"

}

/** The trait that models the new Midonet Backend, managing all relevant
  * connections and APIs to interact with backend storages. */
abstract class MidonetBackend extends AbstractService {
    /** Indicates whether the new backend stack is active */
    def isEnabled = false
    /** Provides access to the Topology storage API */
    def store: Storage
    def stateStore: StateStorage
    def curator: CuratorFramework

    /** Configures a brand new ZOOM instance with all the classes and bindings
      * supported by MidoNet. */
    final def setupBindings(): Unit = {
        List(classOf[AgentMembership],
             classOf[Bgp],
             classOf[BgpRoute],
             classOf[C3POState],
             classOf[Chain],
             classOf[Dhcp],
             classOf[FloatingIp],
             classOf[HealthMonitor],
             classOf[Host],
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
             classOf[SecurityGroup],
             classOf[SecurityGroupRule],
             classOf[TunnelZone],
             classOf[Vip],
             classOf[Vtep],
             classOf[VtepBinding]
        ).foreach(store.registerClass)

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
        store.declareBinding(classOf[Pool], "pool_member_ids", CLEAR,
                             classOf[PoolMember], "pool_id", CLEAR)
        store.declareBinding(classOf[Pool], "health_monitor_id", CLEAR,
                             classOf[HealthMonitor], "pool_id", CLEAR)

        store.declareBinding(classOf[Router], "load_balancer_id", CASCADE,
                             classOf[LoadBalancer], "router_id", CLEAR)

        store.declareBinding(classOf[LoadBalancer], "vip_ids", ERROR,
                             classOf[Vip], "load_balancer_id", CLEAR)
        store.declareBinding(classOf[Vip], "gateway_port_id", CLEAR,
                             classOf[Port], "vip_ids", ERROR)

        store.declareBinding(classOf[Port], "bgp_id", CLEAR,
                             classOf[Bgp], "port_id", CLEAR)
        store.declareBinding(classOf[Bgp], "bgp_route_ids", CASCADE,
                             classOf[BgpRoute], "bgp_id", CLEAR)

        store.declareBinding(classOf[Route], "gateway_dhcp_id", CLEAR,
                             classOf[Dhcp], "gateway_route_ids", CLEAR)

        // Field bindings between Neutron objects
        store.declareBinding(classOf[NeutronPort], "floating_ip_ids", CLEAR,
                             classOf[FloatingIp], "port_id", CLEAR)

        stateStore.registerKey(classOf[Host], AliveKey, SingleFirstWriteWins)
        stateStore.registerKey(classOf[Host], HostKey, SingleFirstWriteWins)
        stateStore.registerKey(classOf[Port], HostsKey, Multiple)
        stateStore.registerKey(classOf[Port], RoutesKey, Multiple)

        store.build()
    }

}

/** Class responsible for providing services to access to the new Storage
  * services. */
class MidonetBackendService @Inject() (cfg: MidonetBackendConfig,
                                       override val curator: CuratorFramework)
    extends MidonetBackend {

    private val log = getLogger("org.midonet.nsdb")

    private val zoom =
        new ZookeeperObjectMapper(cfg.rootKey + "/zoom", curator)

    override def store: Storage = zoom
    override def stateStore: StateStorage = zoom
    override def isEnabled = cfg.useNewStack

    protected override def doStart(): Unit = {
        log.info(s"Starting backend ${this} store: $store")
        try {
            if ((cfg.curatorEnabled || cfg.useNewStack) &&
                curator.getState != CuratorFrameworkState.STARTED) {
                curator.start()
            }
            if (cfg.useNewStack) {
                log.info("Setting up storage bindings")
                setupBindings()
            }
            notifyStarted()
        } catch {
            case e: Exception =>
                log.error("Failed to start backend service", e)
                this.notifyFailed(e)
        }
    }

    protected def doStop(): Unit = {
        curator.close()
        notifyStopped()
    }
}
