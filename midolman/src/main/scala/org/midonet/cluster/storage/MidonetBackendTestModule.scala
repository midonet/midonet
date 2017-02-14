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
package org.midonet.cluster.storage

import com.typesafe.config.Config
import org.apache.curator.framework.CuratorFramework

import org.midonet.cluster.data.storage.{InMemoryStorage, StateStorage, Storage}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.conf.MidoTestConfigurator

/* In the main source tree to allow usage by other module's tests, without
 * creating a jar. */
object MidonetBackendTest  {
    final val StorageReactorTag = "storageReactor"
}

class MidonetTestBackend extends MidonetBackend {

    private val inMemoryZoom = new InMemoryStorage()

    override def store: Storage = inMemoryZoom
    override def stateStore: StateStorage = inMemoryZoom
    override def curator: CuratorFramework = null

    override def doStart(): Unit = {
        MidonetBackend.setupBindings(store, stateStore)
        notifyStarted()
    }

    override def doStop(): Unit = notifyStopped()
}

object MidonetBackendTestModule {
    def apply() = new MidonetBackendTestModule
}

/** Provides all dependencies for the new backend, using a FAKE zookeeper. */
class MidonetBackendTestModule(cfg: Config = MidoTestConfigurator.forAgents())
    extends MidonetBackendModule(new MidonetBackendConfig(cfg)) {

    override protected def bindStorage(): Unit = {
        bind(classOf[MidonetBackend])
            .to(classOf[MidonetTestBackend])
            .asEagerSingleton()
        expose(classOf[MidonetBackend])
    }

    override protected def bindCurator(): Unit = {

    }
}
