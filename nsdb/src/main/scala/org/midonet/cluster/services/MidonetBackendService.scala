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

import scala.util.control.NonFatal
import scala.collection.JavaConversions._
import com.codahale.metrics.MetricRegistry
import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.slf4j.LoggerFactory.getLogger
import org.midonet.cluster.data.{ZoomInit, ZoomInitializer}
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.C3POState
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.{ActualReflections, ReflectionsHolder}
import org.midonet.conf.HostIdGenerator

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
    final val FloodingProxyKey = "flooding_proxy"
    final val HostKey = "host"
    final val RoutesKey = "routes"
    final val VtepConfig = "config"
    final val VtepConnState = "connection_state"
    final val VtepVxgwManager = "vxgw_manager"

    /** Configures a brand new ZOOM instance with all the classes and bindings
      * supported by MidoNet. */
    final def setupBindings(store: Storage, stateStore: StateStorage,
                            setup: () => Unit = () => {}): Unit = {
        List(classOf[AgentMembership],
             classOf[BgpNetwork],
             classOf[BgpPeer],
             classOf[C3POState],
             classOf[Chain],
             classOf[Dhcp],
             classOf[DhcpV6],
             classOf[FloatingIp],
             classOf[HealthMonitor],
             classOf[Host],
             classOf[IPAddrGroup],
             classOf[LoadBalancer],
             classOf[L2Insertion],
             classOf[Mirror],
             classOf[Network],
             classOf[NeutronConfig],
             classOf[NeutronFirewall],
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
             classOf[TraceRequest],
             classOf[TunnelZone],
             classOf[Vip],
             classOf[Vtep]
        ).foreach(store.registerClass)

        store.declareBinding(classOf[Port], "insertion_ids", CASCADE,
                             classOf[L2Insertion], "port_id", CLEAR)
        store.declareBinding(classOf[Port], "srv_insertion_ids", CASCADE,
                             classOf[L2Insertion], "srv_port_id", CLEAR)

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

        store.declareBinding(classOf[Network], "outbound_mirror_ids", CLEAR,
                             classOf[Mirror], "network_outbound_ids", CLEAR)
        store.declareBinding(classOf[Router], "outbound_mirror_ids", CLEAR,
                             classOf[Mirror], "router_outbound_ids", CLEAR)
        store.declareBinding(classOf[Port], "outbound_mirror_ids", CLEAR,
                             classOf[Mirror], "port_outbound_ids", CLEAR)

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

        stateStore.registerKey(classOf[Host], AliveKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Host], HostKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Port], ActiveKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Port], BgpKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Port], RoutesKey, Multiple)
        stateStore.registerKey(classOf[TunnelZone], FloodingProxyKey, SingleLastWriteWins)
        stateStore.registerKey(classOf[Vtep], VtepConfig, SingleLastWriteWins)
        stateStore.registerKey(classOf[Vtep], VtepConnState, SingleLastWriteWins)
        stateStore.registerKey(classOf[Vtep], VtepVxgwManager, SingleFirstWriteWins)

        setup()
        store.build()
    }

    /** This method allows hooks to be inserted in the classpath, so that setup
      * code can be executed before the midonet backend is built. Such hook
      * classes must implement ZoomInitializer and have the @ZoomInit
      * annotation. */
    final def setupFromClasspath(store: Storage, stateStore: StateStorage,
                                 reflections: ReflectionsHolder): Unit = {
        reflections match {
            case ActualReflections(refl) =>
                log.info("Scanning classpath for storage initializing hooks...")

                val zoomIniters = refl.getSubTypesOf(classOf[ZoomInitializer])

                zoomIniters.filter(_.getAnnotation(classOf[ZoomInit]) != null)
                    .foreach { i => try {
                        val zoomIniterObj = i.getConstructors()(0)
                            .newInstance()
                            .asInstanceOf[ZoomInitializer]
                        zoomIniterObj.setup(store, stateStore)
                        log.info(s"Initialize storage from $i")
                    } catch {
                        case NonFatal(e) =>
                            log.warn(s"Could not initialize storage from $i", e)
                    }
                }

            case _ =>
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
    /** The Curator instance being used */
    def curator: CuratorFramework
}

/** Class responsible for providing services to access to the new Storage
  * services. */
class MidonetBackendService @Inject() (cfg: MidonetBackendConfig,
                                       override val curator: CuratorFramework,
                                       metricRegistry: MetricRegistry,
                                       reflections: ReflectionsHolder)
    extends MidonetBackend {

    import MidonetBackend.setupFromClasspath

    private val log = getLogger("org.midonet.nsdb")

    private val namespaceId =
        if (MidonetBackend.isCluster) MidonetBackend.ClusterNamespaceId
        else HostIdGenerator.getHostId

    private val zoom =
        new ZookeeperObjectMapper(cfg, namespaceId.toString,
                                  curator, metricRegistry)

    override def store: Storage = zoom
    override def stateStore: StateStorage = zoom

    protected override def doStart(): Unit = {
        log.info("Starting backend store for host {}", namespaceId)
        try {
            if (curator.getState != CuratorFrameworkState.STARTED) {
                curator.start()
            }
            log.info("Setting up storage bindings")
            MidonetBackend.setupBindings(zoom, zoom, () => {
                setupFromClasspath(zoom, zoom, reflections)
            })
            notifyStarted()
        } catch {
            case NonFatal(e) =>
                log.error("Failed to start backend service", e)
                this.notifyFailed(e)
        }
    }

    protected def doStop(): Unit = {
        log.info("Stopping backend store for host {}", namespaceId)
        curator.close()
        notifyStopped()
    }
}
