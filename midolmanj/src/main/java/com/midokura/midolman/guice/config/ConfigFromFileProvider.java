/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.config;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalINIConfiguration;

import com.midokura.config.ConfigProvider;

/**
 * Simple Provider of a {@link ConfigProvider} instance that will build it from
 * a file on disk.
 */
public class ConfigFromFileProvider implements Provider<ConfigProvider> {

    public static final String CONFIG_FILE_PATH =
        "midolmanConfigurationFilePath";

    @Inject
    @Named(ConfigFromFileProvider.CONFIG_FILE_PATH)
    String configFilePath;

    @Override
    public ConfigProvider get() {
        try {
            return
                ConfigProvider.providerForIniConfig(
                    new HierarchicalINIConfiguration(configFilePath));
        } catch (ConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
}
