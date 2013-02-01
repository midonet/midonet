/*
* Copyright 2012 Midokura PTE LTD.
*/
package com.midokura.midonet.api.serialization;

import com.google.inject.AbstractModule;

/**
 * Bindings specific to error handling in api.
 */
public class SerializationModule extends AbstractModule {

    @Override
    protected void configure() {

        bind(WildCardJacksonJaxbJsonProvider.class).asEagerSingleton();
        bind(JsonMappingExceptionMapper.class).asEagerSingleton();

    }

}
