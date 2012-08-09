/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.reactor;

import java.util.concurrent.ScheduledExecutorService;

import com.google.inject.PrivateModule;
import com.google.inject.Singleton;

import com.midokura.midolman.services.SelectLoopService;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.eventloop.SelectLoop;

/**
 * This is an Guice module that will expose a {@link SelectLoop} and a {@link Reactor}
 * binding to the enclosing injector.
 */
public class ReactorModule extends PrivateModule {
    @Override
    protected void configure() {

        bind(ScheduledExecutorService.class)
            .toProvider(ScheduledExecutorServiceProvider.class)
            .asEagerSingleton();

        bind(Reactor.class)
            .toProvider(SelectLoopProvider.class)
            .asEagerSingleton();

        bind(SelectLoop.class)
            .toProvider(SelectLoopProvider.class)
            .asEagerSingleton();

        bind(SelectLoopProvider.class)
            .in(Singleton.class);

        bind(SelectLoopService.class)
            .in(Singleton.class);

        expose(SelectLoop.class);
        expose(Reactor.class);
        expose(SelectLoopService.class);
    }
}
