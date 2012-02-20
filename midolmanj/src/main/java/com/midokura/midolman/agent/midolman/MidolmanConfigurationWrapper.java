/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.midolman;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.apache.commons.configuration.HierarchicalConfiguration;

import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.agent.config.IniBasedHostAgentConfiguration;

/**
 * A {@link HostAgentConfiguration} implementation that knows how to read information
 * from inside a HierarchicalConfiguration that was loaded from a midolman.conf
 * file.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/9/12
 */
public class MidolmanConfigurationWrapper
    extends IniBasedHostAgentConfiguration {

    public static final String NAMED_MIDOLMAN_CONFIG = "NamedMidolmanConfig";

    @Inject
    public MidolmanConfigurationWrapper(@Named(NAMED_MIDOLMAN_CONFIG)
                                        HierarchicalConfiguration configuration) {
        super(configuration);
    }

    @Override
    public String getZooKeeperBasePath() {
        return safelyGetString("midolman", "midolman_root_key",
                               "/midonet/v1/midolman");
    }
}
