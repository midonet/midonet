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

import scala.concurrent.duration._

import com.typesafe.config.{Config, ConfigFactory}

import org.midonet.cluster.services.c3po.C3POMinion
import org.midonet.cluster.services.containers.ContainerService
import org.midonet.cluster.services.heartbeat.Heartbeat
import org.midonet.cluster.services.rest_api.RestApi
import org.midonet.cluster.services.state.StateProxy
import org.midonet.cluster.services.topology.TopologyApiService
import org.midonet.cluster.services.vxgw.VxlanGatewayService
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator, MidoTestConfigurator}
import org.midonet.minion.{ExecutorsConfig, MinionConfig, ScheduledMinionConfig}

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
    final val prefix = "cluster"

    val auth = new AuthConfig(conf)
    val backend = new MidonetBackendConfig(conf)
    val c3po = new C3POConfig(conf)
    val heartbeat = new HeartbeatConfig(conf)
    val vxgw = new VxGwConfig(conf)
    val topologyApi = new TopologyApiConfig(conf)
    val restApi = new RestApiConfig(conf)
    val containers = new ContainersConfig(conf)
    val translators = new TranslatorsConfig(conf)
    val stateProxy = new StateProxyConfig(conf)
    val executors = new ExecutorsConfig(conf, prefix)
}

class AuthConfig(val conf: Config) {
    final val prefix = "cluster.auth"

    def provider = conf.getString(s"$prefix.provider_class")
    def adminRole = conf.getString(s"$prefix.admin_role")
    def tenantAdminRole = conf.getString(s"$prefix.tenant_admin_role")
    def tenantUserRole = conf.getString(s"$prefix.tenant_user_role")
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

class RestApiConfig(val conf: Config) extends MinionConfig[RestApi] {
    final val Prefix = "cluster.rest_api"

    override def isEnabled = conf.getBoolean("cluster.rest_api.enabled")

    def httpPort = conf.getInt(s"$Prefix.http_port")
    def httpsPort = conf.getInt(s"$Prefix.https_port")
    def rootUri = conf.getString(s"$Prefix.root_uri")
    def nsdbLockTimeoutMs = conf.getDuration(s"$Prefix.nsdb_lock_timeout", TimeUnit.MILLISECONDS)
}

class ContainersConfig(val conf: Config) extends MinionConfig[ContainerService] {
    final val Prefix = "cluster.containers"

    override def isEnabled = conf.getBoolean(s"$Prefix.enabled")
    def schedulerTimeoutMs = conf.getDuration(s"$Prefix.scheduler_timeout", TimeUnit.MILLISECONDS)
    def schedulerRetryMs = conf.getDuration(s"$Prefix.scheduler_retry", TimeUnit.MILLISECONDS)
    def schedulerMaxRetries = conf.getInt(s"$Prefix.scheduler_max_retries")
    def schedulerBadHostLifetimeMs = conf.getDuration(s"$Prefix.scheduler_bad_host_lifetime", TimeUnit.MILLISECONDS)
}

class StateProxyConfig(val conf: Config) extends MinionConfig[StateProxy] {
    final val Prefix = "cluster.state_proxy"

    override def isEnabled = conf.getBoolean(s"$Prefix.enabled")
    def initialSubscriberQueueSize =
        conf.getInt(s"$Prefix.initial_subscriber_queue_size")
    def notifyBatchSize =
        conf.getInt(s"$Prefix.notify_batch_size")
    def serverAddress = conf.getString(s"$Prefix.server.address")
    def serverPort = conf.getInt(s"$Prefix.server.port")
    def serverSupervisorThreads =
        conf.getInt(s"$Prefix.server.supervisor_threads")
    def serverWorkerThreads =
        conf.getInt(s"$Prefix.server.worker_threads")
    def serverMaxPendingConnections =
        conf.getInt(s"$Prefix.server.max_pending_connections")
    def serverBindRetryInterval =
        conf.getDuration(s"$Prefix.server.bind_retry_interval", TimeUnit.SECONDS) seconds
    def serverChannelTimeout =
        conf.getDuration(s"$Prefix.server.channel_timeout", TimeUnit.MILLISECONDS) millis
    def serverShutdownQuietPeriod =
        conf.getDuration(s"$Prefix.server.shutdown_quiet_period", TimeUnit.MILLISECONDS) millis
    def serverShutdownTimeout =
        conf.getDuration(s"$Prefix.server.shutdown_timeout", TimeUnit.MILLISECONDS) millis
}