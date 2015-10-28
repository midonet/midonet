/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.midolman.cluster;

import com.codahale.metrics.MetricRegistry;
import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Scopes;
import com.google.inject.name.Named;

import org.midonet.cluster.Client;
import org.midonet.cluster.backend.cassandra.CassandraClient;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.flows.FlowInvalidator;
import org.midonet.midolman.logging.FlowTracingAppender;
import org.midonet.midolman.logging.FlowTracingSchema$;
import org.midonet.midolman.monitoring.FlowRecorderFactory;
import org.midonet.midolman.services.DatapathConnectionService;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.services.MidolmanService;
import org.midonet.midolman.services.SelectLoopService;
import org.midonet.midolman.simulation.Chain;
import org.midonet.midolman.state.NatBlockAllocator;
import org.midonet.midolman.state.ZkNatBlockAllocator;
import org.midonet.midolman.topology.VirtualTopology;
import org.midonet.util.eventloop.Reactor;

/**
 * Main midolman configuration module
 */
public class MidolmanModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(MidolmanConfig.class);
        requireBinding(Client.class);
        requireBinding(DatapathConnectionService.class);
        requireBinding(MidolmanActorsService.class);

        bind(MidolmanService.class).asEagerSingleton();
        expose(MidolmanService.class);

        bind(VirtualTopology.class)
            .asEagerSingleton();
        expose(VirtualTopology.class);

        bind(MetricRegistry.class).toInstance(new MetricRegistry());
        expose(MetricRegistry.class);

        bind(FlowRecorderFactory.class).asEagerSingleton();
        expose(FlowRecorderFactory.class);

        bind(SelectLoopService.class)
            .asEagerSingleton();
        expose(SelectLoopService.class);

        requestStaticInjection(Chain.class);

        bind(FlowInvalidator.class).toProvider(new Provider<FlowInvalidator>() {
            @Inject
            private MidolmanConfig config;

            @Inject
            private MidolmanActorsService service;

            @Override
            public FlowInvalidator get() {
                return new FlowInvalidator(service, config.simulationThreads());
            }
        }).asEagerSingleton();
        expose(FlowInvalidator.class);

        bindAllocator();

        bind(FlowTracingAppender.class)
            .toProvider(FlowTracingAppenderProvider.class)
            .asEagerSingleton();
        expose(FlowTracingAppender.class);
    }

    protected void bindAllocator() {
        bind(NatBlockAllocator.class).to(ZkNatBlockAllocator.class)
            .in(Scopes.SINGLETON);
        expose(NatBlockAllocator.class);
    }

    private static class FlowTracingAppenderProvider
        implements Provider<FlowTracingAppender> {
        @Inject
        MidolmanConfig config;

        @Override
        public FlowTracingAppender get() {
            CassandraClient cass = new CassandraClient(
                    config.zookeeper(), config.cassandra(),
                    FlowTracingSchema$.MODULE$.KEYSPACE_NAME(),
                    FlowTracingSchema$.MODULE$.SCHEMA(),
                    FlowTracingSchema$.MODULE$.SCHEMA_TABLE_NAMES());
            return new FlowTracingAppender(cass.connect());
        }
    }
}
