/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.inject.Singleton;

import com.google.inject.*;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.reactor.ReactorModule;
import org.midonet.midolman.services.DatapathConnectionService;
import org.midonet.netlink.BufferPool;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.throttling.RandomEarlyDropThrottlingGuardFactory;
import org.midonet.util.throttling.ThrottlingGuard;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public class DatapathModule extends PrivateModule {
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface SIMULATION_THROTTLING_GUARD {}
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface NETLINK_SEND_BUFFER_POOL {}

    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        requireBinding(Reactor.class);
        requireBinding(MidolmanConfig.class);
        requireBinding(Key.get(SelectLoop.class, ReactorModule.READ_LOOP.class));
        requireBinding(Key.get(SelectLoop.class, ReactorModule.WRITE_LOOP.class));

        bind(ThrottlingGuard.class).
                annotatedWith(SIMULATION_THROTTLING_GUARD.class).
                toProvider(SimulationThrottlingGuardProvider.class).
                asEagerSingleton();
        expose(Key.get(ThrottlingGuard.class,
            SIMULATION_THROTTLING_GUARD.class));

        bind(BufferPool.class).
                annotatedWith(NETLINK_SEND_BUFFER_POOL.class).
                toProvider(NetlinkSendBufferPoolProvider.class).
                asEagerSingleton();
        expose(Key.get(BufferPool.class, NETLINK_SEND_BUFFER_POOL.class));

        bindOvsDatapathConnection();
        expose(OvsDatapathConnection.class);

        bind(DatapathConnectionService.class)
            .asEagerSingleton();
        expose(DatapathConnectionService.class);
    }

    protected void bindOvsDatapathConnection() {
        bind(OvsDatapathConnection.class)
            .toProvider(OvsDatapathConnectionProvider.class)
            .in(Singleton.class);
    }

    private static class NetlinkSendBufferPoolProvider
            implements Provider<BufferPool> {
        @Inject MidolmanConfig config;

        @Override
        public BufferPool get() {
            return new BufferPool(config.getSendBufferPoolInitialSize(),
                                  config.getSendBufferPoolMaxSize(),
                                  config.getSendBufferPoolBufSizeKb() * 1024);
        }
    }

    private static class SimulationThrottlingGuardProvider
            implements Provider<ThrottlingGuard> {
        @Inject MidolmanConfig config;

        @Override
        public ThrottlingGuard get() {
            return new RandomEarlyDropThrottlingGuardFactory(
                    config.getSimulationThrottlingLowWaterMark(),
                    config.getSimulationThrottlingHighWaterMark()).
                    build("SimulationThrottlingGuard");
        }
    }
}
