/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.config;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalINIConfiguration;

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
        try {
            HierarchicalINIConfiguration config = new HierarchicalINIConfiguration();
            config.setDelimiterParsingDisabled(true);
            config.setFileName(configFilePath);
            config.load();
            return ConfigProvider.providerForIniConfig(config);
        } catch (ConfigurationException e) {
            throw new RuntimeException(e);
        }
    }
}
