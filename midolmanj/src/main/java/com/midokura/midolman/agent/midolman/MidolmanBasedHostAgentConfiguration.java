/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.midolman;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigString;
import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.config.MidolmanConfig;

/**
 * A {@link HostAgentConfiguration} implementation that knows how to read information
 * from inside a HierarchicalConfiguration that was loaded from a midolman.conf
 * file.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/9/12
 */
public interface MidolmanBasedHostAgentConfiguration
    extends HostAgentConfiguration {

    @Override
    @ConfigGroup(MidolmanConfig.GROUP_NAME)
    @ConfigString(key = "midolman_root_key", defaultValue = "/midonet/v1/midolman")
    String getZooKeeperBasePath();
}
