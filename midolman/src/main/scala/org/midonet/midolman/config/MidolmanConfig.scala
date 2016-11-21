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

import scala.concurrent.duration._
import scala.util.Try

import com.typesafe.config.{ConfigException, ConfigFactory, Config}
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.cluster.storage.{CassandraConfig,MidonetBackendConfig}
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator, MidoTestConfigurator}
import org.midonet.minion.{MinionConfig, ExecutorsConfig}
import org.midonet.packets.{MAC, IPv4Subnet}
import org.midonet.services.flowstate.FlowStateService
import org.midonet.services.rest_api.BindingApiService

object MidolmanConfig {

    def forTests = new MidolmanConfig(MidoTestConfigurator.forAgents())

    def forTests(config: Config) = new MidolmanConfig(MidoTestConfigurator.forAgents(config))

    def forTests(config: String) = new MidolmanConfig(MidoTestConfigurator.forAgents(config))

    def apply() = {
        val configurator = MidoNodeConfigurator()
        new MidolmanConfig(configurator.runtimeConfig(HostIdGenerator.getHostId),
                           configurator.mergedSchemas())
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

    def dhcpMtu = Math.min(getInt(s"$PREFIX.midolman.dhcp_mtu"), 0xffff)
    def simulationThreads = getInt(s"$PREFIX.midolman.simulation_threads")
    def maxPooledContexts = getInt(s"$PREFIX.midolman.max_pooled_contexts")
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
    val containers = new ContainerConfig(conf, schema)
    val services = new ServicesConfig(conf, schema)
    val flowState = new FlowStateConfig(conf, schema)
    val bindingApi = new BindingApiConfig(conf, schema)
    val ruleLogging = new RuleLoggingConfig(conf, schema)
    val fip64 = new Fip64Config(conf, schema)
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
    def vxlanRecirculateUdpPort = getInt(s"$PREFIX.vxlan_recirculate_udp_port")

    def recircCidr = IPv4Subnet.fromCidr(Try(getString(s"$PREFIX.recirc_cidr"))
                                               .getOrElse("169.254.123.0/24"))
    def recircConfig = new RecircConfig(recircCidr)

    def globalIncomingBurstCapacity = getInt(s"$PREFIX.global_incoming_burst_capacity")
    def vmIncomingBurstCapacity = getInt(s"$PREFIX.vm_incoming_burst_capacity")
    def tunnelIncomingBurstCapacity = getInt(s"$PREFIX.tunnel_incoming_burst_capacity")
    def vtepIncomingBurstCapacity = getInt(s"$PREFIX.vtep_incoming_burst_capacity")

    def controlPacketTos: Byte = getInt(s"$PREFIX.control_packet_tos").toByte
}

class RecircConfig(recircCidr: IPv4Subnet) {
    val recircHostName = "midorecirc-host"
    val recircMnName = "midorecirc-dp"
    val recircHostMac = MAC.fromString("02:00:11:00:11:01")
    val recircMnMac = MAC.fromString("02:00:11:00:11:02")
    val subnet = new IPv4Subnet(recircCidr.toNetworkAddress, 24)
    val network = subnet.toNetworkAddress
    val recircHostAddr = network.next
    val recircMnAddr = recircHostAddr.next
    val broadcast = subnet.toNetworkAddress

    def isValidMnAddr(addr: Int): Boolean =
        (addr != network.toInt) &&
        (addr != broadcast.toInt) &&
        (addr != recircHostAddr.toInt)
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
    def novaMetadataUrl =
        getString("agent.openstack.metadata.nova_metadata_url")
    def sharedSecret = getString("agent.openstack.metadata.shared_secret")
}

class FlowHistoryConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    def enabled = getBoolean("agent.flow_history.enabled")
    def encoding = getString("agent.flow_history.encoding")
    def endpointService = getString("agent.flow_history.endpoint_service")
}

class ContainerConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    val prefix = "agent.containers"
    def enabled = getBoolean(s"$prefix.enabled")
    def timeout = getDuration(s"$prefix.timeout", TimeUnit.MILLISECONDS) millis
    def shutdownGraceTime = getDuration(s"$prefix.shutdown_grace_time",
                                        TimeUnit.MILLISECONDS) millis
    def threadCount = getInt(s"$prefix.thread_count")
    def logDirectory = getString(s"$prefix.log_directory")

    val ipsec = new IPSecContainerConfig(conf, schema)
}

class IPSecContainerConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    val prefix = "agent.containers.ipsec"
    def loggingEnabled = getBoolean(s"$prefix.logging_enabled")
    def loggingPollInterval = getDuration(s"$prefix.logging_poll_interval",
                                          TimeUnit.MILLISECONDS) millis
    def loggingTimeout = getDuration(s"$prefix.logging_timeout",
                                     TimeUnit.MILLISECONDS) millis
    def statusUpdateInterval = getDuration(s"$prefix.status_update_interval",
                                           TimeUnit.MILLISECONDS) millis
}

class ServicesConfig(val conf: Config, val schema: Config) extends TypeFailureFallback {
    val prefix = "agent.minions"

    val executors = new ExecutorsConfig(conf, prefix)
}

class FlowStateConfig(val conf: Config, val schema: Config)
    extends TypeFailureFallback with MinionConfig[FlowStateService] {
    val prefix = "agent.minions.flow_state"

    override def isEnabled: Boolean = getBoolean(s"$prefix.enabled")
    def port: Int = getInt(s"$prefix.port")
    def blockSize: Int = Math.max(getInt(s"$prefix.block_size"), 1024)
    def blocksPerPort: Int = getInt(s"$prefix.blocks_per_port")
    def expirationTime: Duration = getDuration(s"$prefix.expiration_time",
                                               TimeUnit.MILLISECONDS) millis
    def expirationDelay: Duration = getDuration(s"$prefix.expiration_delay",
                                               TimeUnit.MILLISECONDS) millis
    def cleanFilesDelay: Duration = getDuration(s"$prefix.clean_unused_files_delay",
                                                TimeUnit.MILLISECONDS) millis
    def logDirectory: String = getString(s"$prefix.log_directory")
    def legacyPushState: Boolean = getBoolean(s"$prefix.legacy_push_state")
    def localPushState: Boolean = getBoolean(s"$prefix.local_push_state")
    def connectionTimeout: Int = getDuration(s"$prefix.connection_timeout",
                                        TimeUnit.MILLISECONDS).toInt
}

class BindingApiConfig(val conf: Config, val schema: Config)
    extends TypeFailureFallback with MinionConfig[BindingApiService] {
    val prefix = "agent.minions.binding_api"

    override def isEnabled: Boolean = getBoolean(s"$prefix.enabled")
    def unixSocket = getString(s"$prefix.unix_socket")
}

class RuleLoggingConfig(val conf: Config, val schema: Config)
    extends TypeFailureFallback {
    val prefix = "agent.rule_logging"

    def compress = getBoolean(s"$prefix.compress")
    def logFileName = getString(s"$prefix.log_file_name")
    def maxFiles = getInt(s"$prefix.max_files")
    def logDirectory = getString(s"$prefix.log_directory")
    def rotationFrequency = getString(s"$prefix.rotation_frequency")
}

class Fip64Config(val conf: Config, val schema: Config) {
    val vtepUdpPort: Short = 5321
    val vtepVppAddr = IPv4Subnet.fromCidr("169.254.0.1/30")
    val vtepKernAddr = IPv4Subnet.fromCidr("169.254.0.2/30")

    val vxlanDownlink = false
}
