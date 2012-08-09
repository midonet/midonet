/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.config;

import org.apache.commons.configuration.HierarchicalConfiguration;

import com.midokura.config.ConfigProvider;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class MockConfigProviderModule extends ConfigProviderModule {

    private HierarchicalConfiguration configuration;

    public MockConfigProviderModule(HierarchicalConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    protected void bindConfigProvider() {
        bind(ConfigProvider.class)
            .toInstance(ConfigProvider.providerForIniConfig(configuration));
    }
}
