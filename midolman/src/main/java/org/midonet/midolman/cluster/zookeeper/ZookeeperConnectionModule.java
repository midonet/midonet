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
package org.midonet.midolman.cluster.zookeeper;

import com.google.inject.PrivateModule;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.name.Names;

import org.midonet.cluster.backend.Directory;
import org.midonet.cluster.backend.zookeeper.ZkConnection;
import org.midonet.cluster.backend.zookeeper.ZkConnectionAwareWatcher;
import org.midonet.cluster.backend.zookeeper.ZkConnectionProvider;
import org.midonet.cluster.backend.zookeeper.ZkConnectionProvider.BGP_ZK_INFRA;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.TryCatchReactor;

/**
 * Modules which creates the proper bindings for building a Directory backed up
 * by zookeeper.
 */
public class ZookeeperConnectionModule extends PrivateModule {

    private final Class<? extends ZkConnectionAwareWatcher> connWatcherImpl;
    private final Reactor directoryReactor;
    private final Reactor bgpReactor;

    public ZookeeperConnectionModule(Class<? extends ZkConnectionAwareWatcher>
            connWatcherImpl) {
        ZookeeperReactorProvider zkReactorProvider =
            new ZookeeperReactorProvider();
        this.connWatcherImpl = connWatcherImpl;
        this.directoryReactor = zkReactorProvider.get();
        this.bgpReactor = zkReactorProvider.get();
    }

    public Reactor getDirectoryReactor() { return directoryReactor; }
    public Reactor getBgpReactor() { return bgpReactor; }

    @Override
    protected void configure() {
        requireBinding(MidonetBackendConfig.class);

        bindZookeeperConnection();
        bindDirectory();
        bindReactor();

        expose(Key.get(Reactor.class,
                       Names.named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG)));
        expose(Key.get(Reactor.class, BGP_ZK_INFRA.class));

        expose(Directory.class);

        bindZkConnectionWatcher();
    }

    protected void bindZkConnectionWatcher() {
        bind(ZkConnectionAwareWatcher.class)
            .to(connWatcherImpl)
            .asEagerSingleton();
        expose(ZkConnectionAwareWatcher.class);

        bind(ZkConnectionAwareWatcher.class)
            .annotatedWith(BGP_ZK_INFRA.class)
            .to(connWatcherImpl)
            .asEagerSingleton();

        expose(ZkConnectionAwareWatcher.class)
            .annotatedWith(BGP_ZK_INFRA.class);
    }

    protected void bindDirectory() {
        bind(Directory.class)
            .toProvider(DirectoryProvider.class)
            .asEagerSingleton();
    }

    protected void bindZookeeperConnection() {
        bind(ZkConnection.class)
            .toProvider(ZkConnectionProvider.class)
            .asEagerSingleton();
        expose(ZkConnection.class);
    }

    protected void bindReactor() {
        bind(Reactor.class).annotatedWith(
            Names.named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG))
            .toInstance(directoryReactor);
        bind(Reactor.class)
            .annotatedWith(BGP_ZK_INFRA.class)
            .toInstance(bgpReactor);
    }

    public static class ZookeeperReactorProvider
        implements Provider<Reactor> {

        @Override
        public Reactor get() {
            return new TryCatchReactor("zookeeper", 1);
        }
    }
}
