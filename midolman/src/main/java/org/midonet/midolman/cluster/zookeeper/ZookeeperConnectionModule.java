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

import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.name.Names;

import org.midonet.cluster.backend.Directory;
import org.midonet.cluster.backend.zookeeper.ZkConnection;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.midolman.state.ZkConnectionAwareWatcher;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.TryCatchReactor;

/**
 * Modules which creates the proper bindings for building a Directory backed up
 * by zookeeper.
 */
public class ZookeeperConnectionModule extends PrivateModule {

    private final Class<? extends ZkConnectionAwareWatcher> connWatcherImpl;

    public ZookeeperConnectionModule(Class<? extends ZkConnectionAwareWatcher>
            connWatcherImpl) {
        this.connWatcherImpl = connWatcherImpl;
    }
    @Override
    protected void configure() {

        binder().requireExplicitBindings();

        requireBinding(MidonetBackendConfig.class);

        bindZookeeperConnection();
        bindDirectory();
        bindReactor();

        expose(Key.get(Reactor.class,
                       Names.named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG)));
        expose(Directory.class);

        bindZkConnectionWatcher();
    }

    protected void bindZkConnectionWatcher() {
        bind(ZkConnectionAwareWatcher.class)
            .to(connWatcherImpl)
            .asEagerSingleton();
        expose(ZkConnectionAwareWatcher.class);
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
            .toProvider(ZookeeperReactorProvider.class)
            .asEagerSingleton();
    }

    public static class ZookeeperReactorProvider
        implements Provider<Reactor> {

        @Override
        public Reactor get() {
            return new TryCatchReactor("zookeeper", 1);
        }
    }
}
