/*
* Copyright 2014 Midokura Europe SARL
*/
package org.midonet.midolman.guice.state;

import com.google.inject.*;
import com.google.inject.name.Named;
import com.google.inject.name.Names;

import org.midonet.cassandra.CassandraClient;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.zookeeper.ZKConnectionProvider;
import org.midonet.midolman.state.FlowStateStorage;
import org.midonet.midolman.state.FlowStateStorage$;
import org.midonet.midolman.state.FlowStateStorageFactory;
import org.midonet.util.eventloop.Reactor;


public class FlowStateStorageModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(MidolmanConfig.class);
        requireBinding(Key.get(Reactor.class, Names.named(
                ZKConnectionProvider.DIRECTORY_REACTOR_TAG)));

        bind(FlowStateStorageFactory.class).toProvider(FlowStateStorageFactoryProvider.class)
                .asEagerSingleton();
        expose(FlowStateStorageFactory.class);
    }

    private static class FlowStateStorageFactoryProvider implements Provider<FlowStateStorageFactory> {
        @Inject
        MidolmanConfig config;

        @Inject
        @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
        Reactor reactor;

        @Override
        public FlowStateStorageFactory get() {
            CassandraClient cass = new CassandraClient(
                    config.getCassandraServers(), config.getCassandraCluster(),
                    "MidonetFlowState", config.getCassandraReplicationFactor(),
                    FlowStateStorage$.MODULE$.SCHEMA(), reactor);
            cass.connect();
            return new FlowStateStorageFactoryImpl(cass);
        }
    }

    private static class FlowStateStorageFactoryImpl implements FlowStateStorageFactory {
        CassandraClient cass;

        public FlowStateStorageFactoryImpl(CassandraClient cass) {
            this.cass = cass;
        }

        @Override
        public FlowStateStorage create() {
            return FlowStateStorage$.MODULE$.apply(cass);
        }
    }
}
