/*
 * Copyright 2014 - 2015 Midokura SARL
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
package org.midonet.brain

import java.util.concurrent.TimeUnit

import com.typesafe.config.{ConfigFactory, Config}

import org.midonet.brain.services.c3po.C3POMinion
import org.midonet.brain.services.heartbeat.Heartbeat
import org.midonet.brain.services.topology.TopologyApiService
import org.midonet.brain.services.vxgw.VxlanGatewayService
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator, MidoTestConfigurator}

object BrainConfig {
    val DEFAULT_MTU: Short = 1500

    def forTests = new BrainConfig(MidoTestConfigurator.forBrains)

    def forTests(config: Config) = new BrainConfig(
            config.withFallback(MidoTestConfigurator.forBrains))

    def forTests(config: String) = new BrainConfig(
            ConfigFactory.parseString(config).
                withFallback(MidoTestConfigurator.forBrains))

    def apply() = new BrainConfig(MidoNodeConfigurator.forBrains().
                    runtimeConfig(HostIdGenerator.getHostId))

    def apply(fileName: String) =
        new BrainConfig(MidoNodeConfigurator.forBrains(fileName).
            runtimeConfig(HostIdGenerator.getHostId))
}

class BrainConfig(_conf: Config) {
    val conf = _conf.resolve()

    val backend = new MidonetBackendConfig(conf)
    val embedding = new EmbeddedClusterNodeConfig(conf)
    val c3po = new C3POConfig(conf)
    val hearbeat = new HeartbeatConfig(conf)
    val vxgw = new VxGwConfig(conf)
    val topologyApi = new TopologyApiConfig(conf)
    val topologyUpdater = new TopologyZoomUpdaterConfig(conf)
    val snoopy = new TopologySnoopyConfig(conf)
}

class EmbeddedClusterNodeConfig(conf: Config) {
    def enabled = conf.getBoolean("midobrain.vxgw_enabled")
}

class C3POConfig(val conf: Config) extends ScheduledMinionConfig[C3POMinion] {
    override def isEnabled = conf.getBoolean("neutron_importer.enabled")
    override def minionClass = conf.getString("neutron_importer.with")
    override def numThreads = conf.getInt("neutron_importer.threads")
    override def delayMs = conf.getDuration("neutron_importer.delay", TimeUnit.MILLISECONDS)
    override def periodMs = conf.getDuration("neutron_importer.period", TimeUnit.MILLISECONDS)
    def connectionString = conf.getString("neutron_importer.connection_string")
    def jdbcDriver = conf.getString("neutron_importer.jdbc_driver_class")
    def user = conf.getString("neutron_importer.user")
    def password = conf.getString("neutron_importer.password")
}

class HeartbeatConfig(val conf: Config) extends ScheduledMinionConfig[Heartbeat] {
    override def isEnabled = conf.getBoolean("heartbeat.enabled")
    override def minionClass = conf.getString("heartbeat.with")
    override def numThreads = conf.getInt("heartbeat.threads")
    override def delayMs = conf.getDuration("heartbeat.delay", TimeUnit.MILLISECONDS)
    override def periodMs = conf.getDuration("heartbeat.period", TimeUnit.MILLISECONDS)
}

class VxGwConfig(val conf: Config) extends MinionConfig[VxlanGatewayService] {
    override def isEnabled = conf.getBoolean("vxgw.enabled")
    override def minionClass = conf.getString("vxgw.with")
}

class TopologyApiConfig(val conf: Config) extends MinionConfig[TopologyApiService] {

    override def isEnabled = conf.getBoolean("topology_api.enabled")
    override def minionClass = conf.getString("topology_api.with")

    def socketEnabled = conf.getBoolean("topology_api.socket_enabled")
    def port = conf.getInt("topology_api.port")
    def wsEnabled = conf.getBoolean("topology_api.ws_enabled")
    def wsPort = conf.getInt("topology_api.ws_port")
    def wsPath = conf.getString("topology_api.ws_path")
    def sessionGracePeriod = conf.getDuration("topology_api.session_grace_period", TimeUnit.MILLISECONDS)
    def sessionBufferSize = conf.getInt("topology_api.session_buffer_size")
}

class TopologyZoomUpdaterConfig(val conf: Config) {
    def enableUpdates = conf.getBoolean("topology_zoom_updater.enable_updates")
    def threads = conf.getInt("topology_zoom_updater.num_threads")
    def period = conf.getDuration("topology_zoom_updater.period", TimeUnit.MILLISECONDS)
    def initialRouters = conf.getInt("topology_zoom_updater.initial_routers")
    def initialNetworksPerRouter = conf.getInt("topology_zoom_updater.initial_networks_per_router")
    def initialPortsPerNetwork = conf.getInt("topology_zoom_updater.initial_ports_per_network")
    def initialVteps = conf.getInt("topology_zoom_updater.initial_vteps")
    def initialHosts = conf.getInt("topology_zoom_updater.initial_hosts")
}

class TopologySnoopyConfig(val conf: Config) {
    def host = conf.getString("snoopy.host")
    def port = conf.getInt("snoopy.port")
    def wsPath = conf.getString("snoopy.ws_path")
}
