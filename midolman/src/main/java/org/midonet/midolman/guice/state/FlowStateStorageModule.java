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
package org.midonet.midolman.guice.state;

import com.datastax.driver.core.Session;
import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import scala.concurrent.Future;

import org.midonet.cassandra.CassandraClient;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.state.FlowStateStorage;
import org.midonet.midolman.state.FlowStateStorage$;
import org.midonet.midolman.state.FlowStateStorageFactory;


public class FlowStateStorageModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(MidolmanConfig.class);

        bind(FlowStateStorageFactory.class).toProvider(FlowStateStorageFactoryProvider.class)
                .asEagerSingleton();
        expose(FlowStateStorageFactory.class);
    }

    private static class FlowStateStorageFactoryProvider implements Provider<FlowStateStorageFactory> {
        @Inject
        MidolmanConfig config;

        @Override
        public FlowStateStorageFactory get() {
            CassandraClient cass = new CassandraClient(
                    config.getCassandraServers(), config.getCassandraCluster(),
                    "MidonetFlowState", config.getCassandraReplicationFactor(),
                    FlowStateStorage$.MODULE$.SCHEMA(),
                    FlowStateStorage$.MODULE$.SCHEMA_TABLE_NAMES(),
                    config.getZkHosts());
            return new FlowStateStorageFactoryImpl(cass.connect());
        }
    }

    private static class FlowStateStorageFactoryImpl implements FlowStateStorageFactory {
        Future<Session> sessionF;

        public FlowStateStorageFactoryImpl(Future<Session> sessionF) {
            this.sessionF = sessionF;
        }

        @Override
        public FlowStateStorage create() {
            return FlowStateStorage$.MODULE$.apply(sessionF);
        }
    }
}
