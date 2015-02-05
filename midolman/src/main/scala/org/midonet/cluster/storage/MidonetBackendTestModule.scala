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

import com.google.inject.name.{Named, Names}
import com.google.inject.{Inject, Provider}

import org.midonet.cluster.data.storage.{InMemoryStorage, Storage}
import org.midonet.cluster.storage.MidonetBackendTestModule.TestStorageProvider
import org.midonet.util.eventloop.{CallingThreadReactor, Reactor}

object MidonetBackendTestModule {

    final val StorageReactorTag = "storageReactor"

    private class TestStorageProvider extends Provider[Storage] {

        @Inject
        @Named(StorageReactorTag)
        var reactor: Reactor = _

        override def get: Storage = new InMemoryStorage(reactor)
    }
}

class MidonetBackendTestModule extends MidonetBackendModule {

    override def configure(): Unit = {
        super.configure()
        bind(classOf[Reactor])
            .annotatedWith(
                Names.named(MidonetBackendTestModule.StorageReactorTag))
            .to(classOf[CallingThreadReactor])
            .asEagerSingleton()
    }

    override protected def bindStorage(): Unit = {
        bind(classOf[Storage])
            .toProvider(classOf[TestStorageProvider])
            .asEagerSingleton()
        expose(classOf[Storage])
    }
}
