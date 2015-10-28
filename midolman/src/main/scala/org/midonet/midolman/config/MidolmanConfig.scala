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
package org.midonet.midolman.config

import java.util.concurrent.TimeUnit
import scala.util.Try

import com.typesafe.config.{ConfigException, ConfigFactory, Config}
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.cluster.storage.{CassandraConfig,MidonetBackendConfig}
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator, MidoTestConfigurator}

object MidolmanConfig {
    val DEFAULT_MTU: Short = 1500

    def forTests = new MidolmanConfig(MidoTestConfigurator.forAgents())

    def forTests(config: Config) = new MidolmanConfig(MidoTestConfigurator.forAgents(config))

    def forTests(config: String) = new MidolmanConfig(MidoTestConfigurator.forAgents(config))

    def apply() = {
        val configurator = MidoNodeConfigurator()
        new MidolmanConfig(configurator.runtimeConfig(HostIdGenerator.getHostId),
                           configurator.mergedSchemas)
    }
}

trait TypeFailureFallback {
    val logger = Logger(LoggerFactory.getLogger("org.midonet.config"))

    def conf: Config
    def schema: Config

    private def get[T](key: String, func: (Config, String) => T) = try {
        func(conf, key)
    } catch {
        case e: ConfigException.WrongType =>
            logger.warn(s"Invalid value for config key: $key, will fallback to schema", e)
            func(schema, key)
    }

    def getBoolean(key: String): Boolean = get(key, (c, k) => c.getBoolean(k))
    def getString(key: String): String = get(key, (c, k) => c.getString(k))
    def getInt(key: String): Int = get(key, (c, k) => c.getInt(k))
    def getDouble(key: String): Double = get(key, (c, k) => c.getDouble(k))
    def getDuration(key: String, unit: TimeUnit): Long = get(key, (c, k) => c.getDuration(k, unit))
}

class MidolmanConfig(_conf: Config, val schema: Config = ConfigFactory.empty()) extends TypeFailureFallback {
    val PREFIX = "agent"
    val conf = _conf.resolve()

    def bridgeArpEnabled = getBoolean(s"$PREFIX.midolman.enable_bridge_arp")
    def bgpKeepAlive = getDuration(s"$PREFIX.midolman.bgp_keepalive", TimeUnit.SECONDS).toInt
    def bgpHoldTime = getDuration(s"$PREFIX.midolman.bgp_holdtime", TimeUnit.SECONDS).toInt
    def bgpConnectRetry = getDuration(s"$PREFIX.midolman.bgp_connect_retry", TimeUnit.SECONDS).toInt

    def dhcpMtu: Short = getInt(s"$PREFIX.midolman.dhcp_mtu").toShort
    def simulationThreads = getInt(s"$PREFIX.midolman.simulation_threads")
    def outputChannels = getInt(s"$PREFIX.midolman.output_channels")
    def inputChannelThreading = getString(s"$PREFIX.midolman.input_channel_threading")
    def datapathName = Try(getString(s"$PREFIX.midolman.datapath")).getOrElse("midonet")

    def lockMemory = getBoolean(s"$PREFIX.midolman.lock_memory")

    val bridge = new BridgeConfig(conf, schema)
    val router = new RouterConfig(conf, schema)
    val zookeeper = new MidonetBackendConfig(conf)
    val cassandra = new CassandraConfig(conf)
    val datapath = new DatapathConfig(conf, schema)
    val arptable = new ArpTableConfig(conf, schema)
    val healthMonitor = new HealthMonitorConfig(conf, schema)
    val host = new HostConfig(conf, schema)
    val neutron = new NeutronConfig(conf, schema)
    val openstack = new OpenStackConfig(conf, schema)
    val flowHistory = new FlowHistoryConfig(conf, schema)
}

class HostConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def waitTimeForUniqueId: Long = Try(getDuration("agent.host.wait_time_gen_id", TimeUnit.MILLISECONDS)).getOrElse(1000L)
    def retriesForUniqueId = Try(getInt("agent.host.retries_gen_id")).getOrElse(300)
}

class BridgeConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def macPortMappingExpiry = conf.getDuration("agent.bridge.mac_port_mapping_expire", TimeUnit.MILLISECONDS).toInt
}

class RouterConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    val PREFIX = "agent.router"
    def maxBgpPeerRoutes = conf.getInt(s"$PREFIX.max_bgp_peer_routes")
    def bgpZookeeperHoldtime = conf.getDuration(s"$PREFIX.bgp_zookeeper_holdtime", TimeUnit.SECONDS)
}

class DatapathConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    val PREFIX = "agent.datapath"

    def sendBufferPoolInitialSize = getInt(s"$PREFIX.send_buffer_pool_initial_size")
    def sendBufferPoolMaxSize = getInt(s"$PREFIX.send_buffer_pool_max_size")
    def sendBufferPoolBufSizeKb = getInt(s"$PREFIX.send_buffer_pool_buf_size_kb")

    def maxFlowCount = getInt(s"$PREFIX.max_flow_count")

    def vxlanVtepUdpPort = getInt(s"$PREFIX.vxlan_vtep_udp_port")
    def vxlanOverlayUdpPort = getInt(s"$PREFIX.vxlan_overlay_udp_port")

    def globalIncomingBurstCapacity = getInt(s"$PREFIX.global_incoming_burst_capacity")
    def vmIncomingBurstCapacity = getInt(s"$PREFIX.vm_incoming_burst_capacity")
    def tunnelIncomingBurstCapacity = getInt(s"$PREFIX.tunnel_incoming_burst_capacity")
    def vtepIncomingBurstCapacity = getInt(s"$PREFIX.vtep_incoming_burst_capacity")

    def controlPacketTos: Byte = getInt(s"$PREFIX.control_packet_tos").toByte
}

class ArpTableConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    val PREFIX = "agent.arptable"

    def retryInterval = getDuration(s"$PREFIX.arp_retry_interval", TimeUnit.MILLISECONDS)
    def timeout = getDuration(s"$PREFIX.arp_timeout", TimeUnit.MILLISECONDS)
    def stale = getDuration(s"$PREFIX.arp_stale", TimeUnit.MILLISECONDS)
    def expiration = getDuration(s"$PREFIX.arp_expiration", TimeUnit.MILLISECONDS)
}

class HealthMonitorConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    val PREFIX = "agent.haproxy_health_monitor"

    def enable = getBoolean(s"$PREFIX.health_monitor_enable")
    def namespaceCleanup = getBoolean(s"$PREFIX.namespace_cleanup")
    def haproxyFileLoc = getString(s"$PREFIX.haproxy_file_loc")
    def backend_contention_retries =
        getInt(s"$PREFIX.backend_contention_retries")
}


class NeutronConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def tasksDb = getString("agent.cluster.tasks_db_connection")
    def enabled = getBoolean("agent.cluster.enabled")
}

class OpenStackConfig(val conf: Config, val schema: Config)
        extends TypeFailureFallback {
    def metadata = new MetadataConfig(conf, schema)
}

class MetadataConfig(val conf: Config, val schema: Config)
        extends TypeFailureFallback {
    def enabled = getBoolean("agent.openstack.metadata.enabled")
    def nova_metadata_url =
        getString("agent.openstack.metadata.nova_metadata_url")
    def shared_secret = getString("agent.openstack.metadata.shared_secret")
}

class FlowHistoryConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def enabled = getBoolean("agent.flow_history.enabled")
    def encoding = getString("agent.flow_history.encoding")
    def udpEndpoint = getString("agent.flow_history.udp_endpoint")
}
