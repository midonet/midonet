/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.config;

import com.midokura.config.ConfigBool;
import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;
import com.midokura.config.ConfigString;

/**
 * Configuration entries belonging to the midolman stanza.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/21/12
 */
@ConfigGroup(MidolmanConfig.GROUP_NAME)
public interface MidolmanConfig
    extends ZookeeperConfig, OpenvSwitchConfig, OpenFlowConfig,
            MemcacheConfig, CassandraConfig, DatapathConfig
{
    public final static String GROUP_NAME = "midolman";

    @ConfigInt(key = "disconnected_ttl_seconds", defaultValue = 30)
    int getMidolmanDisconnectedTtlSeconds();

    @ConfigBool(key = "enable_bgp", defaultValue = true)
    public boolean getMidolmanEnableBgp();

    @ConfigInt(key = "dhcp_mtu", defaultValue = 1450)
    int getMidolmanDhcpMtu();

    @ConfigString(key = "cache_type", defaultValue = "cassandra")
    public String getMidolmanCacheType();

    @ConfigBool(key = "enable_monitoring", defaultValue = true)
    public boolean getMidolmanEnableMonitoring();

    @ConfigGroup("vrn")
    @ConfigString(key = "router_network_id",
                  defaultValue = "01234567-0123-0123-aaaa-0123456789ab")
    public String getVrnRouterNetworkId();
}
