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

package org.midonet.cluster.storage

import com.google.inject.{Inject, PrivateModule, Provider}
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.services.{MidonetBackend, MidonetBackendService}
import org.midonet.config.ConfigProvider

/** This Guice module is dedicated to declare general-purpose dependencies that
  * are exposed to MidoNet components that need to access the various storage
  * backends that exist within a deployment.  It should not include any
  * dependencies linked to any specific service or component. */
class MidonetBackendModule extends PrivateModule {

    override def configure(): Unit = {
        bindCurator()
        bindStorage()
        bindLockFactory()

        bind(classOf[MidonetBackendConfig])
            .toProvider(classOf[MidonetBackendConfigProvider])
        expose(classOf[MidonetBackendConfig])
    }

    protected def bindLockFactory(): Unit = {
        bind(classOf[ZookeeperLockFactory]).asEagerSingleton()
        expose(classOf[ZookeeperLockFactory])
    }

    protected def bindStorage(): Unit = {
        bind(classOf[MidonetBackend])
            .to(classOf[MidonetBackendService])
            .asEagerSingleton()
        expose(classOf[MidonetBackend])
    }

    private def bindCurator(): Unit = {
        bind(classOf[CuratorFramework])
            .toProvider(classOf[CuratorFrameworkProvider])
            .asEagerSingleton()
        expose(classOf[CuratorFramework])
    }

}

class MidonetBackendConfigProvider extends Provider[MidonetBackendConfig] {
    @Inject
    var cfgProvider: ConfigProvider = _
    override def get(): MidonetBackendConfig =
        cfgProvider.getConfig(classOf[MidonetBackendConfig])
}

class CuratorFrameworkProvider @Inject()(cfg: MidonetBackendConfig)
    extends Provider[CuratorFramework] {
    override def get(): CuratorFramework = CuratorFrameworkFactory.newClient (
        cfg.zookeeperHosts, new ExponentialBackoffRetry(1000, 10)
    )
}

