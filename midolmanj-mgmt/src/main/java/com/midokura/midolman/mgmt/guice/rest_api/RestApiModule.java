/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.rest_api;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.mgmt.guice.monitoring.MonitoringModule;
import com.midokura.midolman.mgmt.http.CorsConfig;
import com.midokura.midolman.mgmt.rest_api.RestApiConfig;
import com.midokura.midolman.mgmt.rest_api.resources.ResourceFactory;

/**
 * Guice module for REST API.
 */
public class RestApiModule extends AbstractModule {

    @Override
    protected void configure() {

        requireBinding(ConfigProvider.class);

        install(new MonitoringModule());

        install(new FactoryModuleBuilder().build(ResourceFactory.class));
    }

    @Provides
    CorsConfig provideCorsConfig(ConfigProvider provider) {
        return provider.getConfig(CorsConfig.class);
    }

    @Provides
    RestApiConfig provideRestApiConfig(ConfigProvider provider) {
        return provider.getConfig(RestApiConfig.class);
    }

}
