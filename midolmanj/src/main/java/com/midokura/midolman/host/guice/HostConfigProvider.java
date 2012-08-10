/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.host.guice;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.host.config.HostConfig;

/**
 * Provider for HostConfig
 */
public class HostConfigProvider implements Provider<HostConfig> {

    @Inject
    ConfigProvider configProvider;

    @Override
    public HostConfig get() {
        return configProvider.getConfig(HostConfig.class);
    }

}
