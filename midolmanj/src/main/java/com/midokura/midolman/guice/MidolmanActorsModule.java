/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;

import com.midokura.midolman.services.MidolmanActorsService;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class MidolmanActorsModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(MidolmanActorsService.class)
            .in(Singleton.class);
    }
}
