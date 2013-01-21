/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.config;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;

@ConfigGroup(DatapathConfig.GROUP_NAME)
public interface DatapathConfig {

    public final static String GROUP_NAME = "datapath";

    @ConfigInt(key = "max_flow_count", defaultValue = 10000)
    public int getDatapathMaxFlowCount();

    /**
     * TODO: This features is not documented in the configuration file.
     * The wildcard flows have idle times, so the table should take care of itself. Having a smaller table (a limited
     * size table) means that the system would be potentially evicting valid flows often, causing more simulations and
     * hence, more CPU usage.
     */
    @ConfigInt(key = "max_wildcard_flow_count", defaultValue = 0) // 0 means NO_LIMIT
    public int getDatapathMaxWildcardFlowCount();
}