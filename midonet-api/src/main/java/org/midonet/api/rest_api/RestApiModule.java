/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.rest_api;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.assistedinject.FactoryModuleBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.brain.services.vxgw.VxLanGatewayService;
import org.midonet.brain.southbound.vtep.VtepDataClientFactory;
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

        bindVtepDataClientFactory(); // allow mocking

        bind(ApplicationResource.class);
        install(new FactoryModuleBuilder().build(ResourceFactory.class));

        bind(RestApiService.class).asEagerSingleton();
        bind(VxLanGatewayService.class).asEagerSingleton();

        log.debug("configure: exiting.");
    }

    protected void bindVtepDataClientFactory() {
        bind(VtepDataClientFactory.class).asEagerSingleton();
    }

    @Provides
    RestApiConfig provideRestApiConfig(ConfigProvider provider) {
        log.debug("provideRestApiConfig: entered.");
        return provider.getConfig(RestApiConfig.class);
    }

}
