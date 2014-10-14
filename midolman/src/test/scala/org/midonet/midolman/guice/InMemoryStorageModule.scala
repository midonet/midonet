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

import com.google.inject.name.{Named, Names}
import com.google.inject.{Inject, PrivateModule, Provider}

import org.midonet.cluster.data.storage.{InMemoryStorage, Storage}
import org.midonet.util.eventloop.{CallingThreadReactor, Reactor}

object InMemoryStorageModule {

    final val storageReactorTag = "storageReactor"

    private class StorageProvider extends Provider[Storage] {

        @Inject
        @Named(storageReactorTag)
        var reactor: Reactor = _

        override def get: Storage = new InMemoryStorage(reactor)
    }
}

class InMemoryStorageModule extends PrivateModule {

    import InMemoryStorageModule._

    protected override def configure(): Unit = {
        bind(classOf[Reactor])
            .annotatedWith(Names.named(storageReactorTag))
            .to(classOf[CallingThreadReactor])
            .asEagerSingleton()
        bind(classOf[Storage])
            .toProvider(classOf[StorageProvider])
            .asEagerSingleton()
        expose(classOf[Storage])
    }
}
