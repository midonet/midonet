/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.host.guice;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.host.config.HostConfig;

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
