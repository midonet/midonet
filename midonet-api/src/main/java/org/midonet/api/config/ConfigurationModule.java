/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.config;

import javax.servlet.ServletContext;

import com.google.inject.AbstractModule;

import org.midonet.config.ConfigProvider;
import org.midonet.config.providers.ServletContextConfigProvider;

/**
 * Guice module for configuration. Use ServletContext as the configuration
 * source by default, unless the constructor with a filename was called.
 * It is assumed that if using ServletContext, web.xml is context-param
 * elements are defined as {group}-{key}
 */
public class ConfigurationModule extends AbstractModule {

    private final ConfigProvider provider;

    public ConfigurationModule(ServletContext context) {
        this.provider = new ServletContextConfigProvider(context);
    }

    public ConfigurationModule(String filePath) {
        this.provider = ConfigProvider.fromIniFile(filePath);
    }

    @Override
    protected void configure() {
        bind(ConfigProvider.class).toInstance(provider);
    }
}
