/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import javax.inject.Singleton;

import com.google.inject.*;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.io.DatapathConnectionPool;
import org.midonet.midolman.io.OneToOneConnectionPool;
import org.midonet.midolman.io.UpcallDatapathConnectionManager;
import org.midonet.midolman.services.DatapathConnectionService;


public class DatapathModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        requireBinding(MidolmanConfig.class);

        bindDatapathConnectionPool();
        bindUpcallDatapathConnectionManager();

        expose(DatapathConnectionPool.class);
        expose(UpcallDatapathConnectionManager.class);

        bind(DatapathConnectionService.class)
            .asEagerSingleton();
        expose(DatapathConnectionService.class);
    }

    protected void bindDatapathConnectionPool() {
        bind(DatapathConnectionPool.class)
            .toProvider(DatapathConnectionPoolProvider.class)
            .in(Singleton.class);
    }

    protected void bindUpcallDatapathConnectionManager() {
        bind(UpcallDatapathConnectionManager.class)
            .toProvider(UpcallDatapathConnectionManagerProvider.class)
            .in(Singleton.class);
    }

    public static class UpcallDatapathConnectionManagerProvider
            implements Provider<UpcallDatapathConnectionManager> {

        @Inject
        MidolmanConfig config;

        @Override
        public UpcallDatapathConnectionManager get() {
            return new UpcallDatapathConnectionManager(config);
        }
    }

    public static class DatapathConnectionPoolProvider
            implements Provider<DatapathConnectionPool> {

        @Inject
        MidolmanConfig config;

        @Override
        public DatapathConnectionPool get() {
            return new OneToOneConnectionPool("netlink.requests",
                                              config.getNumOutputChannels(),
                                              config);
        }
    }
}
