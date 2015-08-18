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

import org.eclipse.jetty.server.Request
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
import org.midonet.cluster.services.rest_api.resources.ApplicationResource
import org.midonet.cluster.storage.{LegacyStateTableStorage, MidonetBackendConfig, MidonetBackendModule}
import org.midonet.conf.FileConf
import org.midonet.midolman.cluster.LegacyClusterModule
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule.ZookeeperReactorProvider
import org.midonet.midolman.cluster.zookeeper.{DirectoryProvider, ZkConnectionProvider}
import org.midonet.midolman.state.{Directory, ZkConnection, ZkConnectionAwareWatcher, ZookeeperConnectionWatcher}
import org.midonet.packets.IPv4Subnet
import org.midonet.util.eventloop.Reactor

object Migrator extends App {

    private val log = LoggerFactory.getLogger(this.getClass)

    log.info("Starting Midonet data migration tool.")

    private val configFile = args(0)
    private val config = loadConfig(configFile)

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

    val legacyImporter = injector.getInstance(classOf[LegacyImporter])

    val backend = injector.getInstance(classOf[MidonetBackend])
    backend.startAsync().awaitRunning()

    val appResource = loadV2ApplicationResource()

    migrateData()

    System.exit(0)

    private def loadConfig(configFile: String): MidonetBackendConfig = {
        log.info("Loading config file: " + configFile)
        if (!Files.isReadable(Paths.get(configFile))) {
            System.err.println("Could not read config file: " + configFile)
            System.exit(1)
        }

        val conf = new FileConf(new File(configFile))
        new MidonetBackendConfig(conf.get)
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
    private def loadV2ApplicationResource(): ApplicationResource = {
        val seedMap = new util.HashMap[Key[_], Object]()
        seedMap.put(Key.get(classOf[HttpServletRequest]), new Request(null, null))
        ServletScopes.scopeRequest(
            new Callable[ApplicationResource] {
                override def call(): ApplicationResource =
                    injector.getInstance(classOf[ApplicationResource])
            }, seedMap).call()
    }

    private def migrateData(): Unit = {
        try {
            migrateHosts()
            migrateTunnelZones()
            migrateChains()
            migrateBridges()
            migrateRouters()
            migratePortGroups()
            migratePorts()

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

    /** Migrates Hosts. No prerequisites. */
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

    /** Migrates Bridges and their Subnets. No prerequisites. */
    private def migrateBridges(): Unit = {
        for (b <- legacyImporter.listBridges) {
            log.info("Migrating " + b)

            // The API won't accept a bridge with these fields set. Zoom will
            // fill them in again when we migrate the VXLAN port.
            b.vxLanPortId = null
            b.vxLanPortIds = null

            val resp = appResource.bridges.create(b, APPLICATION_BRIDGE_JSON_V3)
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
            val resp = appResource.bridges.dhcps(bridgeId)
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
            val resp = appResource.bridges.dhcps(bridgeId).hosts(subnet)
                .create(h, APPLICATION_DHCP_HOST_JSON_V2)
            handleResponse(resp)
        }
    }

    /** Migrates routers. No prerequisites. */
    private def migrateRouters(): Unit = {
        for (r <- legacyImporter.listRouters) {
            log.info("Migrating " + r)
            if (r.tenantId == null) migrateTenantlessRouter(r)
            else {
                val resp = appResource.routers.create(
                    r, APPLICATION_ROUTER_JSON_V3)
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
                    appResource.bridges.ports(bp.bridgeId).create(
                        bp, APPLICATION_PORT_V2_JSON)
                case rp: RouterPort =>
                    appResource.routers.ports(rp.routerId).create(
                        rp, APPLICATION_PORT_V2_JSON)
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

    /** Migrates tunnel zones. No prerequisites. */
    private def migrateTunnelZones(): Unit = {
        for (tz <- legacyImporter.listTunnelZones) {
            log.info("Migrating " + tz)
            val resp = appResource.tunnelZones.create(
                tz, APPLICATION_TUNNEL_ZONE_JSON)
            handleResponse(resp)
        }
    }

    /** Migrates chains and their associated rules. No prerequisites. */
    private def migrateChains(): Unit = {
        for (chain <- legacyImporter.listChains) {
            // First migrate the chain. Clear rule IDs, since they refer to
            // rules that don't exist yet.
            log.info("Migrating " + chain)
            chain.ruleIds = null
            appResource.chains.create(chain, APPLICATION_CHAIN_JSON)
            val chainRuleResource = appResource.chains.rules(chain.id)

            // Now migrate the rules. This will add the rule IDs back to the
            // V2 chains via Zoom bindings.
            for (rule <- legacyImporter.listRules(chain.id)) {
                log.info("Migrating " + rule)
                rule match {
                    case nr: NatRule if nr.flowAction == null =>
                        val flowAction = nr.action.toString.toLowerCase
                        log.info(s"NatRule ${nr.id} has null flowAction." +
                                 s"Setting to $flowAction based on value of" +
                                 s"action.")
                        nr.flowAction = nr.action.toString.toLowerCase
                    case _ =>
                }
                chainRuleResource.create(rule, APPLICATION_RULE_JSON_V2)
            }

        }
    }

    private def migratePortGroups(): Unit = {
        for (pg <- legacyImporter.listPortGroups) {
            log.info("Migrating " + pg)
            val resp = appResource.portGroups.create(pg,
                                                     APPLICATION_PORTGROUP_JSON)
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

