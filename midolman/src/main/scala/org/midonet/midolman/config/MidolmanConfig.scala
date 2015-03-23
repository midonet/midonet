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

import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator, MidoTestConfigurator}

object MidolmanConfig {
    val DEFAULT_MTU: Short = 1500

    def forTests = new MidolmanConfig(MidoTestConfigurator.forAgents)

    def forTests(config: Config) = new MidolmanConfig(
            config.withFallback(MidoTestConfigurator.forAgents))

    def forTests(config: String) = new MidolmanConfig(
            ConfigFactory.parseString(config).
                withFallback(MidoTestConfigurator.forAgents))

    def apply() = {
        val configurator = MidoNodeConfigurator.forAgents()
        new MidolmanConfig(configurator.runtimeConfig(HostIdGenerator.getHostId),
                           configurator.schema.get)
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
    val conf = _conf.resolve()

    def bridgeArpEnabled = getBoolean("midolman.enable_bridge_arp")
    def bgpKeepAlive = getDuration("midolman.bgp_keepalive", TimeUnit.SECONDS).toInt
    def bgpHoldTime = getDuration("midolman.bgp_holdtime", TimeUnit.SECONDS).toInt
    def bgpConnectRetry = getDuration("midolman.bgp_connect_retry", TimeUnit.SECONDS).toInt
    def bgpdConfigPath = getString("midolman.bgpd_config")
    def dhcpMtu: Short = getInt("midolman.dhcp_mtu").toShort
    def simulationThreads = getInt("midolman.simulation_threads")
    def outputChannels = getInt("midolman.output_channels")
    def inputChannelThreading = getString("midolman.input_channel_threading")
    def datapathName = Try(getString("midolman.datapath")).getOrElse("midonet")

    val bridge = new BridgeConfig(conf, schema)
    val router = new RouterConfig(conf, schema)
    val zookeeper = new MidonetBackendConfig(conf)
    val cassandra = new CassandraConfig(conf, schema)
    val datapath = new DatapathConfig(conf, schema)
    val arptable = new ArpTableConfig(conf, schema)
    val healthMonitor = new HealthMonitorConfig(conf, schema)
    val host = new HostConfig(conf, schema)
    val neutron = new NeutronConfig(conf, schema)
}

class HostConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def waitTimeForUniqueId: Long = Try(getDuration("host.wait_time_gen_id", TimeUnit.MILLISECONDS)).getOrElse(1000L)
    def retriesForUniqueId = Try(getInt("host.retries_gen_id")).getOrElse(300)
}

class BridgeConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def macPortMappingExpiry = conf.getDuration("bridge.mac_port_mapping_expire", TimeUnit.MILLISECONDS).toInt
}

class RouterConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def maxBgpPeerRoutes = conf.getInt("router.max_bgp_peer_routes")
}

class CassandraConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def servers = getString("cassandra.servers")
    def cluster = getString("cassandra.cluster")
    def replication_factor = getInt("cassandra.replication_factor")
}

class DatapathConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def sendBufferPoolInitialSize = getInt("datapath.send_buffer_pool_initial_size")
    def sendBufferPoolMaxSize = getInt("datapath.send_buffer_pool_max_size")
    def sendBufferPoolBufSizeKb = getInt("datapath.send_buffer_pool_buf_size_kb")

    def maxFlowCount = getInt("datapath.max_flow_count")

    def vxlanVtepUdpPort = getInt("datapath.vxlan_vtep_udp_port")
    def vxlanOverlayUdpPort = getInt("datapath.vxlan_overlay_udp_port")

    def globalIncomingBurstCapacity = getInt("datapath.global_incoming_burst_capacity")
    def vmIncomingBurstCapacity = getInt("datapath.vm_incoming_burst_capacity")
    def tunnelIncomingBurstCapacity = getInt("datapath.tunnel_incoming_burst_capacity")
    def vtepIncomingBurstCapacity = getInt("datapath.vtep_incoming_burst_capacity")

    def controlPacketTos: Byte = getInt("datapath.control_packet_tos").toByte
}

class ArpTableConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def retryInterval = getDuration("arptable.arp_retry_interval", TimeUnit.SECONDS)
    def timeout = getDuration("arptable.arp_timeout", TimeUnit.SECONDS)
    def stale = getDuration("arptable.arp_stale", TimeUnit.SECONDS)
    def expiration = getDuration("arptable.arp_expiration", TimeUnit.SECONDS)
}

class HealthMonitorConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def enable = getBoolean("haproxy_health_monitor.health_monitor_enable")
    def namespaceCleanup = getBoolean("haproxy_health_monitor.namespace_cleanup")
    def namespaceSuffix = getString("haproxy_health_monitor.namespace_suffix")
    def haproxyFileLoc = getString("haproxy_health_monitor.haproxy_file_loc")
}


class NeutronConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def tasksDb = getString("cluster.tasks_db_connection")
    def enabled = getBoolean("cluster.enabled")
}
