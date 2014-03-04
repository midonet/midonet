/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.datapath;

import javax.inject.Singleton;

import com.google.inject.*;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.io.*;
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

        public final String ONE_TO_ONE = "one_to_one";
        public final String ONE_TO_MANY = "one_to_many";

        @Inject
        MidolmanConfig config;

        @Inject
        TokenBucketPolicy tbPolicy;

        @Override
        public UpcallDatapathConnectionManager get() {
            String threadingModel = config.getInputChannelThreading();
            if (ONE_TO_ONE.equalsIgnoreCase(threadingModel)) {
                return new OneToOneDpConnManager(config, tbPolicy);
            } else if (ONE_TO_MANY.equalsIgnoreCase(threadingModel)) {
                return new OneToManyDpConnManager(config, tbPolicy);
            } else {
                throw new IllegalArgumentException(
                    "Unknown value for input_channel_threading: " + threadingModel);
            }
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
