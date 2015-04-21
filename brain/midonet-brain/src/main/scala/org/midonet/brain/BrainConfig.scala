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
import org.midonet.brain.services.conf.ConfMinion
import org.midonet.brain.services.flowtracing.FlowTracingMinion
import org.midonet.brain.services.heartbeat.Heartbeat
import org.midonet.brain.services.topology.TopologyApiService
import org.midonet.brain.services.vxgw.VxlanGatewayService

import org.midonet.cluster.storage.CassandraConfig
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

    def apply() = new BrainConfig(MidoNodeConfigurator().
                    runtimeConfig(HostIdGenerator.getHostId))

    def apply(fileName: String) =
        new BrainConfig(MidoNodeConfigurator(fileName).
            runtimeConfig(HostIdGenerator.getHostId))
}

class BrainConfig(_conf: Config) {
    val conf = _conf.resolve()

    val backend = new MidonetBackendConfig(conf)
    val cassandra = new CassandraConfig(conf)
    val embedding = new EmbeddedClusterNodeConfig(conf)
    val c3po = new C3POConfig(conf)
    val hearbeat = new HeartbeatConfig(conf)
    val vxgw = new VxGwConfig(conf)
    val topologyApi = new TopologyApiConfig(conf)
    val topologyUpdater = new TopologyZoomUpdaterConfig(conf)
    val snoopy = new TopologySnoopyConfig(conf)
    val confApi = new ConfApiConfig(conf)
    val flowTracing = new FlowTracingConfig(conf)
}

class EmbeddedClusterNodeConfig(conf: Config) {
    def enabled = conf.getBoolean("midobrain.vxgw_enabled")
}

class C3POConfig(val conf: Config) extends ScheduledMinionConfig[C3POMinion] {
    val PREFIX = "brain.neutron_importer"

    override def isEnabled = conf.getBoolean(s"$PREFIX.enabled")
    override def minionClass = conf.getString(s"$PREFIX.with")
    override def numThreads = conf.getInt(s"$PREFIX.threads")
    override def delayMs = conf.getDuration(s"$PREFIX.delay", TimeUnit.MILLISECONDS)
    override def periodMs = conf.getDuration(s"$PREFIX.period", TimeUnit.MILLISECONDS)
    def connectionString = conf.getString(s"$PREFIX.connection_string")
    def jdbcDriver = conf.getString(s"$PREFIX.jdbc_driver_class")
    def user = conf.getString(s"$PREFIX.user")
    def password = conf.getString(s"$PREFIX.password")
}

class HeartbeatConfig(val conf: Config) extends ScheduledMinionConfig[Heartbeat] {
    val PREFIX = "brain.heartbeat"
    override def isEnabled = conf.getBoolean(s"$PREFIX.enabled")
    override def minionClass = conf.getString(s"$PREFIX.with")
    override def numThreads = conf.getInt(s"$PREFIX.threads")
    override def delayMs = conf.getDuration(s"$PREFIX.delay", TimeUnit.MILLISECONDS)
    override def periodMs = conf.getDuration(s"$PREFIX.period", TimeUnit.MILLISECONDS)
}

class VxGwConfig(val conf: Config) extends MinionConfig[VxlanGatewayService] {
    override def isEnabled = conf.getBoolean("brain.vxgw.enabled")
    override def minionClass = conf.getString("brain.vxgw.with")
}

class TopologyApiConfig(val conf: Config) extends MinionConfig[TopologyApiService] {
    val PREFIX = "brain.topology_api"

    override def isEnabled = conf.getBoolean(s"$PREFIX.enabled")
    override def minionClass = conf.getString(s"$PREFIX.with")

    def socketEnabled = conf.getBoolean(s"$PREFIX.socket_enabled")
    def port = conf.getInt(s"$PREFIX.port")
    def wsEnabled = conf.getBoolean(s"$PREFIX.ws_enabled")
    def wsPort = conf.getInt(s"$PREFIX.ws_port")
    def wsPath = conf.getString(s"$PREFIX.ws_path")
    def sessionGracePeriod = conf.getDuration(s"$PREFIX.session_grace_period", TimeUnit.MILLISECONDS)
    def sessionBufferSize = conf.getInt(s"$PREFIX.session_buffer_size")
}

class TopologyZoomUpdaterConfig(val conf: Config) {
    val PREFIX = "brain.topology_zoom_updater"

    def enableUpdates = conf.getBoolean(s"$PREFIX.enable_updates")
    def threads = conf.getInt(s"$PREFIX.num_threads")
    def period = conf.getDuration(s"$PREFIX.period", TimeUnit.MILLISECONDS)
    def initialRouters = conf.getInt(s"$PREFIX.initial_routers")
    def initialNetworksPerRouter = conf.getInt(s"$PREFIX.initial_networks_per_router")
    def initialPortsPerNetwork = conf.getInt(s"$PREFIX.initial_ports_per_network")
    def initialVteps = conf.getInt(s"$PREFIX.initial_vteps")
    def initialHosts = conf.getInt(s"$PREFIX.initial_hosts")
}

class TopologySnoopyConfig(val conf: Config) {
    def host = conf.getString("brain.snoopy.host")
    def port = conf.getInt("brain.snoopy.port")
    def wsPath = conf.getString("brain.snoopy.ws_path")
}

class ConfApiConfig(val conf: Config) extends MinionConfig[ConfMinion] {
    override def isEnabled = conf.getBoolean("brain.conf_api.enabled")
    override def minionClass = conf.getString("brain.conf_api.with")

    def httpPort = conf.getInt("brain.conf_api.http_port")
}

class FlowTracingConfig(val conf: Config)
        extends MinionConfig[FlowTracingMinion] {
    override def isEnabled = conf.getBoolean("brain.flow_tracing.enabled")
    override def minionClass = conf.getString("brain.flow_tracing.with")

    def getPort = conf.getInt("brain.flow_tracing.port")
}
