/*
* Copyright 2013 Midokura PTE
*/
package org.midonet.midolman.guice.serialization;

import com.google.inject.PrivateModule;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.version.serialization.JsonVersionZkSerializer;

/**
 * Serialization configuration module
 */
public class SerializationModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        bind(Serializer.class)
                .to(JsonVersionZkSerializer.class).asEagerSingleton();
        expose(Serializer.class);

    }

}
