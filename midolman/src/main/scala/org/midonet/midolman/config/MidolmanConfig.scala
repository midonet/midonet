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

import com.typesafe.config.{ConfigFactory, Config}

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

    def apply() = new MidolmanConfig(MidoNodeConfigurator.forAgents().runtimeConfig(HostIdGenerator.getHostId))
}

class MidolmanConfig(_conf: Config) {
    val conf = _conf.resolve()

    def bridgeArpEnabled = conf.getBoolean("midolman.enable_bridge_arp")
    def bgpKeepAlive = conf.getDuration("midolman.bgp_keepalive", TimeUnit.SECONDS).toInt
    def bgpHoldTime = conf.getDuration("midolman.bgp_holdtime", TimeUnit.SECONDS).toInt
    def bgpConnectRetry = conf.getDuration("midolman.bgp_connect_retry", TimeUnit.SECONDS).toInt
    def bgpdConfigPath = conf.getString("midolman.bgpd_config")
    def dhcpMtu: Short = conf.getInt("midolman.dhcp_mtu").toShort
    def simulationThreads = conf.getInt("midolman.simulation_threads")
    def outputChannels = conf.getInt("midolman.output_channels")
    def inputChannelThreading = conf.getString("midolman.input_channel_threading")
    def datapathName = Try(conf.getString("midolman.datapath")).getOrElse("midonet")

    val bridge = new BridgeConfig(conf)
    val router = new RouterConfig(conf)
    val zookeeper = new MidonetBackendConfig(conf)
    val cassandra = new CassandraConfig(conf)
    val datapath = new DatapathConfig(conf)
    val arptable = new ArpTableConfig(conf)
    val healthMonitor = new HealthMonitorConfig(conf)
    val host = new HostConfig(conf)
    val cluster = new ClusterConfig(conf)
}

class HostConfig(conf: Config) {
    def waitTimeForUniqueId: Long = Try(conf.getDuration("host.wait_time_gen_id", TimeUnit.MILLISECONDS)).getOrElse(1000L)
    def retriesForUniqueId = Try(conf.getInt("host.retries_gen_id")).getOrElse(300)
}

class BridgeConfig(conf: Config) {
    def macPortMappingExpiry = conf.getDuration("bridge.mac_port_mapping_expire", TimeUnit.MILLISECONDS).toInt
}

class RouterConfig(conf: Config) {
    def maxBgpPeerRoutes = conf.getInt("router.max_bgp_peer_routes")
}

class CassandraConfig(conf: Config) {
    def servers = conf.getString("cassandra.servers")
    def cluster = conf.getString("cassandra.cluster")
    def replication_factor = conf.getInt("cassandra.replication_factor")
}

class DatapathConfig(conf: Config)
{
    def sendBufferPoolInitialSize = conf.getInt("datapath.send_buffer_pool_initial_size")
    def sendBufferPoolMaxSize = conf.getInt("datapath.send_buffer_pool_max_size")
    def sendBufferPoolBufSizeKb = conf.getInt("datapath.send_buffer_pool_buf_size_kb")

    def maxFlowCount = conf.getInt("datapath.max_flow_count")

    def vxlanVtepUdpPort = conf.getInt("datapath.vxlan_vtep_udp_port")
    def vxlanOverlayUdpPort = conf.getInt("datapath.vxlan_overlay_udp_port")

    def globalIncomingBurstCapacity = conf.getInt("datapath.global_incoming_burst_capacity")
    def vmIncomingBurstCapacity = conf.getInt("datapath.vm_incoming_burst_capacity")
    def tunnelIncomingBurstCapacity = conf.getInt("datapath.tunnel_incoming_burst_capacity")
    def vtepIncomingBurstCapacity = conf.getInt("datapath.vtep_incoming_burst_capacity")

    def controlPacketTos: Byte = conf.getInt("datapath.control_packet_tos").toByte
}

class ArpTableConfig(conf: Config) {
    def retryInterval = conf.getDuration("arptable.arp_retry_interval", TimeUnit.SECONDS)
    def timeout = conf.getDuration("arptable.arp_timeout", TimeUnit.SECONDS)
    def stale = conf.getDuration("arptable.arp_stale", TimeUnit.SECONDS)
    def expiration = conf.getDuration("arptable.arp_expiration", TimeUnit.SECONDS)
}

class HealthMonitorConfig(conf: Config) {
    def enable = conf.getBoolean("haproxy_health_monitor.health_monitor_enable")
    def namespaceCleanup = conf.getBoolean("haproxy_health_monitor.namespace_cleanup")
    def namespaceSuffix = conf.getString("haproxy_health_monitor.namespace_suffix")
    def haproxyFileLoc = conf.getString("haproxy_health_monitor.haproxy_file_loc")
}


class ClusterConfig(conf: Config) {
    def tasksDb = conf.getString("cluster.tasks_db_connection")
    def enabled = conf.getBoolean("cluster.enabled")
}
