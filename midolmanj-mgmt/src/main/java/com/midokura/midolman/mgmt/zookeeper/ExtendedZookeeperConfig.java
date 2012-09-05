/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.zookeeper;

import com.midokura.config.ConfigBool;
import com.midokura.config.ConfigGroup;
import com.midokura.midolman.config.ZookeeperConfig;

/**
 * Extension of ZookeeperConfig
 */
@ConfigGroup(ZookeeperConfig.GROUP_NAME)
public interface ExtendedZookeeperConfig extends ZookeeperConfig {

    public static final String USE_MOCK_KEY = "use_mock";

    @ConfigBool(key = USE_MOCK_KEY, defaultValue = false)
    public boolean getUseMock();

}
