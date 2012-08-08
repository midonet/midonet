/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.guice;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.config.HostAgentConfig;

/**
 * Provider for HostAgentConfig
 */
public class HostAgentConfigProvider implements Provider<HostAgentConfig> {

    @Inject
    ConfigProvider configProvider;

    @Override
    public HostAgentConfig get() {
        return configProvider.getConfig(HostAgentConfig.class);
    }

}
