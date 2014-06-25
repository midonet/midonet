/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.rest_api;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.assistedinject.FactoryModuleBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.license.LicenseManager;
import org.midonet.api.vtep.VtepDataClientProvider;
import org.midonet.config.ConfigProvider;

/**
 * Guice module for REST API.
 */
public class RestApiModule extends AbstractModule {

    private static final Logger log = LoggerFactory.getLogger(
            RestApiModule.class);

    @Override
    protected void configure() {
        log.debug("configure: entered.");

        requireBinding(ConfigProvider.class);

        bind(WebApplicationExceptionMapper.class).asEagerSingleton();
        bind(VtepDataClientProvider.class).asEagerSingleton();

        bind(ApplicationResource.class);
        install(new FactoryModuleBuilder().build(ResourceFactory.class));

        bind(LicenseManager.class).asEagerSingleton();

        log.debug("configure: exiting.");
    }

    @Provides
    RestApiConfig provideRestApiConfig(ConfigProvider provider) {
        log.debug("provideRestApiConfig: entered.");
        return provider.getConfig(RestApiConfig.class);
    }

}
