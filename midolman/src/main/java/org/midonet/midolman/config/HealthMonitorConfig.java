/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.config;

import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;

@ConfigGroup(HealthMonitorConfig.GROUP_NAME)
public interface HealthMonitorConfig {

    public final static String GROUP_NAME = "haproxy_health_monitor";

    @ConfigBool(key = "health_monitor_enable", defaultValue = false)
    public boolean getHealthMonitorEnable();

    @ConfigBool(key = "namespace_cleanup", defaultValue = false)
    public boolean getNamespaceCleanup();

    @ConfigString(key = "namespace_suffix", defaultValue = "_MN")
    public String getNamespaceSuffix();

    @ConfigString(key = "haproxy_file_loc",
                  defaultValue = "/etc/midolman/l4lb/")
    public String getHaproxyFileLoc();
}
