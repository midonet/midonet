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

package org.midonet.migrator

import java.io.File
import java.net.URI
import java.nio.file.{Files, Paths}
import java.util
import java.util.UUID
import java.util.concurrent.Callable

import javax.servlet.http.HttpServletRequest
import javax.validation.Validator
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core._

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.control.NonFatal

import com.google.inject.name.Names
import com.google.inject.servlet.{ServletModule, ServletScopes}
import com.google.inject.{AbstractModule, Guice, Key}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import org.eclipse.jetty.server.Request
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory

import org.midonet.cluster.auth.{AuthService, MockAuthService}
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.{ObjectExistsException, StateTableStorage}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.neutron.models.ProviderRouter
import org.midonet.cluster.rest_api.validation.ValidatorProvider
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.ResourceProvider
import org.midonet.cluster.services.rest_api.resources._
import org.midonet.cluster.storage.{LegacyStateTableStorage, MidonetBackendConfig, MidonetBackendModule}
import org.midonet.conf.{MidoNodeConfigurator, FileConf}
import org.midonet.midolman.cluster.LegacyClusterModule
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule.ZookeeperReactorProvider
import org.midonet.midolman.cluster.zookeeper.{DirectoryProvider, ZkConnectionProvider}
import org.midonet.midolman.state.{Directory, ZkConnection, ZkConnectionAwareWatcher, ZookeeperConnectionWatcher}
import org.midonet.packets.IPv4Subnet
import org.midonet.util.eventloop.Reactor

object Migrator extends App {

    private val log = Logger(LoggerFactory.getLogger(this.getClass))

    log.info("Starting Midonet data migration tool.")

    private val config = loadConfig(args)

