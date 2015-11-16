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
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.name.Names;

import org.midonet.cluster.backend.Directory;
import org.midonet.cluster.backend.zookeeper.ZkConnectionAwareWatcher;
import org.midonet.cluster.state.LegacyStorage;
import org.midonet.cluster.state.ZookeeperLegacyStorage;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.util.eventloop.Reactor;

import static org.midonet.cluster.backend.zookeeper.ZkConnectionProvider.DIRECTORY_REACTOR_TAG;

/**
 * Guice module to install dependencies for data access.
 */
public class LegacyClusterModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(MidonetBackendConfig.class);
        requireBinding(Directory.class);
        requireBinding(Key.get(Reactor.class,
                Names.named(DIRECTORY_REACTOR_TAG)));
        requireBinding(ZkConnectionAwareWatcher.class);

        bind(PathBuilder.class).toProvider(PathBuilderProvider.class)
                               .asEagerSingleton();
        expose(PathBuilder.class);

        bind(ZkManager.class).toProvider(BaseZkManagerProvider.class)
                             .asEagerSingleton();
        expose(ZkManager.class);

        bind(LegacyStorage.class).to(ZookeeperLegacyStorage.class)
                                 .asEagerSingleton();
        expose(LegacyStorage.class);

        binder().requireExplicitBindings();
    }

    private static class PathBuilderProvider implements Provider<PathBuilder> {
        @Inject
        MidonetBackendConfig config;

        @Override
        public PathBuilder get() {
            return new PathBuilder(config.rootKey());
        }
    }

    private static class BaseZkManagerProvider implements Provider<ZkManager> {

        @Inject
        Directory directory;

        @Inject
        MidonetBackendConfig config;

        @Override
        public ZkManager get() {
            return new ZkManager(directory, config.rootKey());
        }
    }
}
