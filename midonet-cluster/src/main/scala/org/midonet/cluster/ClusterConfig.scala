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

import org.midonet.cluster.services.c3po.C3POMinion
import org.midonet.cluster.services.heartbeat.Heartbeat
import org.midonet.cluster.services.rest_api.RestApi
import org.midonet.cluster.services.topology.TopologyApiService
import org.midonet.cluster.services.vxgw.VxlanGatewayService
import org.midonet.cluster.services.{MinionConfig, ScheduledMinionConfig}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator, MidoTestConfigurator}

object ClusterConfig {
    val DEFAULT_MTU: Short = 1500

    val MIN_DYNAMIC_NAT_PORT: Int = 1

    val MAX_DYNAMIC_NAT_PORT: Int = 0xffff

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
    val translators = new TranslatorsConfig(conf)

}

class AuthConfig(val conf: Config) {
    val Prefix = "cluster.auth"

    def provider = conf.getString(s"$Prefix.provider_class")
    def adminRole = conf.getString(s"$Prefix.admin_role")
    def tenantAdminRole = conf.getString(s"$Prefix.tenant_admin_role")
    def tenantUserRole = conf.getString(s"$Prefix.tenant_user_role")
}

class TranslatorsConfig(val conf: Config) {
    import ClusterConfig._

    final val prefix = "cluster.translators"

    private val dynamicNatStart = conf.getInt(s"$prefix.nat.dynamic_port_start")
    private val dynamicNatEnd = conf.getInt(s"$prefix.nat.dynamic_port_end")

    private def portInRange(port: Int) =
        MIN_DYNAMIC_NAT_PORT <= port && port <= MAX_DYNAMIC_NAT_PORT

    def dynamicNatPortStart =
        if (portInRange(dynamicNatStart)) dynamicNatStart
        else MIN_DYNAMIC_NAT_PORT

    def dynamicNatPortEnd =
        if (portInRange(dynamicNatEnd) && (dynamicNatStart < dynamicNatEnd)) dynamicNatEnd
        else MAX_DYNAMIC_NAT_PORT
}

class C3POConfig(val conf: Config) extends ScheduledMinionConfig[C3POMinion] {
    final val prefix = "cluster.neutron_importer"

    override def isEnabled = conf.getBoolean(s"$prefix.enabled")
    override def numThreads = conf.getInt(s"$prefix.threads")
    override def delayMs = conf.getDuration(s"$prefix.delay", TimeUnit.MILLISECONDS)
    override def periodMs = conf.getDuration(s"$prefix.period", TimeUnit.MILLISECONDS)
    def connectionString = conf.getString(s"$prefix.connection_string")
    def jdbcDriver = conf.getString(s"$prefix.jdbc_driver_class")
    def user = conf.getString(s"$prefix.user")
    def password = conf.getString(s"$prefix.password")
}

class HeartbeatConfig(val conf: Config) extends ScheduledMinionConfig[Heartbeat] {
    final val prefix = "cluster.heartbeat"

    override def isEnabled = conf.getBoolean(s"$prefix.enabled")
    override def numThreads = conf.getInt(s"$prefix.threads")
    override def delayMs = conf.getDuration(s"$prefix.delay", TimeUnit.MILLISECONDS)
    override def periodMs = conf.getDuration(s"$prefix.period", TimeUnit.MILLISECONDS)
}

class VxGwConfig(val conf: Config) extends MinionConfig[VxlanGatewayService] {
    final val prefix = "cluster.vxgw"

    override def isEnabled = conf.getBoolean(s"$prefix.enabled")
}

class TopologyApiConfig(val conf: Config) extends MinionConfig[TopologyApiService] {
    final val prefix = "cluster.topology_api"

    override def isEnabled = conf.getBoolean(s"$prefix.enabled")

    def socketEnabled = conf.getBoolean(s"$prefix.socket_enabled")
    def port = conf.getInt(s"$prefix.port")
    def wsEnabled = conf.getBoolean(s"$prefix.ws_enabled")
    def wsPort = conf.getInt(s"$prefix.ws_port")
    def wsPath = conf.getString(s"$prefix.ws_path")
    def sessionGracePeriod = conf.getDuration(s"$prefix.session_grace_period", TimeUnit.MILLISECONDS)
    def sessionBufferSize = conf.getInt(s"$prefix.session_buffer_size")
}

class RestApiConfig(val conf: Config) extends MinionConfig[RestApi] {
    final val prefix = "cluster.rest_api"

    override def isEnabled = conf.getBoolean("cluster.rest_api.enabled")

    def httpHost = conf.getString(s"$prefix.http_host")
    def httpPort = conf.getInt(s"$prefix.http_port")
    def httpsHost = conf.getString(s"$prefix.https_host")
    def httpsPort = conf.getInt(s"$prefix.https_port")
    def rootUri = conf.getString(s"$prefix.root_uri")
    def outputBufferSize = conf.getInt(s"$prefix.output_buffer_size")
    def nsdbLockTimeoutMs = conf.getDuration(s"$prefix.nsdb_lock_timeout", TimeUnit.MILLISECONDS)
}
