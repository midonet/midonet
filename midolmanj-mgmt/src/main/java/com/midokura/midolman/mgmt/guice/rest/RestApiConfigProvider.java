/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.rest;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.mgmt.config.RestApiConfig;

/**
 * Provides RestApiConfig
 */
public class RestApiConfigProvider implements Provider<RestApiConfig> {

    private final ConfigProvider provider;

    @Inject
    public RestApiConfigProvider(ConfigProvider provider) {
        this.provider = provider;
    }

    @Override
    public RestApiConfig get() {
        return provider.getConfig(RestApiConfig.class);
    }

}
