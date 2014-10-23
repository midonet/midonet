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
package org.midonet.midolman.guice.cluster;

import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import org.apache.curator.framework.CuratorFramework;

import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.cluster.data.storage.Storage;
import org.midonet.cluster.data.storage.ZookeeperObjectMapper;

/**
 * Guice module to install dependencies for ZOOM-based data access.
 * TODO (ernest): this is a temporary hack to allow tests to run
 * when depending on MidostoreSetupService (which already depends on ZOOM-based
 * storage service
 */
public class MidostoreModule extends PrivateModule {

    private static class StorageServiceProvider
        implements Provider<Storage> {
        @Inject
        ZookeeperConfig cfg;
        @Inject
        CuratorFramework curator;
        @Override public Storage get() {
            return new ZookeeperObjectMapper(cfg.getZkRootPath() + "/zoom",
                                             curator);
        }
    }

    @Override
    protected void configure() {
        bind(Storage.class)
            .toProvider(StorageServiceProvider.class)
            .asEagerSingleton();
        expose(Storage.class);
    }
}
