/*
* Copyright 2012 Midokura PTE LTD.
*/
package com.midokura.midolman.mgmt.serialization;

import com.google.inject.AbstractModule;

/**
 * Bindings specific to error handling in mgmt.
 */
public class SerializationModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(WildCardJacksonJaxbJsonProvider.class).asEagerSingleton();
        bind(JsonMappingExceptionMapper.class).asEagerSingleton();

    }

}