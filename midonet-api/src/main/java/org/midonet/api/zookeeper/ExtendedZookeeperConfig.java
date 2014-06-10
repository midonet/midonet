/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.zookeeper;

import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.midolman.config.ZookeeperConfig;

/**
 * Extension of ZookeeperConfig
 */
@ConfigGroup(ZookeeperConfig.GROUP_NAME)
public interface ExtendedZookeeperConfig extends ZookeeperConfig {

    String USE_MOCK_KEY = "use_mock";

    @ConfigBool(key = USE_MOCK_KEY, defaultValue = false)
    boolean getUseMock();

}
