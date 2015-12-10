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

package org.midonet.cluster.services.containers

import java.util.concurrent.Executors

import com.codahale.metrics.MetricRegistry
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Observer
import rx.schedulers.Schedulers

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.services.containers.schedulers._
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.cluster.{ClusterConfig, ClusterNode, containersLog}

/**
  * This is the cluster service for container management across the MidoNet
  * agents. The service monitors the current configuration of service
  * containers and the set of active agent nodes, and schedules the creation
  * or deletion of the containers via NSDB.
  */
@ClusterService(name = "containers")
class ContainerService @Inject()(nodeContext: Context,
                                 config: ClusterConfig,
                                 metrics: MetricRegistry)
    extends Minion(nodeContext) {

    private val log = Logger(LoggerFactory.getLogger(containersLog))

    override def isEnabled = config.containers.isEnabled

    private val executor = Executors.newSingleThreadExecutor()

    private val scheduler = Schedulers.from(executor)

    private val backend = ClusterNode.injector.getInstance(classOf[MidonetBackend])

    private val delegateFactory = new ContainerDelegateFactory(backend)
    
    protected var containerScheduler: Scheduler = new Scheduler(backend.store,
                                                                backend.stateStore,
                                                                executor)

    override def doStart(): Unit = {
        log info "Starting Container Management service"
        containerScheduler.eventObservable
            .onBackpressureBuffer
            .observeOn(scheduler)
            .subscribe(new Observer[ContainerEvent] {
                override def onCompleted(): Unit = {
                    log.info("Container updates no longer tracked (stream completed)")
                }
                override def onError(t: Throwable): Unit = {
                    log.warn("Container updates no longer tracked (stream error)", t)
                }
                override def onNext(t: ContainerEvent): Unit = t match {
                    case Allocation(container, group, hostId) =>
                        delegateFactory.getContainerDelegate(container)
                            .onCreate(container, group, hostId)
                    case Deallocation(container, hostId) =>
                        delegateFactory.getContainerDelegate(container)
                            .onDelete(container, null, hostId)
                }
            })
        containerScheduler.startScheduling()
        notifyStarted()
    }

    override def doStop(): Unit = {
        notifyStopped()
    }
}

