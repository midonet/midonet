/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.config;

import org.apache.commons.configuration.HierarchicalConfiguration;

import org.midonet.config.ConfigProvider;

/**
 * A {@link ConfigProviderModule} specialization which will use an passed in
 * {@link HierarchicalConfiguration} configuration
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
