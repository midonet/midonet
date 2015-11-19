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
import org.apache.curator.framework.{CuratorFrameworkFactory, CuratorFramework}

import org.midonet.cluster.backend.zookeeper.{ZkConnectionAwareWatcher, ZkConnection}
import org.midonet.cluster.data.storage.{StateTableStorage, InMemoryStorage, StateStorage, Storage}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.conf.MidoTestConfigurator
import org.midonet.util.eventloop.Reactor

/* In the main source tree to allow usage by other module's tests, without
 * creating a jar. */
object MidonetBackendTest  {
    final val StorageReactorTag = "storageReactor"
}

class MidonetTestBackend extends MidonetBackend {

    private val inMemoryZoom = new InMemoryStorage()

    override def store: Storage = inMemoryZoom
    override def stateStore: StateStorage = inMemoryZoom
    override def stateTableStore: StateTableStorage = null
    override def curator: CuratorFramework = null
    override def reactor: Reactor = null
    override def connection: ZkConnection = null
    override def connectionWatcher: ZkConnectionAwareWatcher = null

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

    override protected def bindCuratorFramework() = null

    override protected  def backend(curatorFramework: CuratorFramework) =
        new MidonetTestBackend

    override protected def bindLockFactory(): Unit = {
        // all tests that need it use a real MidonetBackend, with a Test server
    }
}
