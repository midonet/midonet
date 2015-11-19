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

import org.midonet.cluster.services.conman.ConmanService
import org.midonet.cluster.services.rest_api.Vladimir
import org.midonet.cluster.services.{ScheduledMinionConfig, MinionConfig}
import org.midonet.cluster.services.c3po.C3POMinion
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
    val conf = _conf.resolve()

    val auth = new AuthConfig(conf)
    val backend = new MidonetBackendConfig(conf)
    val c3po = new C3POConfig(conf)
    val heartbeat = new HeartbeatConfig(conf)
    val vxgw = new VxGwConfig(conf)
    val topologyApi = new TopologyApiConfig(conf)
    val restApi = new RestApiConfig(conf)
    val conman = new ConmanConfig(conf)
}

class AuthConfig(val conf: Config) {
    val Prefix = "cluster.auth"

    def provider = conf.getString(s"$Prefix.provider_class")
    def adminRole = conf.getString(s"$Prefix.admin_role")
    def tenantAdminRole = conf.getString(s"$Prefix.tenant_admin_role")
    def tenantUserRole = conf.getString(s"$Prefix.tenant_user_role")
}

class C3POConfig(val conf: Config) extends ScheduledMinionConfig[C3POMinion] {
    final val Prefix = "cluster.neutron_importer"

    override def isEnabled = conf.getBoolean(s"$Prefix.enabled")
    override def numThreads = conf.getInt(s"$Prefix.threads")
    override def delayMs = conf.getDuration(s"$Prefix.delay", TimeUnit.MILLISECONDS)
    override def periodMs = conf.getDuration(s"$Prefix.period", TimeUnit.MILLISECONDS)
    def connectionString = conf.getString(s"$Prefix.connection_string")
    def jdbcDriver = conf.getString(s"$Prefix.jdbc_driver_class")
    def user = conf.getString(s"$Prefix.user")
    def password = conf.getString(s"$Prefix.password")
}

class HeartbeatConfig(val conf: Config) extends ScheduledMinionConfig[Heartbeat] {
    final val Prefix = "cluster.heartbeat"

    override def isEnabled = conf.getBoolean(s"$Prefix.enabled")
    override def numThreads = conf.getInt(s"$Prefix.threads")
    override def delayMs = conf.getDuration(s"$Prefix.delay", TimeUnit.MILLISECONDS)
    override def periodMs = conf.getDuration(s"$Prefix.period", TimeUnit.MILLISECONDS)
}

class VxGwConfig(val conf: Config) extends MinionConfig[VxlanGatewayService] {
    final val Prefix = "cluster.vxgw"

    override def isEnabled = conf.getBoolean(s"$Prefix.enabled")
}

class TopologyApiConfig(val conf: Config) extends MinionConfig[TopologyApiService] {
    final val Prefix = "cluster.topology_api"

    override def isEnabled = conf.getBoolean(s"$Prefix.enabled")

    def socketEnabled = conf.getBoolean(s"$Prefix.socket_enabled")
    def port = conf.getInt(s"$Prefix.port")
    def wsEnabled = conf.getBoolean(s"$Prefix.ws_enabled")
    def wsPort = conf.getInt(s"$Prefix.ws_port")
    def wsPath = conf.getString(s"$Prefix.ws_path")
    def sessionGracePeriod = conf.getDuration(s"$Prefix.session_grace_period", TimeUnit.MILLISECONDS)
    def sessionBufferSize = conf.getInt(s"$Prefix.session_buffer_size")
}

class RestApiConfig(val conf: Config) extends MinionConfig[Vladimir] {
    final val Prefix = "cluster.rest_api"

    override def isEnabled = conf.getBoolean("cluster.rest_api.enabled")

    def httpPort = conf.getInt(s"$Prefix.http_port")
    def httpsPort = conf.getInt(s"$Prefix.https_port")
    def rootUri = conf.getString(s"$Prefix.root_uri")
}

class ConmanConfig(val conf: Config) extends MinionConfig[ConmanService] {
    final val Prefix = "cluster.container_manager"

    override def isEnabled = conf.getBoolean("cluster.container_manager.enabled")
}