    // Settings for services depending on the DataClient (1.X) storage module
    private val dataClientDependencies = new AbstractModule {
        override def configure(): Unit = {
            bind(classOf[ZkConnection])
                .toProvider(classOf[ZkConnectionProvider])
                .asEagerSingleton()
            bind(classOf[Directory])
                .toProvider(classOf[DirectoryProvider])
                .asEagerSingleton()
            bind(classOf[Reactor]).annotatedWith(
                Names.named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG))
                .toProvider(classOf[ZookeeperReactorProvider])
                .asEagerSingleton()
            bind(classOf[ZkConnectionAwareWatcher])
                .to(classOf[ZookeeperConnectionWatcher])
                .asEagerSingleton()

            install(new SerializationModule)
            install(new LegacyClusterModule)
        }
    }

    private val newApiModule = new AbstractModule {
        override def configure(): Unit = {
            bind(classOf[AuthService])
                .toInstance(new MockAuthService(ConfigFactory.defaultReference()))
            bind(classOf[ResourceProvider])
                .toInstance(new ResourceProvider(log))
            bind(classOf[StateTableStorage])
                .to(classOf[LegacyStateTableStorage]).asEagerSingleton()
            bind(classOf[UriInfo]).toInstance(MockUriInfo)
            bind(classOf[Validator])
                .toProvider(classOf[ValidatorProvider])
                .asEagerSingleton()

            install(new ServletModule)
            install(new MidonetBackendModule(config))
        }
    }

    protected[migrator] val injector = Guice.createInjector(
        newApiModule, dataClientDependencies)

    private val legacyImporter = injector.getInstance(classOf[LegacyImporter])

    private val backend = injector.getInstance(classOf[MidonetBackend])
    backend.startAsync().awaitRunning()

    private val resources = loadV2Resources()

    migrateData()

    System.exit(0)

    private def loadConfig(args: Array[String]): MidonetBackendConfig = {

        val opts = new ScallopConf(args) {
            banner("Upgrades Midonet 1.9.x topology to 2.0 topology.")

            val zkHost = opt[String](
                "zk-host", 'z', "Zookeeper IP address and port",
                default = Some("127.0.0.1:2181"))
            val timeout = opt[Int](
                "zk-timeout", 't', "Zookeeper connection timeout in seconds",
                default = Some(30))
            val zkRoot = opt[String](
                "zk-root", 'r', "Root Zookeeper path",
                default = Some("/midonet/v1"))
            val maxRetries = opt[Int](
                "max-retries", 'm',
                "Max number of retries for zookeeper operations",
                default = Some(10), noshort = true)
            val baseRetryTime = opt[Int](
                "base-retry-time", 'b',
                "Base retry time in seconds (increases exponentially)",
                default = Some(1))
            val bufferSize = opt[Int](
                "buffer-size", 'B', "Zookeeper buffer size in kilobytes",
                default = Some(4096))
        }

        val conf = ConfigFactory.parseString(
            s"""
              |zookeeper {
              |    use_new_stack = true
              |    curator_enabled = true
              |    zookeeper_hosts = "${opts.zkHost()}"
              |    session_timeout = ${opts.timeout()}s
              |    session_timeout_type: "duration"
              |    session_gracetime = ${opts.timeout()}s
              |    session_gracetime_type: "duration"
              |    root_key = "${opts.zkRoot()}"
              |    max_retries = ${opts.maxRetries()}
              |    base_retry = ${opts.baseRetryTime()}s
              |    base_retry_type = "duration"
              |    buffer_size = ${opts.bufferSize() * 1024}
              |}
            """.stripMargin)

        new MidonetBackendConfig(conf)
    }

    private object MockUriInfo extends UriInfo {
        override def getRequestUri: URI = ???
        override def getBaseUriBuilder: UriBuilder = ???
        override def getMatchedResources: util.List[AnyRef] = ???
        override def getQueryParameters: MultivaluedMap[String, String] = ???
        override def getQueryParameters(decode: Boolean): MultivaluedMap[String, String] = ???
        override def getMatchedURIs: util.List[String] = ???
        override def getMatchedURIs(decode: Boolean): util.List[String] = ???
        override def getAbsolutePathBuilder: UriBuilder = ???
        override def getPathSegments: util.List[PathSegment] = ???
        override def getPathSegments(decode: Boolean): util.List[PathSegment] = ???
        override def getBaseUri: URI = new URI("")
        override def getAbsolutePath: URI = ???
        override def getPath: String = ???
        override def getPath(decode: Boolean): String = ???
        override def getRequestUriBuilder: UriBuilder = ???
        override def getPathParameters: MultivaluedMap[String, String] = ???
        override def getPathParameters(decode: Boolean): MultivaluedMap[String, String] = ???
    }

    /**
     * Load the V2 ApplicationResource. Needs to be scoped inside a request
     * because the TenantResource injects an HttpServletRequest
     */
    private def loadV2Resources(): Resources = {
        val seedMap = new util.HashMap[Key[_], Object]()
        seedMap.put(Key.get(classOf[HttpServletRequest]), new Request(null, null))
        ServletScopes.scopeRequest(
            new Callable[Resources] {
                override def call(): Resources = new Resources(
                    injector.getInstance(classOf[BridgeResource]),
                    injector.getInstance(classOf[ChainResource]),
                    injector.getInstance(classOf[HealthMonitorResource]),
                    injector.getInstance(classOf[IpAddrGroupResource]),
                    injector.getInstance(classOf[LoadBalancerResource]),
                    injector.getInstance(classOf[PoolResource]),
                    injector.getInstance(classOf[PortGroupResource]),
                    injector.getInstance(classOf[RouterResource]),
                    injector.getInstance(classOf[TraceRequestResource]),
                    injector.getInstance(classOf[TunnelZoneResource]),
                    injector.getInstance(classOf[VipResource]))
            }, seedMap).call()
    }

    case class Resources(bridges: BridgeResource,
                         chains: ChainResource,
                         healthMonitors: HealthMonitorResource,
                         ipAddrGroups: IpAddrGroupResource,
                         loadBalancers: LoadBalancerResource,
                         pools: PoolResource,
                         portGroups: PortGroupResource,
                         routers: RouterResource,
                         traceRequests: TraceRequestResource,
                         tunnelZones: TunnelZoneResource,
                         vips: VipResource)

    private def migrateData(): Unit = {
        try {
            // Need the routers for router migration and router IDs for
            // route migration.
            val routers = legacyImporter.listRouters
            val routerIds = routers.map(_.id)

            // The order of these methods is important, as some types cannot be
            // migrated until certain other types have been migrated. See method
            // JavaDoc comments for prerequisites.

            migrateHosts()
            migrateTunnelZones()
            migrateIpAddrGroups()
            migrateChains()
            migrateBridges()
            migrateRouters(routers)
            migratePortGroups()
            migratePorts()
            migrateRoutes(routerIds)
            migrateTraceRequests()

            migrateHealthMonitors()
            migrateLoadBalancers()
            migratePools()
            migratePoolMembers()
            migrateVips()

            log.info("Finished migrating topology data.")
            System.exit(0)
        } catch {
            case ex: WebApplicationException =>
                val msg = getWebAppExErrorMsg(ex)
                log.error(msg, ex)
                System.err.println(msg)
                System.exit(1)
            case NonFatal(t) =>
                log.error("Error migrating data.", t)
                System.err.println("ERROR: " + t.getMessage)
                System.exit(1)
        }
    }

    /** Migrates Hosts. Prerequisites: None. */
    private def migrateHosts(): Unit = {
        for (h <- legacyImporter.listHosts) {
            log.info("Migrating " + h)
            // Ports aren't migrated yet, so these are invalid references.
            // Zoom will add these back when we migrate the ports.
            h.portIds = null

            // Host creation not supported via API.
            val ph = ZoomConvert.toProto(h, classOf[Topology.Host])
            try backend.store.create(ph) catch {
                case ex: ObjectExistsException =>
                    log.warn(s"A Host with ID ${h.id} already exists.")
            }
        }
    }

    /** Migrates Bridges and their Subnets. Prerequisites: Chains. */
    private def migrateBridges(): Unit = {
        for (b <- legacyImporter.listBridges) {
            log.info("Migrating " + b)

            // The API won't accept a bridge with this field set. Zoom will fill
            // it in later when we migrate the VXLAN port(s).
            b.vxLanPortIds = null

            val resp = resources.bridges.create(b, APPLICATION_BRIDGE_JSON_V4)
            handleResponse(resp)

            migrateDhcpSubnets(b.id)
        }
    }

    /**
     * Migrates DhcpSubnets for the specified bridge.
     *
     * TODO: IPv6, too?
     */
    private def migrateDhcpSubnets(bridgeId: UUID): Unit = {
        for (s <- legacyImporter.listDhcpSubnets(bridgeId)) {
            log.info("Migrating " + s)
            val resp = resources.bridges.dhcps(bridgeId)
                .create(s, APPLICATION_DHCP_SUBNET_JSON_V2)
            handleResponse(resp)

            migrateDhcpHosts(bridgeId, s.subnetAddress.asInstanceOf[IPv4Subnet])
        }
    }

    /**
     * Migrates DhcpHosts for the specified bridge and subnet.
     *
     * TODO: IPv6, too?
     */
    private def migrateDhcpHosts(bridgeId: UUID, subnet: IPv4Subnet): Unit = {
        for (h <- legacyImporter.listDhcpHosts(bridgeId, subnet)) {
            log.info("Migrating " + h)
            val resp = resources.bridges.dhcps(bridgeId).hosts(subnet)
                .create(h, APPLICATION_DHCP_HOST_JSON_V2)
            handleResponse(resp)
        }
    }

    /** Migrates routers. Prerequisites: Chains. */
    private def migrateRouters(routers: TraversableOnce[Router]): Unit = {
        for (r <- routers) {
            log.info("Migrating " + r)
            if (r.tenantId == null) migrateTenantlessRouter(r)
            else {
                val resp = resources.routers
                    .create(r, APPLICATION_ROUTER_JSON_V3)
                handleResponse(resp)
            }
        }
    }

    /** Migrate ports. Prerequisites: Bridges, Routers, Hosts, TunnelZones */
    private def migratePorts(): Unit = {
        val migratedPorts = mutable.Set[UUID]()
        for (p <- legacyImporter.listPorts) {
            log.info("Migrating " + p)
            // Can't create a port with a reference to a non-existing peer.
            // Clear it now, and Zoom will fix it when we create the peer.
            if (p.peerId != null && !migratedPorts(p.peerId))
                p.peerId = null

            val resp = p match {
                case bp: BridgePort =>
                    resources.bridges.ports(bp.bridgeId)
                        .create(bp, APPLICATION_PORT_V2_JSON)
                case rp: RouterPort =>
                    resources.routers.ports(rp.routerId)
                        .create(rp, APPLICATION_PORT_V2_JSON)
                case vp: VxLanPort =>
                    log.warn(s"Skipping port ${p.id}; VXLAN ports not yet " +
                             s"supported.")
                    null
            }
            if (resp != null) handleResponse(resp)

            migratedPorts += p.id
        }
    }

    private def migrateTenantlessRouter(r: Router): Unit = {
        if (r.name != ProviderRouter.NAME)
            log.error(s"Router ${r.id}, has no tenantId, but is not the " +
                      "provider router. This may indicate corruption.")

        // Need to bypass RouterResource because it won't accept a router with
        // no tenant.
        val pr = ZoomConvert.toProto(r, classOf[Topology.Router])
        try backend.store.create(pr) catch {
            case ex: ObjectExistsException =>
                log.warn(s"A Router with ID ${r.id} already exists.")
        }
    }

    /** Migrates tunnel zones. Prerequisites: None. */
    private def migrateTunnelZones(): Unit = {
        for (tz <- legacyImporter.listTunnelZones) {
            log.info("Migrating " + tz)
            val resp = resources.tunnelZones.create(
                tz, APPLICATION_TUNNEL_ZONE_JSON)
            handleResponse(resp)
        }
    }

    /**
     * Migrates chains and their associated rules.
     * Prerequisites: IpAddrGroups.
     */
    private def migrateChains(): Unit = {

        // First migrate the chains. We need to migrate all chains before
        // migrating rules in order to avoid migrating a jump rule whose target
        // chain hasn't been migrated yet.
        val chains = legacyImporter.listChains
        for (chain <- chains) {
            log.info("Migrating " + chain)

            // Clear rule IDs, since they refer to rules that don't exist yet.
            chain.ruleIds = null

            val resp = resources.chains.create(chain, APPLICATION_CHAIN_JSON)
            handleResponse(resp)
        }

        // Now migrate the rules.
        for (chain <- chains) {
            val ruleResource = resources.chains.rules(chain.id)
            for (rule <- legacyImporter.listRules(chain.id)) {
                log.info("Migrating " + rule)
                rule match {
                    case nr: NatRule if nr.flowAction == null =>
                        val flowAction = nr.action.toString.toLowerCase
                        log.info(s"NatRule ${nr.id} has null flowAction." +
                                 s"Setting to $flowAction based on value of" +
                                 s"action.")
                        nr.flowAction = nr.action.toString.toLowerCase
                    case _ => // Other rules are fine as is.
                }
                val resp = ruleResource.create(rule, APPLICATION_RULE_JSON_V2)
                handleResponse(resp)
            }
        }
    }

    /** Migrates port groups. Prerequisites: None. */
    private def migratePortGroups(): Unit = {
        for (pg <- legacyImporter.listPortGroups) {
            log.info("Migrating " + pg)
            val resp = resources.portGroups
                .create(pg, APPLICATION_PORTGROUP_JSON)
            handleResponse(resp)
        }
    }

    /** Migrates routes. Prerequisites: Routers, Ports */
    private def migrateRoutes(routerIds: TraversableOnce[UUID]): Unit = {
        for (rtrId <- routerIds;
             rt <- legacyImporter.listRoutes(rtrId)) {
            log.info("Migrating " + rt)
            val resp = resources.routers.routes(rtrId)
                .create(rt, APPLICATION_ROUTE_JSON)
            handleResponse(resp)
        }
    }

    /** Migrates IPAddrGroups. Prerequisites: None */
    private def migrateIpAddrGroups(): Unit = {
        for (ipg <- legacyImporter.listIpAddrGroups) {
            log.info("Migrating " + ipg)
            val resp = resources.ipAddrGroups
                .create(ipg, APPLICATION_IP_ADDR_GROUP_JSON)
            handleResponse(resp)

            for (addr <- legacyImporter.listIpAddrGroupAddrs(ipg.id)) {
                log.info("Migrating " + addr)
                val resp = resources.ipAddrGroups.ports(ipg.id).create(addr)
                handleResponse(resp)
            }
        }
    }

    /** Migrates health monitors. Prerequisites: None. */
    private def migrateHealthMonitors(): Unit = {
        for (hm <- legacyImporter.listHealthMonitors) {
            log.info("Migrating " + hm)
            val resp = resources.healthMonitors
                .create(hm, APPLICATION_HEALTH_MONITOR_JSON)
            handleResponse(resp)
        }
    }

    /** Migrates load balancers. Prerequisites: None. */
    private def migrateLoadBalancers(): Unit = {
        for (lb <- legacyImporter.listLoadBalancers) {
            log.info("Migrating " + lb)
            val resp = resources.loadBalancers
                .create(lb, APPLICATION_LOAD_BALANCER_JSON)
            handleResponse(resp)
        }
    }

    /** Migrates pools. Prerequisites: LoadBalancers, HealthMonitors. */
    private def migratePools(): Unit = {
        for (p <- legacyImporter.listPools) {
            log.info("Migrating " + p)
            val resp = resources.pools.create(p, APPLICATION_POOL_JSON)
            handleResponse(resp)
        }
    }

    /** Migrates pool members. Prerequisites: Pools. */
    private def migratePoolMembers(): Unit = {
        for (pm <- legacyImporter.listPoolMembers) {
            log.info("Migrating " + pm)
            val resp = resources.pools.members(pm.poolId)
                .create(pm, APPLICATION_POOL_MEMBER_JSON)
            handleResponse(resp)
        }
    }

    /** Migrates VIPs. Prerequisites: LoadBalancers, Pools */
    private def migrateVips(): Unit = {
        for (v <- legacyImporter.listVips) {
            log.info("Migrating " + v)
            val resp = resources.vips.create(v, APPLICATION_VIP_JSON)
            handleResponse(resp)
        }
    }

    /** Migrates TraceRequests. Prerequisites: Bridges, Ports, Routers. */
    private def migrateTraceRequests(): Unit = {
        for (tr <- legacyImporter.listTraceRequests) {
            log.info("Migrating " + tr)
            val resp = resources.traceRequests
                .create(tr, APPLICATION_TRACE_REQUEST_JSON)
            handleResponse(resp)
        }
    }

    private def getWebAppExErrorMsg(ex: WebApplicationException): String = {
        if (ex.getMessage != null) return ex.getMessage

        ex.getResponse.getEntity match {
            case e: ValidationErrorEntity =>
                val violations = e.getViolations.map {
                    m => m("property") + " " + m("message")
                }
                "Validation error(s): " + violations.mkString("; ")
            case e =>
                "Unexpected error entity: " + e
        }
    }

    private def handleResponse(r: Response): Unit = r.getEntity match {
        case null => // Success
        case e: ErrorEntity =>
            // Error will already have been logged by API code, so no need to
            // log again.
            System.err.println("Error: " + e.getMessage)
        case e =>
            log.error("Unexpected response entity: " + e)
            System.err.println("Internal error. See log for details.")
    }
}

