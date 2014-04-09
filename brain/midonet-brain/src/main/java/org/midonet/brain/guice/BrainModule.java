/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.guice;

import com.google.inject.PrivateModule;

import org.midonet.brain.southbound.midonet.MidoVtep;
import org.midonet.brain.southbound.midonet.handlers.MacPortUpdateHandler;
import org.midonet.config.ConfigProvider;

/**
 * Brain module configuration.
 */
public class BrainModule extends PrivateModule {
    @Override
    protected void configure() {
        bind(MidoVtep.class).asEagerSingleton();
        expose(MidoVtep.class);

        requireBinding(ConfigProvider.class);
        requireBinding(MacPortUpdateHandler.class);
    }
}
