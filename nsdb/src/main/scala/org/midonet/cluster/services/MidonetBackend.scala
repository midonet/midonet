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

package org.midonet.cluster.services

import java.util.UUID

import com.google.common.util.concurrent.AbstractService

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState

import rx.Observable

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage.{StateStorage, StateTableStorage, Storage}
import org.midonet.cluster.models.Neutron.{TapService, VpnService, _}
import org.midonet.cluster.models.Topology.{TunnelZone, Vip, Vtep, _}
import org.midonet.cluster.services.c3po.C3POState
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.util.eventloop.Reactor

object MidonetBackend {

    /** Indicates whether the [[MidonetBackend]] instance is used by cluster,
      * in which case the state path uses a unique host identifier. */
    protected[cluster] var isCluster = false
    /** Unique namespace identifier for the cluster in the [[MidonetBackend]].
      * All cluster nodes share the same storage namespace. */
    final val ClusterNamespaceId = new UUID(0L, 0L)

    final val ActiveKey = "active"
    final val AliveKey = "alive"
    final val BgpKey = "bgp"
    final val ContainerKey = "container"
    final val FloodingProxyKey = "flooding_proxy"
    final val HostKey = "host"
    final val RoutesKey = "routes"
    final val StatusKey = "status"
    final val VtepConfig = "config"
    final val VtepConnState = "connection_state"
    final val VtepVxgwManager = "vxgw_manager"

    final val ArpTable = "arp_table"
    final val Fip64Table = "fip64_table"
    final val GatewayTable = "gateway_table"
    final val Ip4MacTable = "ip4_mac_table"
    final val MacTable = "mac_table"
    final val PeeringTable = "peering_table"

    final val NsdbErrorCodeGraceTimeExpired = 7453
    final val NsdbErrorCodeSessionExpired = 7454

