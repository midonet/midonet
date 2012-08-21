/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.config;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;

@ConfigGroup(DatapathConfig.GROUP_NAME)
public interface DatapathConfig {

    public final static String GROUP_NAME = "datapath";

    @ConfigInt(key = "max_flow_count", defaultValue = 1000)
    public int getDatapathMaxFlowCount();
}