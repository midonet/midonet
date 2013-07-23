/*
* Copyright 2012 Midokura PTE LTD.
* Copyright 2013 Midokura PTE LTD.
*/
package org.midonet.api.serialization;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.codehaus.jackson.jaxrs.JacksonJsonProvider;
import org.midonet.api.version.VersionParser;

/**
 * Bindings specific to serialization in api.
 */
public class SerializationModule extends AbstractModule {

    @Override
    protected void configure() {

        install(new org.midonet.midolman.guice.serialization
                .SerializationModule());

        bind(ObjectMapperProvider.class).asEagerSingleton();
        bind(WildCardJacksonJaxbJsonProvider.class).asEagerSingleton();
        bind(JsonMappingExceptionMapper.class).asEagerSingleton();

    }
}
