/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.reactor;

import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import com.midokura.midolman.services.SelectLoopService;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.eventloop.SelectLoop;
import com.midokura.util.eventloop.TryCatchReactor;

/**
 * This is an Guice module that will expose a {@link SelectLoop} and a {@link Reactor}
 * binding to the enclosing injector.
 */
public class ReactorModule extends PrivateModule {
    @Override
    protected void configure() {

        bind(Reactor.class)
            .toProvider(NetlinkReactorProvider.class)
            .asEagerSingleton();

        bind(SelectLoop.class)
            .in(Singleton.class);

        bind(SelectLoopService.class)
            .in(Singleton.class);

        expose(SelectLoop.class);
        expose(Reactor.class);
        expose(SelectLoopService.class);

    }

    public static class NetlinkReactorProvider implements Provider<Reactor> {

        @Override
        public Reactor get() {
            return new TryCatchReactor("netlink", 1);
        }
    }
}
