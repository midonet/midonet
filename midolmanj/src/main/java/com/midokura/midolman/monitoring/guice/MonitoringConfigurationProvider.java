/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.monitoring.guice;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;

/**
 * Monitoring configuration provider.
 */
public class MonitoringConfigurationProvider implements
        Provider<MonitoringConfiguration> {

    private final ConfigProvider provider;

    @Inject
    public MonitoringConfigurationProvider(ConfigProvider provider) {
        this.provider = provider;
    }
    @Override
    public MonitoringConfiguration get() {
        return provider.getConfig(MonitoringConfiguration.class);
    }

}
