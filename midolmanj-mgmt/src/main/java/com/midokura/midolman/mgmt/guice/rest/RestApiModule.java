/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.rest;

import com.google.inject.AbstractModule;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.mgmt.config.RestApiConfig;

/**
 * Guice module for REST API.
 */
public class RestApiModule extends AbstractModule {

    @Override
    protected void configure() {

        requireBinding(ConfigProvider.class);

        bind(RestApiConfig.class).toProvider(
                RestApiConfigProvider.class).asEagerSingleton();

    }

}
