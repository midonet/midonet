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

import java.util.concurrent.{ScheduledExecutorService, ExecutorService, Executors}

import com.google.inject.AbstractModule
import com.google.inject.name.Names
import com.typesafe.scalalogging.Logger

import org.midonet.util.concurrent.NamedThreadFactory

object ExecutorsModule {

    /**
      * Creates a scheduled thread pool executor where cluster services can
      * execute small jobs. The pool size equals the number of available
      * processors but not greater than the maximum thread pool size specified
      * in the cluster configuration.
      *
      * The threads in the pool are shut down gracefully when stopping the
      * cluster node, waiting for all tasks to complete their execution, but
      * no more than thread pool shutdown timeout interval specified in the
      * cluster configuration. Therefore, if any service schedules future tasks
      * on the cluster thread pool, it is its responsibility to cancel their
      * scheduling upon stopping the service.
      */
    def apply(config: ClusterConfig, log: Logger): ScheduledExecutorService = {
        var poolSize = Runtime.getRuntime.availableProcessors()
        if (poolSize < config.threadPoolSize) {
            poolSize = config.threadPoolSize
        }
        log info s"Cluster thread pool started with $poolSize threads"

        Executors.newScheduledThreadPool(
            poolSize, new NamedThreadFactory("cluster-pool", isDaemon = true))
    }

}

class ExecutorsModule(executor: ScheduledExecutorService) extends AbstractModule {

    override def configure(): Unit = {
        bind(classOf[ExecutorService])
            .annotatedWith(Names.named("cluster-pool"))
            .toInstance(executor)
        bind(classOf[ScheduledExecutorService])
            .annotatedWith(Names.named("cluster-pool"))
            .toInstance(executor)
    }
}
