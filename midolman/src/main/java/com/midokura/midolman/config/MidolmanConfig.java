/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.config;

import com.midokura.config.ConfigBool;
import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;
import com.midokura.config.ConfigString;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;

/**
 * Configuration entries belonging to the midolman stanza.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/21/12
 */
@ConfigGroup(MidolmanConfig.GROUP_NAME)
public interface MidolmanConfig
    extends ZookeeperConfig, CassandraConfig, DatapathConfig,
            ArpTableConfig, MonitoringConfiguration
{
    public final static String GROUP_NAME = "midolman";

    @ConfigInt(key = "disconnected_ttl_seconds", defaultValue = 30)
    int getMidolmanDisconnectedTtlSeconds();

    @ConfigBool(key = "enable_bgp", defaultValue = true)
    public boolean getMidolmanBGPEnabled();

    @ConfigInt(key = "bgp_port_start_index", defaultValue = 0)
    public int getMidolmanBGPPortStartIndex();

    @ConfigInt(key = "dhcp_mtu", defaultValue = 1450)
    int getMidolmanDhcpMtu();

    @ConfigString(key = "cache_type", defaultValue = "cassandra")
    public String getMidolmanCacheType();

    @ConfigString(key = "top_level_actors_supervisor", defaultValue = "resume")
    public String getMidolmanTopLevelActorsSupervisor();

    @ConfigInt(key = "check_flow_expiration_interval", defaultValue = 10000)
    public int getFlowExpirationInterval();

    @ConfigGroup("vrn")
    @ConfigString(key = "router_network_id",
                  defaultValue = "01234567-0123-0123-aaaa-0123456789ab")
    public String getVrnRouterNetworkId();
}
