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

import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Scopes;

import com.codahale.metrics.MetricRegistry;

import com.typesafe.config.ConfigFactory;
import akka.actor.ActorSystem;

import org.midonet.cluster.Client;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.backend.cassandra.CassandraClient;
import org.midonet.midolman.DatapathControllerListener;
import org.midonet.midolman.PacketWorkersService;
import org.midonet.midolman.PacketWorkersServiceDCListener;
import org.midonet.midolman.PacketWorkersServiceImpl;
import org.midonet.midolman.ShardedSimulationBackChannel;
import org.midonet.midolman.SimulationBackChannel;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.datapath.DatapathChannel;
import org.midonet.midolman.datapath.FlowProcessor;
import org.midonet.midolman.flows.FlowInvalidator;
import org.midonet.midolman.logging.FlowTracingAppender;
import org.midonet.midolman.logging.FlowTracingSchema$;
import org.midonet.midolman.monitoring.FlowRecorder;
import org.midonet.midolman.monitoring.FlowRecorderFactory;
import org.midonet.midolman.services.DatapathConnectionService;
import org.midonet.midolman.services.HostIdProviderService;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.services.MidolmanService;
import org.midonet.midolman.services.SelectLoopService;
import org.midonet.midolman.simulation.Chain;
import org.midonet.midolman.state.FlowStateStorageFactory;
import org.midonet.midolman.state.NatBlockAllocator;
import org.midonet.midolman.state.ZkNatBlockAllocator;
import org.midonet.midolman.topology.VirtualTopology;
import org.midonet.util.concurrent.NanoClock;
import org.midonet.util.StatisticalCounter;

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

        requireBinding(MetricRegistry.class);
        requireBinding(HostIdProviderService.class);
        requireBinding(DatapathChannel.class);
        requireBinding(FlowProcessor.class);
        requireBinding(StatisticalCounter.class);
        requireBinding(NanoClock.class);
        requireBinding(FlowStateStorageFactory.class);

        ShardedSimulationBackChannel backChannel
            = new ShardedSimulationBackChannel();
        bind(ShardedSimulationBackChannel.class).toInstance(backChannel);
        expose(ShardedSimulationBackChannel.class);
        bind(SimulationBackChannel.class).toInstance(backChannel);
        expose(SimulationBackChannel.class);

        bind(VirtualTopology.class)
            .asEagerSingleton();
        expose(VirtualTopology.class);

        bind(FlowRecorder.class)
            .toProvider(FlowRecorderProvider.class)
            .asEagerSingleton();
        expose(FlowRecorder.class);

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

        bindActorSystem();
        bindPacketWorkersService();

        bind(DatapathControllerListener.class)
            .to(PacketWorkersServiceDCListener.class).asEagerSingleton();
        expose(DatapathControllerListener.class);

        bind(MidolmanService.class).asEagerSingleton();
        expose(MidolmanService.class);
    }

    protected void bindAllocator() {
        bind(NatBlockAllocator.class).to(ZkNatBlockAllocator.class)
            .in(Scopes.SINGLETON);
        expose(NatBlockAllocator.class);
    }

    static class PacketWorkersServiceProvider
        implements Provider<PacketWorkersService> {
        @Inject
        MidolmanConfig config;

        @Inject
        DatapathChannel dpChannel;

        @Inject
        FlowProcessor flowProcessor;

        @Inject
        NatBlockAllocator natBlockAllocator;

        @Inject
        FlowStateStorageFactory storage;

        @Inject
        ShardedSimulationBackChannel backChannel;

        @Inject
        FlowInvalidator flowInvalidator;

        @Inject
        DataClient clusterDataClient;

        @Inject
        NanoClock clock;

        @Inject
        FlowRecorder flowRecorder;

        @Inject
        MetricRegistry metricsRegistry;

        @Inject
        StatisticalCounter counter;

        @Inject
        ActorSystem actorSystem;

        @Override
        public PacketWorkersService get() {
            return new PacketWorkersServiceImpl(
                    config, dpChannel,
                    flowProcessor,
                    flowInvalidator, clusterDataClient,
                    natBlockAllocator,
                    storage, backChannel, clock,
                    flowRecorder, metricsRegistry,
                    counter, actorSystem);
        }
    }

    protected void bindActorSystem() {
        ActorSystem as = ActorSystem.create("midolman",
                ConfigFactory.load().getConfig("midolman"));
        bind(ActorSystem.class).toInstance(as);
        expose(ActorSystem.class);
    }

    protected void bindPacketWorkersService() {
        bind(PacketWorkersService.class)
            .toProvider(PacketWorkersServiceProvider.class)
            .asEagerSingleton();
        expose(PacketWorkersService.class);
    }

    static class FlowRecorderProvider
        implements Provider<FlowRecorder> {
        @Inject
        MidolmanConfig config;

        @Inject
        HostIdProviderService hostIdProvider;

        @Override
        public FlowRecorder get() {
            return new FlowRecorderFactory(config,
                                           hostIdProvider).newFlowRecorder();
        }
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
