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
package org.midonet.cluster

import java.util.concurrent.TimeUnit

import com.typesafe.config.{ConfigFactory, Config}

import org.midonet.cluster.services.{ScheduledMinionConfig, MinionConfig}
import org.midonet.cluster.services.c3po.C3POMinion
import org.midonet.cluster.services.conf.ConfMinion
import org.midonet.cluster.services.heartbeat.Heartbeat
import org.midonet.cluster.services.topology.TopologyApiService
import org.midonet.cluster.services.vxgw.VxlanGatewayService
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator, MidoTestConfigurator}

object ClusterConfig {
    val DEFAULT_MTU: Short = 1500

    def forTests = new ClusterConfig(MidoTestConfigurator.forClusters())

    def forTests(config: Config) = new ClusterConfig(
            config.withFallback(MidoTestConfigurator.forClusters()))

    def forTests(config: String) = new ClusterConfig(
            ConfigFactory.parseString(config).
                withFallback(MidoTestConfigurator.forClusters()))

    def apply() = new ClusterConfig(MidoNodeConfigurator().
                    runtimeConfig(HostIdGenerator.getHostId))

    def apply(fileName: String) =
        new ClusterConfig(MidoNodeConfigurator(fileName).
            runtimeConfig(HostIdGenerator.getHostId))
}

class ClusterConfig(_conf: Config) {
    val PREFIX = "cluster"

    val conf = _conf.resolve()

    val backend = new MidonetBackendConfig(conf)
    val embedding = new EmbeddedClusterNodeConfig(conf)
    val c3po = new C3POConfig(conf)
    val heartbeat = new HeartbeatConfig(conf)
    val vxgw = new VxGwConfig(conf)
    val topologyApi = new TopologyApiConfig(conf)
    val snoopy = new TopologySnoopyConfig(conf)
    val confApi = new ConfApiConfig(conf)
    val restApi = new RestApiConfig(conf)
}

class EmbeddedClusterNodeConfig(conf: Config) {
    def enabled = conf.getBoolean("midocluster.vxgw_enabled")
}

class C3POConfig(val conf: Config) extends ScheduledMinionConfig[C3POMinion] {
    val PREFIX = "cluster.neutron_importer"

    override def isEnabled = conf.getBoolean(s"$PREFIX.enabled")
    override def numThreads = conf.getInt(s"$PREFIX.threads")
    override def delayMs = conf.getDuration(s"$PREFIX.delay", TimeUnit.MILLISECONDS)
    override def periodMs = conf.getDuration(s"$PREFIX.period", TimeUnit.MILLISECONDS)
    def connectionString = conf.getString(s"$PREFIX.connection_string")
    def jdbcDriver = conf.getString(s"$PREFIX.jdbc_driver_class")
    def user = conf.getString(s"$PREFIX.user")
    def password = conf.getString(s"$PREFIX.password")
}

class HeartbeatConfig(val conf: Config) extends ScheduledMinionConfig[Heartbeat] {
    val PREFIX = "cluster.heartbeat"
    override def isEnabled = conf.getBoolean(s"$PREFIX.enabled")
    override def numThreads = conf.getInt(s"$PREFIX.threads")
    override def delayMs = conf.getDuration(s"$PREFIX.delay", TimeUnit.MILLISECONDS)
    override def periodMs = conf.getDuration(s"$PREFIX.period", TimeUnit.MILLISECONDS)
}

class VxGwConfig(val conf: Config) extends MinionConfig[VxlanGatewayService] {
    override def isEnabled = conf.getBoolean("cluster.vxgw.enabled")
}

class TopologyApiConfig(val conf: Config) extends MinionConfig[TopologyApiService] {
    val PREFIX = "cluster.topology_api"

    override def isEnabled = conf.getBoolean(s"$PREFIX.enabled")

    def socketEnabled = conf.getBoolean(s"$PREFIX.socket_enabled")
    def port = conf.getInt(s"$PREFIX.port")
    def wsEnabled = conf.getBoolean(s"$PREFIX.ws_enabled")
    def wsPort = conf.getInt(s"$PREFIX.ws_port")
    def wsPath = conf.getString(s"$PREFIX.ws_path")
    def sessionGracePeriod = conf.getDuration(s"$PREFIX.session_grace_period", TimeUnit.MILLISECONDS)
    def sessionBufferSize = conf.getInt(s"$PREFIX.session_buffer_size")
}

class TopologySnoopyConfig(val conf: Config) {
    def host = conf.getString("cluster.snoopy.host")
    def port = conf.getInt("cluster.snoopy.port")
    def wsPath = conf.getString("cluster.snoopy.ws_path")
}

class ConfApiConfig(val conf: Config) extends MinionConfig[ConfMinion] {
    override def isEnabled = conf.getBoolean("cluster.conf_api.enabled")

    def httpPort = conf.getInt("cluster.conf_api.http_port")
}

class RestApiConfig(val conf: Config) extends MinionConfig[ConfMinion] {
    val PREFIX = "cluster.rest_api"

    override def isEnabled = conf.getBoolean("cluster.rest_api.enabled")

    def httpPort = conf.getInt("cluster.rest_api.http_port")
}
