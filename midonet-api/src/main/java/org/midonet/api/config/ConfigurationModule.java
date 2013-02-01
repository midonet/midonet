/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.config;

import com.google.inject.AbstractModule;
import org.midonet.config.ConfigProvider;
import org.midonet.config.providers.ServletContextConfigProvider;
import org.midonet.midolman.guice.config.ConfigFromFileProvider;

import javax.servlet.ServletContext;

import static com.google.inject.name.Names.named;

/**
 * Guice module for configuration.
 */
public class ConfigurationModule extends AbstractModule {

    private final String filePath;
    private final ServletContext context;

    public ConfigurationModule(ServletContext context) {
        this.context = context;
        this.filePath = null;
    }

   public ConfigurationModule(String filePath) {
        this.filePath = filePath;
        this.context = null;
    }

    @Override
    protected void configure() {

        // Use ServletContext as the configuration source by default,
        // unless a filename is explicitly set.  It is assumed that
        // if using ServletContext, web.xml is context-param elements are
        // defined as {group}-{key}
        if(filePath == null) {
            bind(ConfigProvider.class).toInstance(
                    new ServletContextConfigProvider(context));
        } else {
            bindConstant()
                    .annotatedWith(
                            named(ConfigFromFileProvider.CONFIG_FILE_PATH))
                    .to(filePath);

            bind(ConfigProvider.class)
                    .toProvider(ConfigFromFileProvider.class)
                    .asEagerSingleton();
        }
    }

}
