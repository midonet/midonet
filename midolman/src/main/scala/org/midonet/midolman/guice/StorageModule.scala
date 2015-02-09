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

package org.midonet.midolman.guice

import com.google.inject.{Inject, PrivateModule, Provider}
import org.apache.curator.framework.CuratorFramework
import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.data.storage.{StorageWithOwnership, Storage, ZookeeperObjectMapper}

/**
 * Guice module to install dependencies for ZOOM-based data access.
 */
object StorageModule {

    private class StorageServiceProvider @Inject() (cfg: ZookeeperConfig,
                                                    curator: CuratorFramework)
            extends Provider[StorageWithOwnership] {

        override def get: StorageWithOwnership =
            new ZookeeperObjectMapper(cfg.getZkRootPath + "/zoom", curator)
    }

}

class StorageModule extends PrivateModule {

    protected override def configure(): Unit = {
        bind(classOf[StorageWithOwnership])
            .toProvider(classOf[StorageModule.StorageServiceProvider])
            .asEagerSingleton()
        expose(classOf[StorageWithOwnership])

        bind(classOf[Storage])
            .to(classOf[StorageWithOwnership])
            .asEagerSingleton()
        expose(classOf[Storage])
    }
}