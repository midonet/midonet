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
import java.util.concurrent.{ExecutorService, ScheduledExecutorService}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.google.common.util.concurrent.AbstractService

import io.netty.channel.nio.NioEventLoopGroup

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.state.ConnectionState
import org.reflections.Reflections
import org.slf4j.LoggerFactory.getLogger

import rx.Observable

import org.midonet.cluster.backend.zookeeper.{ZkConnection, ZkConnectionAwareWatcher, ZookeeperConnectionWatcher}
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage._
import org.midonet.cluster.data.{ZoomInit, ZoomInitializer}
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.c3po.C3POState
import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetDiscoveryImpl}
import org.midonet.cluster.services.state.StateProxyService
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.{ConnectionState => StateClientConnectionState}
import org.midonet.cluster.services.state.client.{StateProxyClient, StateSubscriptionKey, StateTableClient}
import org.midonet.cluster.storage.{CuratorZkConnection, MidonetBackendConfig}
import org.midonet.cluster.util.ConnectionObservable
import org.midonet.conf.HostIdGenerator
import org.midonet.util.concurrent.Executors
import org.midonet.util.eventloop.{Reactor, TryCatchReactor}

object MidonetBackend {

    private val log = getLogger("org.midonet.nsdb")

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
    final val MacTable = "mac_table"
    final val Ip4MacTable = "ip4_mac_table"
    final val PeeringTable = "peering_table"

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

        setup()
        store.build()
    }

    /** This method allows hooks to be inserted in the classpath, so that setup
      * code can be executed before the midonet backend is built. Such hook
      * classes must implement ZoomInitializer and have the @ZoomInit
      * annotation. */
    final def setupFromClasspath(store: Storage, stateStore: StateStorage,
                                 reflections: Reflections): Unit = {
        log.info("Scanning classpath for storage initializing hooks...")

        val zoomIniters = reflections.getSubTypesOf(classOf[ZoomInitializer])

        zoomIniters.filter(_.getAnnotation(classOf[ZoomInit]) != null)
            .foreach{ zi => try {
                val zoomIniterObj = zi.getConstructors()(0)
                    .newInstance()
                    .asInstanceOf[ZoomInitializer]
                zoomIniterObj.setup(store, stateStore)
                log.info(s"Initialize storage from $zi")
            } catch {
                case NonFatal(e) =>
                    log.warn(s"Could not initialize storage from $zi", e)
            }
        }
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
        *failure detection of ephemeral nodes. */
    def failFastCurator: CuratorFramework
    /** Provides an executor for handing of asynchronous storage events. */
    def reactor: Reactor
    /** Wraps the legacy ZooKeeper connection around the Curator instance. */
    @Deprecated
    def connection: ZkConnection
    /** Watches the storage connection. */
    def connectionWatcher: ZkConnectionAwareWatcher

    /** Emits notifications with the current connection state for
      * [[curator]]. */
    def connectionState: Observable[ConnectionState]
    /** Emits notifications with the current connection state for
      * [[failFastCurator]]. */
    def failFastConnectionState: Observable[ConnectionState]
    /** Provides access to the state table client. */
    def stateTableClient: StateTableClient
}

/** Class responsible for providing services to access to the new Storage
  * services. */
class MidonetBackendService(config: MidonetBackendConfig,
                            override val curator: CuratorFramework,
                            override val failFastCurator: CuratorFramework,
                            metricRegistry: MetricRegistry,
                            reflections: Option[Reflections])
    extends MidonetBackend {

    import MidonetBackend.setupFromClasspath

    private val log = getLogger("org.midonet.nsdb")

    private val namespaceId =
        if (MidonetBackend.isCluster) MidonetBackend.ClusterNamespaceId
        else HostIdGenerator.getHostId

    private var discoveryServiceExecutor: ExecutorService = null
    private var discoveryService: MidonetDiscovery = null
    private var stateProxyClientExecutor : ScheduledExecutorService = null
    private var stateProxyClient: StateProxyClient = null


    override val reactor = new TryCatchReactor("nsdb", 1)
    override val connection = new CuratorZkConnection(curator, reactor)
    override val connectionWatcher =
        ZookeeperConnectionWatcher.createWith(config, reactor, connection)
    override val connectionState =
        ConnectionObservable.create(curator)
    override val failFastConnectionState =
        ConnectionObservable.create(failFastCurator)

    private val stateTables = new StateTableClient {
        override def stop(): Boolean = false
        override def observable(table: StateSubscriptionKey): Observable[Notify.Update] =
            Observable.never()
        override def connection: Observable[StateClientConnectionState] =
            Observable.never()
        override def start(): Unit = { }
    }

    private val zoom =
        new ZookeeperObjectMapper(config.rootKey, namespaceId.toString, curator,
                                  failFastCurator, stateTables, reactor,
                                  metricRegistry)

    override def store: Storage = zoom
    override def stateStore: StateStorage = zoom
    override def stateTableStore: StateTableStorage = zoom

    override def stateTableClient: StateTableClient = stateProxyClient

    protected def setup(stateTableStorage: StateTableStorage): Unit = { }

    protected override def doStart(): Unit = {
        log.info("Starting backend store for host {}", namespaceId)
        try {
            if (curator.getState != CuratorFrameworkState.STARTED) {
                curator.start()
            }
            if (failFastCurator.getState != CuratorFrameworkState.STARTED) {
                failFastCurator.start()
            }
            discoveryServiceExecutor = Executors.singleThreadScheduledExecutor(
                "discovery-service", isDaemon = true, Executors.CallerRunsPolicy)
            discoveryService = new MidonetDiscoveryImpl(curator,
                                                        discoveryServiceExecutor,
                                                        config)

            if (config.stateClient.enabled) {
                stateProxyClientExecutor = Executors
                    .singleThreadScheduledExecutor(
                        StateProxyService.Name,
                        isDaemon = true,
                        Executors.CallerRunsPolicy)
                val ec = ExecutionContext.fromExecutor(stateProxyClientExecutor)
                val numNettyThreads = config.stateClient.numNetworkThreads
                val eventLoopGroup = new NioEventLoopGroup(numNettyThreads)

                stateProxyClient = new StateProxyClient(
                    config.stateClient,
                    discoveryService,
                    stateProxyClientExecutor,
                    eventLoopGroup)(ec)

                stateProxyClient.start()
            }

            notifyStarted()
            log.info("Setting up storage bindings")
            MidonetBackend.setupBindings(zoom, zoom, () => {
                setup(zoom)
                reflections.foreach(setupFromClasspath(zoom, zoom, _))
            })
        } catch {
            case NonFatal(e) =>
                log.error("Failed to start backend service", e)
                this.notifyFailed(e)
        }
    }

    protected def doStop(): Unit = {
        log.info("Stopping backend store for host {}", namespaceId)
        reactor.shutDownNow()
        curator.close()
        failFastCurator.close()
        notifyStopped()
        if (config.stateClient.enabled) {
            stateProxyClient.stop()
            stateProxyClientExecutor.shutdown()
        }
        discoveryService.stop()
        discoveryServiceExecutor.shutdown()
    }
}
