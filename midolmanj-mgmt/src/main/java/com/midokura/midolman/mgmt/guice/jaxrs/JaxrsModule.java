/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.jaxrs;

import com.google.inject.AbstractModule;
import com.midokura.midolman.mgmt.jaxrs.ThrowableMapper;
import com.midokura.midolman.mgmt.jaxrs.WebApplicationExceptionMapper;
import com.midokura.midolman.mgmt.rest_api.RestApiConfig;
import com.midokura.midolman.mgmt.jaxrs.WildCardJacksonJaxbJsonProvider;
import com.midokura.midolman.mgmt.rest_api.resources.ApplicationResource;

/**
 * Bindings specific to JAX-RS
 */
public class JaxrsModule extends AbstractModule {

    @Override
    protected void configure() {

        requireBinding(RestApiConfig.class);

        bind(ApplicationResource.class);
        bind(WebApplicationExceptionMapper.class).asEagerSingleton();
        bind(ThrowableMapper.class).asEagerSingleton();
        bind(WildCardJacksonJaxbJsonProvider.class).asEagerSingleton();

    }

}
