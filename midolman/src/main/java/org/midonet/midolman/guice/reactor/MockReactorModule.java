/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.guice.reactor;

import java.io.IOException;

import com.google.inject.*;

import org.midonet.midolman.services.SelectLoopService;
import org.midonet.util.eventloop.*;

import static org.midonet.midolman.guice.reactor.ReactorModule.ZEBRA_SERVER_LOOP;

/**
 * This is an Guice module that will expose a {@link SelectLoop} and a {@link Reactor}
 * binding to the enclosing injector.
 */
public class MockReactorModule extends PrivateModule {
    @Override
    protected void configure() {
        bind(Reactor.class)
            .toProvider(MockNetlinkReactorProvider.class)
            .asEagerSingleton();

        bind(SelectLoop.class)
                .annotatedWith(ZEBRA_SERVER_LOOP.class)
                .toProvider(MockSelectLoopProvider.class)
                .in(Singleton.class);

        expose(Key.get(SelectLoop.class, ZEBRA_SERVER_LOOP.class));

        bind(SelectLoopService.class)
            .in(Singleton.class);

        expose(Reactor.class);
        expose(SelectLoopService.class);
    }

    public static class MockSelectLoopProvider implements Provider<SelectLoop> {
        @Override
        public SelectLoop get() {
            try {
                return new MockSelectLoop();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class MockNetlinkReactorProvider implements Provider<Reactor> {

        @Override
        public Reactor get() {
            return new MockReactor();
        }
    }
}
