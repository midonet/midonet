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

import java.util.UUID
import java.util.concurrent.Executors

import com.codahale.metrics.MetricRegistry
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.schedulers.Schedulers
import rx.{Observable, Observer}

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.ServiceContainer
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.cluster.{ClusterConfig, containersLog}

trait ContainerEvent
case class Allocation(container: ServiceContainer, hostId: UUID)
    extends ContainerEvent
case class Deallocation(container: ServiceContainer, hostId: UUID)
    extends ContainerEvent
case class Up(container: ServiceContainer, status: ContainerStatus)
    extends ContainerEvent
case class Down(container: ServiceContainer, status: ContainerStatus)
    extends ContainerEvent

trait ContainerScheduler {

    val eventObservable: Observable[ContainerEvent]

    def startScheduling()

    def stopScheduling()

}

/**
  * This is the cluster service for container management across the MidoNet
  * agents. The service monitors the current configuration of service
  * containers and the set of active agent nodes, and schedules the creation
  * or deletion of the containers via NSDB.
  */
@ClusterService(name = "containers")
class ContainerService @Inject()(nodeContext: Context,
                                 backend: MidonetBackend,
                                 config: ClusterConfig,
                                 metrics: MetricRegistry)
    extends Minion(nodeContext) {

    private val log = Logger(LoggerFactory.getLogger(containersLog))

    override def isEnabled = config.containers.isEnabled

    private val executor = Executors.newSingleThreadExecutor()

    private val scheduler = Schedulers.from(executor)

    private val delegateProvider = new ContainerDelegateProvider(
        backend, config, log)

    protected var containerScheduler: ContainerScheduler = _

    override def doStart(): Unit = {
        log info "Starting Container Management service"
        containerScheduler.eventObservable
            .onBackpressureBuffer
            .observeOn(scheduler)
            .subscribe(new Observer[ContainerEvent] {
                override def onCompleted(): Unit = {
                    log.debug("Container updates no longer tracked (stream completed)")
                }
                override def onError(t: Throwable): Unit = {
                    log.warn("Container updates no longer tracked (stream error)")
                }
                override def onNext(event: ContainerEvent): Unit = {
                    log.debug(s"Container update: $event")
                    event match {
                        case Allocation(container, hostId) =>
                            delegateProvider.getInstance(container.getServiceType)
                                .onAllocation(container, hostId)
                        case Deallocation(container, hostId) =>
                            delegateProvider.getInstance(container.getServiceType)
                                .onDeallocation(container, hostId)
                        case Up(container, status) =>
                            delegateProvider.getInstance(container.getServiceType)
                                .onUp(container, status)
                        case Down(container, status) =>
                            delegateProvider.getInstance(container.getServiceType)
                                .onDown(container, status)
                    }

                }
            })
        containerScheduler.startScheduling()
        notifyStarted()
    }

    override def doStop(): Unit = {
        containerScheduler.stopScheduling()
        notifyStopped()
    }
}
