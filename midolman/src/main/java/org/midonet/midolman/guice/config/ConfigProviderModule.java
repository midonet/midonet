/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.config;

import com.google.inject.PrivateModule;
import static com.google.inject.name.Names.named;

import org.midonet.config.ConfigProvider;

/**
 * This Guice module will expose a {@link ConfigProvider} instance that everyone
 * can use as the source of their configuration.
 */
public class ConfigProviderModule extends PrivateModule {

    String configurationFilePath;

    protected ConfigProviderModule() {

    }

    public ConfigProviderModule(String configurationFilePath) {
        this.configurationFilePath = configurationFilePath;
    }

    @Override
    protected void configure() {
        bindConfigProvider();
        expose(ConfigProvider.class);
    }

    protected void bindConfigProvider() {
        bindConstant()
            .annotatedWith(named(ConfigFromFileProvider.CONFIG_FILE_PATH))
            .to(configurationFilePath);

        bind(ConfigProvider.class)
            .toProvider(ConfigFromFileProvider.class)
            .asEagerSingleton();
    }
}
