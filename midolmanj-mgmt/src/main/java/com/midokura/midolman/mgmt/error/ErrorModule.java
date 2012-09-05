/*
* Copyright 2012 Midokura PTE LTD.
*/
package com.midokura.midolman.mgmt.error;

import com.google.inject.AbstractModule;
import com.midokura.midolman.mgmt.rest_api.WebApplicationExceptionMapper;
import com.midokura.midolman.mgmt.serialization.JsonMappingExceptionMapper;

/**
 * Bindings specific to error handling in mgmt.
 */
public class ErrorModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(ThrowableMapper.class).asEagerSingleton();

    }

}