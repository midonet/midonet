/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.config;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import org.apache.commons.configuration.HierarchicalConfiguration;

import org.midonet.config.ConfigProvider;

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
        return ConfigProvider.fromIniFile(configFilePath);
    }
}
