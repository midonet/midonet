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

import java.util.concurrent.{Executors, TimeUnit}

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.google.inject.Inject

import org.apache.curator.framework.recipes.leader.LeaderLatchListener
import org.reflections.Reflections
import org.slf4j.LoggerFactory

import rx.schedulers.Schedulers
import rx.{Observer, Subscription}

import org.midonet.cluster.models.Topology.ServiceContainer
import org.midonet.cluster.services.containers.schedulers._
import org.midonet.cluster.services._
import org.midonet.cluster.{ClusterConfig, ContainersLog}
import org.midonet.containers
import org.midonet.containers.ContainerDelegate
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion.{Context, Minion, MinionService}
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.logging.Logger

object ContainerService {

    val SchedulingBufferSize = 0x100000
    val MaximumFailures = 0x100
    val ShutdownTimeoutSeconds = 5

    @VisibleForTesting
    @inline
    def latchPath(config: ClusterConfig) = {
        s"${config.backend.rootKey}/containers/leader-latch"
    }

}

/**
  * This is the cluster service for container management across the MidoNet
  * agents. The service monitors the current configuration of service
  * containers and the set of active agent nodes, and schedules the creation
  * or deletion of the containers via NSDB.
  */
@MinionService(name = "containers", runsOn = TargetNode.CLUSTER)
class ContainerService @Inject()(nodeContext: Context,
                                 backend: MidonetBackend,
                                 reflections: Reflections,
                                 latchProvider: LeaderLatchProvider,
                                 config: ClusterConfig)
    extends Minion(nodeContext) {

    import ContainerService._

    private val log = Logger(LoggerFactory.getLogger(ContainersLog))

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

    private val context = containers.Context(backend.store, backend.stateStore,
                                             eventExecutor, eventScheduler, log)

    // A Curator latch used to elect a leader among all ContainerService minions
    private val leaderLatch = latchProvider.get(latchPath(config))
    private val leaderLatchListener = new LeaderLatchListener {
        override def isLeader(): Unit = {
            log.info("Cluster node is container manager leader")
            startScheduling()
        }
        override def notLeader(): Unit = {
            log.info("Cluster node is no longer container manager leader")
            stopScheduling()
        }
    }
    leaderLatch.addListener(leaderLatchListener, eventExecutor)

    @volatile private var scheduler: ServiceScheduler = null
    @volatile private var schedulerSubscription: Subscription = null
    @volatile private var errorCount = 0

    private val schedulerObserver = new Observer[SchedulerEvent] {
        override def onNext(event: SchedulerEvent): Unit = {
            handleEvent(event)
        }
        override def onCompleted(): Unit = {
            log info "Containers notification stream closed"
        }
        override def onError(e: Throwable): Unit = {
            val newCount = errorCount + 1
            log.warn(s"Unexpected error $newCount on the container " +
                     "notification stream", e)
            if (newCount < MaximumFailures) {
                startScheduling()
                errorCount = newCount
            } else {
                log.error("Too many errors emitted by container notification " +
                          "stream, closing and giving up leadership. This is " +
                          "a non-recoverable error, please restart this " +
                          "Cluster node.")
                notifyFailed(e)
                doStop()
            }
        }
    }

    @VisibleForTesting
    def schedulerObserverErrorCount = errorCount

    /** Indicates whether the service is subscribed to container scheduling
      * notifications.
      */
    @VisibleForTesting
    def isSubscribed = {
        val oldSubscription = schedulerSubscription
        oldSubscription != null && !oldSubscription.isUnsubscribed
    }

    protected override def doStart(): Unit = {
        log info "Starting container management service"
        leaderLatch.start()
        notifyStarted()
    }

    protected override def doStop(): Unit = {
        log info "Stopping container management service"
        val schedulerSubscription = this.schedulerSubscription
        if (schedulerSubscription ne null)
            schedulerSubscription.unsubscribe()
        val scheduler = this.scheduler
        if (scheduler ne null)
            scheduler.complete()
        leaderLatch.close()
        leaderLatch.removeListener(leaderLatchListener)
        eventExecutor.shutdown()
        delegateExecutor.shutdown()
        if (!eventExecutor.awaitTermination(ShutdownTimeoutSeconds,
                                            TimeUnit.SECONDS)) {
            log warn "Event executor failed to shutdown within " +
                     s"$ShutdownTimeoutSeconds second"
            eventExecutor.shutdownNow()
        }
        if (!delegateExecutor.awaitTermination(ShutdownTimeoutSeconds,
                                               TimeUnit.SECONDS)) {
            log warn "Delegate executor failed to shutdown within " +
                     s"$ShutdownTimeoutSeconds second"
            delegateExecutor.shutdownNow()
        }
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

    private def stopScheduling(): Unit = {
        if (schedulerSubscription ne null) {
            schedulerSubscription.unsubscribe()
            schedulerSubscription = null
        }
        if (scheduler ne null) {
            scheduler.complete()
            scheduler = null
        }
    }

    private def startScheduling(): Unit = {
        stopScheduling()
        scheduler = newScheduler()
        schedulerSubscription = scheduler.observable
            .onBackpressureBuffer(SchedulingBufferSize)
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
                case Notify(container, hostId) =>
                    log info s"Container ${container.getId} scheduled at " +
                             s"host $hostId"
            }
        } catch {
            case NonFatal(e) =>
                log.warn("Container delegate failed during scheduling " +
                         s"event: $event", e)
        }
    }

}
