/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.config;

import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;
import org.midonet.config.ConfigString;
import org.midonet.midolman.monitoring.config.MonitoringConfiguration;

/**
 * Configuration entries belonging to the midolman stanza.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/21/12
 */
@ConfigGroup(MidolmanConfig.GROUP_NAME)
public interface MidolmanConfig
    extends ZookeeperConfig, CassandraConfig, DatapathConfig,
            ArpTableConfig, MonitoringConfiguration, HealthMonitorConfig
{
    public final static String GROUP_NAME = "midolman";

    @ConfigInt(key = "disconnected_ttl_seconds", defaultValue = 30)
    int getMidolmanDisconnectedTtlSeconds();

    @ConfigBool(key = "enable_bgp", defaultValue = true)
    public boolean getMidolmanBGPEnabled();

    @ConfigBool(key = "enable_bridge_arp", defaultValue = false)
    public boolean getMidolmanBridgeArpEnabled();

    @ConfigInt(key = "bgp_port_start_index", defaultValue = 0)
    public int getMidolmanBGPPortStartIndex();

    @ConfigInt(key = "bgp_keepalive", defaultValue = 60)
    public int getMidolmanBGPKeepAlive();

    @ConfigInt(key = "bgp_holdtime", defaultValue = 180)
    public int getMidolmanBGPHoldtime();

    @ConfigInt(key = "bgp_connect_retry", defaultValue = 120)
    public int getMidolmanBGPConnectRetry();

    @ConfigString(key = "bgpd_binary", defaultValue = "/usr/sbin")
    public String pathToBGPD();

    @ConfigString(key = "bgpd_config", defaultValue = "/etc/quagga")
    public String pathToBGPDConfig();

    @ConfigInt(key = "dhcp_mtu", defaultValue = 1450)
    int getMidolmanDhcpMtu();

    @ConfigString(key = "cache_type", defaultValue = "cassandra")
    public String getMidolmanCacheType();

    @ConfigString(key = "top_level_actors_supervisor", defaultValue = "resume")
    public String getMidolmanTopLevelActorsSupervisor();

    @ConfigInt(key = "check_flow_expiration_interval", defaultValue = 10000)
    public int getFlowExpirationInterval();

    @ConfigInt(key = "idle_flow_tolerance_interval", defaultValue = 10000)
    public int getIdleFlowToleranceInterval();

    @ConfigGroup("bridge")
    @ConfigInt(key = "mac_port_mapping_expire_millis", defaultValue = 30000)
    public int getMacPortMappingExpireMillis();

    @ConfigGroup("vrn")
    @ConfigString(key = "router_network_id",
                  defaultValue = "01234567-0123-0123-aaaa-0123456789ab")
    public String getVrnRouterNetworkId();

    @ConfigInt(key = "simulation_threads", defaultValue = 1)
    public int getSimulationThreads();

    @ConfigInt(key = "output_channels", defaultValue = 1)
    public int getNumOutputChannels();

    @ConfigString(key = "input_channel_threading", defaultValue = "one_to_many")
    public String getInputChannelThreading();
}
