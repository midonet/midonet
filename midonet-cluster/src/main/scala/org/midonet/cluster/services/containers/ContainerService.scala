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

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.reflections.Reflections
import org.slf4j.LoggerFactory

import rx.{Subscription, Subscriber}
import rx.schedulers.Schedulers

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.models.Topology.ServiceContainer
import org.midonet.cluster.services.containers.schedulers._
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.cluster.{ClusterConfig, containersLog}
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.functors.makeAction0

object ContainerService {

    private val SchedulingBufferSize = 0x1000
    private val MaximumFailures = 0x100

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
                                 reflections: Reflections,
                                 config: ClusterConfig)
    extends Minion(nodeContext) {

    import ContainerService._

    private val log = Logger(LoggerFactory.getLogger(containersLog))

    override def isEnabled = config.containers.isEnabled

    private val eventExecutor = Executors.newSingleThreadExecutor(
        new NamedThreadFactory("container-service", isDaemon = true))
    private val eventScheduler = Schedulers.from(eventExecutor)

    private val delegateExecutor = Executors.newSingleThreadExecutor(
        new NamedThreadFactory("container-config", isDaemon = true))
    private val delegateScheduler = Schedulers.from(delegateExecutor)

    private val delegateProvider = new ContainerDelegateProvider(backend, reflections,
                                                                 config, log)
    private val delegates = new TrieMap[String, ContainerDelegate]

    private val context = schedulers.Context(backend.store, backend.stateStore,
                                             eventExecutor, eventScheduler, log)

    @volatile private var scheduler: ServiceScheduler = null
    @volatile private var schedulerSubscription: Subscription = null
    @volatile private var errorCount = 0

    private val schedulerObserver = new Subscriber[SchedulerEvent] {
        override def onNext(event: SchedulerEvent): Unit = {
            handleEvent(event)
        }
        override def onCompleted(): Unit = {
            log info "Containers notification stream closed"
        }
        override def onError(e: Throwable): Unit = {
            log.error("Unexpected error on the container notification stream", e)
            // Increment the error count, and restart the scheduling.
            errorCount += 1
            if (errorCount < MaximumFailures) {
                startScheduling()
            }
        }
    }

    /** Indicates whether the service is subscribed to container scheduling
      * notifications.
      */
    @VisibleForTesting
    def isSubscribed = !schedulerSubscription.isUnsubscribed

    protected override def doStart(): Unit = {
        log info "Starting container management service"
        startScheduling()

        notifyStarted()
    }

    protected override def doStop(): Unit = {
        log info "Stopping container management service"
        val schedulerSubscription = this.schedulerSubscription
        val scheduler = this.scheduler
        if (schedulerSubscription ne null)
            schedulerSubscription.unsubscribe()
        if (scheduler ne null)
            scheduler.complete()
        eventExecutor.shutdown()
        delegateExecutor.shutdown()
        notifyStopped()
    }

    @inline
    protected def newScheduler(): ServiceScheduler = {
        new ServiceScheduler(context, config.containers)
    }

    @inline
    protected def delegate(container: ServiceContainer)
    : Option[ContainerDelegate] = {
        delegates get container.getServiceType
    }

    @inline
    protected def delegateOf(container: ServiceContainer): ContainerDelegate = {
        delegates.getOrElse(container.getServiceType, {
            val delegate = delegateProvider.getInstance(container.getServiceType)
            delegates.putIfAbsent(container.getServiceType, delegate)
                     .getOrElse(delegate)
        })
    }

    private def startScheduling(): Unit = {
        if (schedulerSubscription ne null) {
            schedulerSubscription.unsubscribe()
        }
        if (scheduler ne null) {
            scheduler.complete()
        }

        scheduler = newScheduler()
        schedulerSubscription = scheduler.observable
            .onBackpressureBuffer(SchedulingBufferSize, makeAction0 {
                log error "Scheduling buffer overflow"
            })
            .observeOn(delegateScheduler)
            .subscribe(schedulerObserver)
    }

    private def handleEvent(event: SchedulerEvent): Unit = {
        log debug s"Container scheduling event: $event"
        errorCount = 0
        try {
            val delegate = delegateOf(event.container)
            event match {
                case Schedule(container, hostId) =>
                    delegate.onScheduled(container, hostId)
                case Up(container, status) =>
                    delegate.onUp(container, status)
                case Down(container, status) =>
                    delegate.onDown(container, status)
                case Unschedule(container, hostId) =>
                    delegate.onUnscheduled(container, hostId)
            }
        } catch {
            case NonFatal(e) =>
                log.warn("Container delegate failed during scheduling " +
                         s"event: $event", e)
        }
    }
}
