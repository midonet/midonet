/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.config;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.midokura.config.ConfigProvider;
import com.midokura.config.providers.ServletContextConfigProvider;
import com.midokura.midolman.guice.config.ConfigFromFileProvider;
import org.omg.CORBA.portable.ServantObject;

import javax.inject.Provider;
import javax.servlet.ServletContext;

import static com.google.inject.name.Names.named;

/**
 * Guice module for configuration.
 */
public class ConfigModule extends AbstractModule {

    private final String filePath;
    private final ServletContext context;

    public ConfigModule(ServletContext context) {
        this.context = context;
        this.filePath = null;
    }

   public ConfigModule(String filePath) {
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
