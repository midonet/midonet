/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.cluster.state;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import com.google.inject.name.Names;

import org.midonet.cluster.backend.cassandra.CassandraClient;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.cluster.zookeeper.ZkConnectionProvider;
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
                ZkConnectionProvider.DIRECTORY_REACTOR_TAG)));

        bind(FlowStateStorageFactory.class).toProvider(FlowStateStorageFactoryProvider.class)
                .asEagerSingleton();
        expose(FlowStateStorageFactory.class);
    }

    private static class FlowStateStorageFactoryProvider implements Provider<FlowStateStorageFactory> {
        @Inject
        MidolmanConfig config;

        @Inject
        @Named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG)
        Reactor reactor;

        @Override
        public FlowStateStorageFactory get() {
            CassandraClient cass = new CassandraClient(
                    config.cassandra().servers(), config.cassandra().cluster(),
                    "MidonetFlowState", config.cassandra().replication_factor(),
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
