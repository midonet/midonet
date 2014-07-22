/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.host.config;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;
import org.midonet.config.ConfigString;
import org.midonet.config.HostIdConfig;

/**
 * Interface that provides access to various configuration values
 * available to the host.
 */
@ConfigGroup(HostConfig.GROUP_NAME)
public interface HostConfig extends HostIdConfig {

    public static final String GROUP_NAME = "host";

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
    @ConfigString(key = "properties_file", defaultValue = "/etc/midolman/host_uuid.properties")
    public String getHostPropertiesFilePath();

    /**
     * Get the amount of time to wait during the generate host ID loop
     *
     * @return the wait time
     */
    @ConfigInt(key = "wait_time_gen_id", defaultValue = 1000)
    public int getWaitTimeForUniqueHostId();

    /**
     * Get the number of times to wait for the host ID
     *
     * @return the number of times to wait
     */
    @ConfigInt(key = "retries_gen_id", defaultValue = 5 * 60 * 1000)
    public int getRetriesForUniqueHostId();

}
