/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;

import com.midokura.midolman.services.MidolmanActorsService;

/**
 * This Guice module will bind an instance of {@link MidolmanActorsService} so
 * that it can be retrieved by the client class and booted up at the system
 * initialization time.
 */
public class MidolmanActorsModule extends AbstractModule {
    @Override
    protected void configure() {
        bindMidolmanActorsService();
    }

    protected void bindMidolmanActorsService() {
        bind(MidolmanActorsService.class).in(Singleton.class);
    }
}
