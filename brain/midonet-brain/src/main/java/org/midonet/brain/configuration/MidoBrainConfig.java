package org.midonet.brain.configuration;
/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.midolman.config.CassandraConfig;
import org.midonet.midolman.config.ZookeeperConfig;

@ConfigGroup(MidoBrainConfig.GROUP_NAME)
public interface MidoBrainConfig
    extends ZookeeperConfig, CassandraConfig {

    public final static String GROUP_NAME = "midobrain";

    @ConfigBool(key = "vxgw_enabled", defaultValue = false)
    public boolean getVxGwEnabled();
}
