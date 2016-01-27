/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster

import java.util.concurrent.{ExecutorService, Executors}

import com.google.inject.AbstractModule
import com.google.inject.name.Names
import com.typesafe.scalalogging.Logger

import org.midonet.util.concurrent.NamedThreadFactory

class ExecutorsModule(config: ClusterConfig, log: Logger) extends AbstractModule {

    override def configure(): Unit = {
        val executorService = Executors.newScheduledThreadPool(
            poolSize, new NamedThreadFactory("cluster-pool", true))

        bind(classOf[ExecutorService])
            .annotatedWith(Names.named("cluster-pool"))
            .toInstance(executorService)
    }

    private def poolSize: Int = {
        var count = Runtime.getRuntime.availableProcessors()
        if (count > config.threadPoolSize) {
            count = config.threadPoolSize
        }
        log info s"Cluster thread pool started with $count threads"
        count
    }
}
