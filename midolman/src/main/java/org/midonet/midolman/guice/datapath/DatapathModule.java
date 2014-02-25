/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.inject.Singleton;

import com.google.inject.*;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.io.ManagedDatapathConnection;
import org.midonet.midolman.io.OneToOneConnectionPool;
import org.midonet.midolman.io.DatapathConnectionPool;
import org.midonet.midolman.services.DatapathConnectionService;
import org.midonet.odp.protos.OvsDatapathConnection;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public class DatapathModule extends PrivateModule {
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface UPCALL_DATAPATH_CONNECTION {}

    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        requireBinding(MidolmanConfig.class);

        bindDatapathConnection(UPCALL_DATAPATH_CONNECTION.class);
        expose(Key.get(ManagedDatapathConnection.class, UPCALL_DATAPATH_CONNECTION.class));

        bindDatapathConnectionPool();
        expose(DatapathConnectionPool.class);

        bind(DatapathConnectionService.class)
            .asEagerSingleton();
        expose(DatapathConnectionService.class);
    }

    protected void bindDatapathConnectionPool() {
        bind(DatapathConnectionPool.class)
                .toInstance(new OneToOneConnectionPool("netlink.requests", 4));
    }

    protected void bindDatapathConnection(Class<? extends Annotation > klass) {
        bind(ManagedDatapathConnection.class)
            .annotatedWith(klass)
            .toProvider(ManagedDatapathConnectionProvider.class)
            .in(Singleton.class);
    }
}
