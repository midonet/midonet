/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.config;

import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.guice.MonitoringConfigurationProvider;
import com.midokura.midolman.monitoring.store.MockCassandraStoreProvider;
import com.midokura.midolman.monitoring.store.Store;
import org.apache.commons.configuration.HierarchicalConfiguration;

import com.midokura.config.ConfigProvider;

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
