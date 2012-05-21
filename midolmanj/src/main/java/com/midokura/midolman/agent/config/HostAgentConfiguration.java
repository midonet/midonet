/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.config;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;
import com.midokura.config.ConfigString;
import com.midokura.midolman.config.OpenvSwitchConfig;
import com.midokura.midolman.config.ZookeeperConfig;

/**
 * Interface that provides access to various configuration values
 * available to the agent.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
@ConfigGroup(HostAgentConfiguration.GROUP_NAME)
public interface HostAgentConfiguration extends OpenvSwitchConfig, ZookeeperConfig {

    public static final String GROUP_NAME = "midolman_agent";

    /**
     * Returns the time to wait between local information scans (in millis).
     *
     * @return the time we want to wait between subsequent scans.
     */
    @ConfigInt(key = "wait_time_between_scans", defaultValue = 30000)
    public int getWaitTimeBetweenScans();

    /**
     * ZooKeeper related configuration: the midolman configuration root node.
     *
     * @return the root node path as a string
     */
    @ConfigString(key = "midolman_root_key", defaultValue = "/midonet/v1/midolman")
    public String getZooKeeperBasePath();

    /**
     * Get the unique Id stored in the config file
     *
     * @return the unique Id
     */
    @ConfigString(key = "host_uuid", defaultValue = "")
    public String getId();

    /**
     * Get the path of the properties file
     *
     * @return properties file
     */
    @ConfigString(key = "properties_file", defaultValue = "host_uuid.properties")
    public String getPropertiesFilePath();

    /**
     * Get the amount of time to wait during the generate host ID loop
     *
     * @return the wait time
     */
    @ConfigInt(key = "wait_time_gen_id", defaultValue = 1000)
    public int getWaitTimeForUniqueHostId();

    /**
     * Get the id to use when identifying routers belonging to our virtual
     * configuration.
     *
     * @return the Virtual Network router id
     */
    @ConfigGroup("vrn")
    @ConfigString(key = "router_network_id", defaultValue = "01234567-0123-0123-aaaa-0123456789ab")
    public String getVrnRouterNetworkId();

}
