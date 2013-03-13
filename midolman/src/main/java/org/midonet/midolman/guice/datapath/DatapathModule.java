/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.inject.Singleton;

import com.google.inject.*;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.services.DatapathConnectionService;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.throttling.RandomEarlyDropThrottlingGuardFactory;
import org.midonet.util.throttling.ThrottlingGuardFactory;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public class DatapathModule extends PrivateModule {
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface DATAPATH_THROTTLING_GUARD {}
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface SIMULATION_THROTTLING_GUARD {}

    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        requireBinding(Reactor.class);
        requireBinding(SelectLoop.class);

        bind(ThrottlingGuardFactory.class).
                annotatedWith(DATAPATH_THROTTLING_GUARD.class).
                toProvider(DatapathThrottlingGuardFactoryProvider.class).
                asEagerSingleton();
        bind(ThrottlingGuardFactory.class).
                annotatedWith(SIMULATION_THROTTLING_GUARD.class).
                toProvider(SimulationThrottlingGuardFactoryProvider.class).
                asEagerSingleton();
        expose(Key.get(ThrottlingGuardFactory.class,
            SIMULATION_THROTTLING_GUARD.class));
        expose(Key.get(ThrottlingGuardFactory.class,
            DATAPATH_THROTTLING_GUARD.class));

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

    private static class DatapathThrottlingGuardFactoryProvider
            implements Provider<ThrottlingGuardFactory> {
        @Inject MidolmanConfig config;

        @Override
        public ThrottlingGuardFactory get() {
            return new RandomEarlyDropThrottlingGuardFactory(
                    config.getDatapathThrottlingLowWaterMark(),
                    config.getDatapathThrottlingHighWaterMark());
        }
    }

    private static class SimulationThrottlingGuardFactoryProvider
            implements Provider<ThrottlingGuardFactory> {
        @Inject MidolmanConfig config;

        @Override
        public ThrottlingGuardFactory get() {
            return new RandomEarlyDropThrottlingGuardFactory(
                    config.getSimulationThrottlingLowWaterMark(),
                    config.getSimulationThrottlingHighWaterMark());
        }
    }
}
