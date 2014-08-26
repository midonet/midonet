/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.zookeeper;

import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;

/**
 * Extension of ZookeeperConfig
 */
@ConfigGroup(ZookeeperConfig.GROUP_NAME)
public interface ExtendedZookeeperConfig extends ZookeeperConfig {

    String USE_MOCK_KEY = "use_mock";

    @ConfigBool(key = USE_MOCK_KEY, defaultValue = false)
    boolean getUseMock();

}
