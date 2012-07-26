/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import com.google.inject.AbstractModule;
import static com.google.inject.name.Names.named;

import com.midokura.config.ConfigProvider;
import static com.midokura.midolman.guice.ConfigFromFileProvider.CONFIG_FILE_PATH;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class ConfigurationModule extends AbstractModule {

    String configurationFilePath;

    public ConfigurationModule(String configurationFilePath) {
        this.configurationFilePath = configurationFilePath;
    }

    @Override
    protected void configure() {
        bindConstant()
            .annotatedWith(named(CONFIG_FILE_PATH))
            .to(configurationFilePath);

        bind(ConfigProvider.class)
            .toProvider(ConfigFromFileProvider.class)
            .asEagerSingleton();
    }
}
