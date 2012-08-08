/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.config;

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
@ConfigGroup(HostAgentConfig.GROUP_NAME)
public interface HostAgentConfig {

    public static final String GROUP_NAME = "host_agent";

    /**
     * Returns the time to wait between local information scans (in millis).
     *
     * @return the time we want to wait between subsequent scans.
     */
    @ConfigInt(key = "wait_time_between_scans", defaultValue = 30000)
    public int getWaitTimeBetweenHostScans();

    /**
     * Get the unique Id stored in the config file
     *
     * @return the unique Id
     */
    @ConfigString(key = "host_uuid", defaultValue = "")
    public String getHostId();

    /**
     * Get the path of the properties file
     *
     * @return properties file
     */
    @ConfigString(key = "properties_file", defaultValue = "host_uuid.properties")
    public String getHostPropertiesFilePath();

    /**
     * Get the amount of time to wait during the generate host ID loop
     *
     * @return the wait time
     */
    @ConfigInt(key = "wait_time_gen_id", defaultValue = 1000)
    public int getWaitTimeForUniqueHostId();

}