    /** Configures a brand new ZOOM instance with all the classes and bindings
      * supported by MidoNet core. It also executes a provided setup function
      * and, if a Reflections object is provided, this method searches the
      * classpath for subclasses of ZoomInitializer which are also annotated
      * with @ZoomInit and runs their setup methods. */
    final def setupBindings(store: Storage, stateStore: StateStorage,
                            setup: () => Unit = () => {})
    : Unit = {
        List(classOf[AgentMembership],
             classOf[BgpNetwork],
             classOf[BgpPeer],
             classOf[C3POState],
             classOf[Chain],
             classOf[Dhcp],
             classOf[DhcpV6],
             classOf[FloatingIp],
             classOf[FirewallLog],
             classOf[GatewayDevice],
             classOf[HealthMonitor],
             classOf[Host],
             classOf[HostGroup],
             classOf[IPAddrGroup],
             classOf[IPSecSiteConnection],
             classOf[L2GatewayConnection],
             classOf[L2Insertion],
             classOf[LoadBalancer],
             classOf[LoggingResource],
             classOf[Mirror],
             classOf[Network],
             classOf[NeutronBgpPeer],
             classOf[NeutronBgpSpeaker],
             classOf[NeutronConfig],
             classOf[NeutronFirewall],
             classOf[NeutronHealthMonitor],
             classOf[NeutronLoadBalancerPool],
             classOf[NeutronLoadBalancerPoolMember],
             classOf[NeutronLoggingResource],
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
             classOf[QosPolicy],
             classOf[QosRuleBandwidthLimit],
             classOf[QosRuleDscp],
             classOf[RemoteMacEntry],
             classOf[Route],
             classOf[Router],
             classOf[Rule],
             classOf[RuleLogger],
             classOf[SecurityGroup],
             classOf[ServiceContainer],
             classOf[ServiceContainerGroup],
             classOf[SecurityGroupRule],
             classOf[TapFlow],
             classOf[TapService],
             classOf[TraceRequest],
             classOf[TunnelZone],
             classOf[Vip],
             classOf[VpnService],
             classOf[Vtep]
        ).foreach(store.registerClass)

        store.declareBinding(classOf[Port], "insertion_ids", CASCADE,
                             classOf[L2Insertion], "port_id", CLEAR)
        store.declareBinding(classOf[Port], "srv_insertion_ids", CASCADE,
                             classOf[L2Insertion], "srv_port_id", CLEAR)

        store.declareBinding(classOf[ServiceContainer], "service_group_id", CLEAR,
                             classOf[ServiceContainerGroup], "service_container_ids", CASCADE)

        store.declareBinding(classOf[Port], "service_container_id", CASCADE,
                             classOf[ServiceContainer], "port_id", CLEAR)

        store.declareBinding(classOf[ServiceContainerGroup], "host_group_id", CLEAR,
                             classOf[HostGroup], "service_group_ids", CLEAR)

        store.declareBinding(classOf[ServiceContainerGroup], "port_group_id", CLEAR,
                             classOf[PortGroup], "service_group_ids", CLEAR)

        store.declareBinding(classOf[Network], "port_ids", CASCADE,
                             classOf[Port], "network_id", CLEAR)
        store.declareBinding(classOf[Network], "dhcp_ids", CASCADE,
                             classOf[Dhcp], "network_id", CLEAR)
        store.declareBinding(classOf[Network], "dhcpv6_ids", CASCADE,
                             classOf[DhcpV6], "network_id", CLEAR)

        store.declareBinding(classOf[Port], "peer_id", CLEAR,
                             classOf[Port], "peer_id", CLEAR)
        store.declareBinding(classOf[Port], "dhcp_id", CLEAR,
                             classOf[Dhcp], "router_if_port_id", CLEAR)
        store.declareBinding(classOf[Port], "host_id", CLEAR,
                             classOf[Host], "port_ids", CLEAR)
        store.declareBinding(classOf[Port], "port_group_ids", CLEAR,
                             classOf[PortGroup], "port_ids", CLEAR)
        store.declareBinding(classOf[Port], "route_ids", CASCADE,
                             classOf[Route], "next_hop_port_id", CLEAR)

        store.declareBinding(classOf[Network], "inbound_filter_id", CLEAR,
                             classOf[Chain], "network_inbound_ids", CLEAR)
        store.declareBinding(classOf[Network], "outbound_filter_id", CLEAR,
                             classOf[Chain], "network_outbound_ids", CLEAR)

        store.declareBinding(classOf[Router], "inbound_filter_id", CLEAR,
                             classOf[Chain], "router_inbound_ids", CLEAR)
        store.declareBinding(classOf[Router], "outbound_filter_id", CLEAR,
                             classOf[Chain], "router_outbound_ids", CLEAR)
        store.declareBinding(classOf[Router], "local_redirect_chain_id", CLEAR,
                             classOf[Chain], "router_redirect_ids", CLEAR)

        store.declareBinding(classOf[Port], "inbound_filter_id", CLEAR,
                             classOf[Chain], "port_inbound_ids", CLEAR)
        store.declareBinding(classOf[Port], "outbound_filter_id", CLEAR,
                             classOf[Chain], "port_outbound_ids", CLEAR)

        store.declareBinding(classOf[Router], "port_ids", CASCADE,
                             classOf[Port], "router_id", CLEAR)
        store.declareBinding(classOf[Router], "route_ids", CASCADE,
                             classOf[Route], "router_id", CLEAR)

        store.declareBinding(classOf[Host], "tunnel_zone_ids", CLEAR,
                             classOf[TunnelZone], "host_ids", CLEAR)
        store.declareBinding(classOf[Host], "host_group_ids", CLEAR,
                             classOf[HostGroup], "host_ids", CLEAR)

        store.declareBinding(classOf[LoadBalancer], "pool_ids", CASCADE,
                             classOf[Pool], "load_balancer_id", CLEAR)
        store.declareBinding(classOf[Pool], "pool_member_ids", CASCADE,
                             classOf[PoolMember], "pool_id", CLEAR)
        store.declareBinding(classOf[Pool], "health_monitor_id", CLEAR,
                             classOf[HealthMonitor], "pool_ids", CLEAR)


        // Mirroring references
        store.declareBinding(classOf[Network], "inbound_mirror_ids", CLEAR,
                             classOf[Mirror], "network_inbound_ids", CLEAR)
        store.declareBinding(classOf[Router], "inbound_mirror_ids", CLEAR,
                             classOf[Mirror], "router_inbound_ids", CLEAR)
        store.declareBinding(classOf[Port], "inbound_mirror_ids", CLEAR,
                             classOf[Mirror], "port_inbound_ids", CLEAR)
        store.declareBinding(classOf[Port], "post_in_filter_mirror_ids", CLEAR,
                             classOf[Mirror], "port_post_in_filter_ids", CLEAR)

        store.declareBinding(classOf[Network], "outbound_mirror_ids", CLEAR,
                             classOf[Mirror], "network_outbound_ids", CLEAR)
        store.declareBinding(classOf[Router], "outbound_mirror_ids", CLEAR,
                             classOf[Mirror], "router_outbound_ids", CLEAR)
        store.declareBinding(classOf[Port], "outbound_mirror_ids", CLEAR,
                             classOf[Mirror], "port_outbound_ids", CLEAR)
        store.declareBinding(classOf[Port], "pre_out_filter_mirror_ids", CLEAR,
                             classOf[Mirror], "port_pre_out_filter_ids", CLEAR)

        store.declareBinding(classOf[Mirror], "to_port_id", CLEAR,
                             classOf[Port], "mirror_ids", CASCADE)


        store.declareBinding(classOf[Router], "load_balancer_id", ERROR,
                             classOf[LoadBalancer], "router_id", CLEAR)

        store.declareBinding(classOf[Pool], "vip_ids", CASCADE,
                             classOf[Vip], "pool_id", CLEAR)
        store.declareBinding(classOf[Vip], "gateway_port_id", CLEAR,
                             classOf[Port], "vip_ids", ERROR)

        store.declareBinding(classOf[Router], "bgp_network_ids", CASCADE,
                             classOf[BgpNetwork], "router_id", CLEAR)
        store.declareBinding(classOf[Router], "bgp_peer_ids", CASCADE,
                             classOf[BgpPeer], "router_id", CLEAR)

        store.declareBinding(classOf[Route], "gateway_dhcp_id", CLEAR,
                             classOf[Dhcp], "gateway_route_ids", CASCADE)

        store.declareBinding(classOf[Rule], "chain_id", CLEAR,
                             classOf[Chain], "rule_ids", CASCADE)
        store.declareBinding(classOf[Rule], "fip_port_id", CLEAR,
                             classOf[Port], "fip_nat_rule_ids", CASCADE)

        // Field bindings between Neutron objects
        store.declareBinding(classOf[NeutronPort], "floating_ip_ids", CLEAR,
                             classOf[FloatingIp], "port_id", CLEAR)
        store.declareBinding(classOf[NeutronPort], "gw_router_id", CLEAR,
                             classOf[NeutronRouter], "gw_port_id", CLEAR)

        store.declareBinding(classOf[Network], "trace_request_ids", CASCADE,
                             classOf[TraceRequest], "network_id", CLEAR)
        store.declareBinding(classOf[Router], "trace_request_ids", CASCADE,
                             classOf[TraceRequest], "router_id", CLEAR)
        store.declareBinding(classOf[Port], "trace_request_ids", CASCADE,
                             classOf[TraceRequest], "port_id", CLEAR)

        store.declareBinding(classOf[RuleLogger], "chain_id", CLEAR,
                             classOf[Chain], "logger_ids", CASCADE)
        store.declareBinding(classOf[RuleLogger], "logging_resource_id", CLEAR,
                             classOf[LoggingResource], "logger_ids", CASCADE)

        stateStore.registerKey(classOf[ServiceContainer], StatusKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Host], AliveKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Host], ContainerKey, FailFast)
        stateStore.registerKey(classOf[Host], HostKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Port], ActiveKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Port], BgpKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Port], RoutesKey, Multiple)
        stateStore.registerKey(classOf[TunnelZone], FloodingProxyKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Vtep], VtepConfig, SingleLastWriteWins)
        stateStore.registerKey(classOf[Vtep], VtepConnState, SingleLastWriteWins)
        stateStore.registerKey(classOf[Vtep], VtepVxgwManager, SingleFirstWriteWins)

        store.declareBinding(classOf[VpnService], "router_id", CLEAR,
                             classOf[Router], "vpn_service_ids", CASCADE)
        store.declareBinding(classOf[VpnService], "ipsec_site_connection_ids", CASCADE,
                             classOf[IPSecSiteConnection], "vpnservice_id", CLEAR)
        store.declareBinding(classOf[IPSecSiteConnection], "route_ids", CASCADE,
                             classOf[Route], "ipsec_site_connection_id", CLEAR)

        store.declareBinding(classOf[GatewayDevice], "remote_mac_entry_ids", CASCADE,
                             classOf[RemoteMacEntry], "device_id", CLEAR)

        store.declareBinding(classOf[RemoteMacEntry], "port_ids", CLEAR,
                             classOf[Port], "remote_mac_entry_ids", CLEAR)

        store.declareBinding(classOf[QosPolicy], "bandwidth_limit_rule_ids", CASCADE,
                             classOf[QosRuleBandwidthLimit], "policy_id", CLEAR)
        store.declareBinding(classOf[QosPolicy], "dscp_marking_rule_ids", CASCADE,
                             classOf[QosRuleDscp], "policy_id", CLEAR)
        store.declareBinding(classOf[Port], "qos_policy_id", CLEAR,
                             classOf[QosPolicy], "port_ids", CLEAR)
        store.declareBinding(classOf[Network], "qos_policy_id", CLEAR,
                             classOf[QosPolicy], "network_ids", CLEAR)

        setup()
        store.build()
    }

}

/** The trait that models the new Midonet Backend, managing all relevant
  * connections and APIs to interact with backend storages. */
abstract class MidonetBackend extends AbstractService {
    /** Indicates whether the new backend stack is active */
    def isEnabled = false
    /** Provides access to the Topology Model API */
    def store: Storage
    /** Provides access to the Topology State API */
    def stateStore: StateStorage
    /** Provides access to the Topology State Tables API */
    def stateTableStore: StateTableStorage
    /** The Curator instance being used */
    def curator: CuratorFramework
    /** A 2nd curator instance with a small session timeout to implement fast
      * failure detection of ephemeral nodes. */
    def failFastCurator: CuratorFramework
    /** Provides an executor for handing of asynchronous storage events. */
    def reactor: Reactor

    /** Emits notifications with the current connection state for
      * [[curator]]. */
    def connectionState: Observable[ConnectionState]
    /** Emits notifications with the current connection state for
      * [[failFastCurator]]. */
    def failFastConnectionState: Observable[ConnectionState]
    /** Provides access to the state table client. */
    def stateTableClient: StateTableClient
}
