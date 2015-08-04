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
package org.midonet.midolman.cluster;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;

import com.codahale.metrics.MetricRegistry;
import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Scopes;
import com.google.inject.name.Names;

import org.midonet.cluster.backend.cassandra.CassandraClient;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.midolman.ShardedSimulationBackChannel;
import org.midonet.midolman.ShardedSimulationBackChannel$;
import org.midonet.midolman.SimulationBackChannel;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.logging.FlowTracingAppender;
import org.midonet.midolman.logging.FlowTracingSchema$;
import org.midonet.midolman.monitoring.FlowRecorderFactory;
import org.midonet.midolman.services.DatapathConnectionService;
import org.midonet.midolman.services.HostIdProviderService;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.services.MidolmanService;
import org.midonet.midolman.services.SelectLoopService;
import org.midonet.midolman.simulation.Chain;
import org.midonet.midolman.state.NatBlockAllocator;
import org.midonet.midolman.state.PeerResolver;
import org.midonet.midolman.state.ZkNatBlockAllocator;
import org.midonet.midolman.topology.VirtualTopology;
import org.midonet.midolman.topology.VirtualTopology$;
import org.midonet.util.functors.Predicate;

/**
 * Main midolman configuration module
 */
public class MidolmanModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(MidolmanConfig.class);
        requireBinding(DatapathConnectionService.class);
        requireBinding(MidolmanActorsService.class);
        requireBinding(MetricRegistry.class);
        requireBinding(HostIdProviderService.class);
        requireBinding(MidonetBackend.class);
        requireBinding(VirtualTopology.class);

        bind(MidolmanService.class).asEagerSingleton();
        expose(MidolmanService.class);

        bindSimulationBackChannel();
        bindAllocator();

        bindVirtualTopology();

        bind(FlowTracingAppender.class)
            .toProvider(FlowTracingAppenderProvider.class)
            .asEagerSingleton();
        expose(FlowTracingAppender.class);

        bind(FlowRecorderFactory.class).asEagerSingleton();
        expose(FlowRecorderFactory.class);

        bindZebraSelectLoop();
        expose(SelectLoopService.class);

        bindPeerResolver();
        expose(PeerResolver.class);

        requestStaticInjection(Chain.class);
    }

    protected void bindZebraSelectLoop() {
        bind(SelectLoopService.class)
            .asEagerSingleton();
    }

    protected void bindPeerResolver() {
        bind(PeerResolver.class).toProvider(new Provider<PeerResolver>() {
            @Inject
            private HostIdProviderService hostIdProvider;

            @Inject
            private MidonetBackend backend;

            @Inject
            private VirtualTopology virtualTopology;

            @Override
            public PeerResolver get() {
                return new PeerResolver(hostIdProvider, backend, virtualTopology);
            }
        }).asEagerSingleton();
    }

    protected void bindSimulationBackChannel() {
        bind(ShardedSimulationBackChannel.class).toProvider(new Provider<ShardedSimulationBackChannel>() {
            @Inject
            private MidolmanActorsService service;

            @Override
            public ShardedSimulationBackChannel get() {
                return ShardedSimulationBackChannel$.MODULE$.apply(service);
            }
        }).asEagerSingleton();
        bind(SimulationBackChannel.class).to(ShardedSimulationBackChannel.class);
        expose(SimulationBackChannel.class);
        expose(ShardedSimulationBackChannel.class);
    }

    protected void bindAllocator() {
        bind(NatBlockAllocator.class).to(ZkNatBlockAllocator.class)
            .in(Scopes.SINGLETON);
        expose(NatBlockAllocator.class);
    }

    protected void bindVirtualTopology() {
        final AtomicLong vtThread = new AtomicLong(-1);
        bind(ExecutorService.class)
            .annotatedWith(Names.named(VirtualTopology$.MODULE$.VtExecutorName()))
            .toInstance(Executors.newSingleThreadExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(@Nonnull Runnable r) {
                        Thread thread = new Thread(r, "devices-service");
                        thread.setDaemon(true);
                        vtThread.set(thread.getId());
                        return thread;
                    }
                }));
        bind(Predicate.class)
            .annotatedWith(Names.named(VirtualTopology$.MODULE$.VtExecutorCheckerName()))
            .toInstance(new Predicate() {
                @Override
                public boolean check() {
                    return vtThread.get() < 0
                           || vtThread.get() == Thread.currentThread().getId();
                }
            });
        final AtomicInteger ioThreadIndex = new AtomicInteger(0);
        bind(ExecutorService.class)
            .annotatedWith(Names.named(VirtualTopology$.MODULE$.IoExecutorName()))
            .toInstance(Executors.newCachedThreadPool(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(@Nonnull Runnable r) {
                        Thread t = new Thread(r, "devices-io-" +
                                                ioThreadIndex.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                }));

        bind(VirtualTopology.class)
            .asEagerSingleton();
        expose(VirtualTopology.class);
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
