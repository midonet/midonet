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

import java.util.concurrent.{Executors, Executor}

import com.google.inject.{Provider, PrivateModule}
import com.google.inject.name.Names

import org.midonet.cluster.{ClusterStateImpl, ClusterState}
import org.midonet.cluster.data.storage.StorageWithOwnership

object ClusterModule {

    final val ClusterExecutor = "clusterExecutor"

    private class ClusterExecutorProvider extends Provider[Executor] {
        override def get = Executors.newCachedThreadPool()
    }
}

/**
 * Guice module to install dependencies for cluster topology and state access.
 */
class ClusterModule extends PrivateModule {

    import ClusterModule._

    protected override def configure(): Unit = {
        requireBinding(classOf[StorageWithOwnership])

        bind(classOf[Executor])
            .annotatedWith(Names.named(ClusterExecutor))
            .toProvider(classOf[ClusterExecutorProvider])
            .asEagerSingleton()

        bind(classOf[ClusterState])
            .to(classOf[ClusterStateImpl])
            .asEagerSingleton()
        expose(classOf[ClusterState])
    }

}
