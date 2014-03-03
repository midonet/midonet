/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import com.google.inject.Key;
import java.lang.annotation.Annotation;
import javax.inject.Singleton;

import org.midonet.midolman.io.DatapathConnectionPool;
import org.midonet.midolman.io.ManagedDatapathConnection;
import org.midonet.midolman.io.MockDatapathConnectionPool;

public class MockDatapathModule extends DatapathModule {
    @Override
    protected void bindDatapathConnection(Class<? extends Annotation> klass) {
        bind(ManagedDatapathConnection.class)
            .annotatedWith(klass)
            .toProvider(MockManagedDatapathConnectionProvider.class)
            .in(Singleton.class);
    }

    @Override
    protected void bindDatapathConnectionPool() {
        requireBinding(Key.get(ManagedDatapathConnection.class,
                               UPCALL_DATAPATH_CONNECTION.class));
        bind(DatapathConnectionPool.class)
            .toInstance(new MockDatapathConnectionPool());
    }
}
