/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.config;

import com.google.inject.PrivateModule;
import org.apache.commons.configuration.HierarchicalConfiguration;

import org.midonet.config.ConfigProvider;

/**
 * This Guice module will expose a {@link ConfigProvider} instance that everyone
 * can use as the source of their configuration.
 */
public class ConfigProviderModule extends PrivateModule {

    private final ConfigProvider provider;

    public ConfigProviderModule(String path) {
        this.provider = ConfigProvider.fromIniFile(path);
    }

    public ConfigProviderModule(HierarchicalConfiguration configuration) {
        this.provider = ConfigProvider.providerForIniConfig(configuration);
    }

    @Override
    protected void configure() {
        bind(ConfigProvider.class).toInstance(provider);
        expose(ConfigProvider.class);
    }
}
