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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID
import java.util.concurrent.TimeUnit

import javax.annotation.Nullable

import scala.collection.mutable
import scala.compat.Platform
import scala.util.Random
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.TextFormat
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Observable.{Operator, OnSubscribe}
import rx.exceptions.Exceptions
import rx.internal.producers.ProducerArbiter
import rx.plugins.RxJavaPlugins
import rx.subjects.{BehaviorSubject, PublishSubject}
import rx.subscriptions.SerialSubscription
import rx.{Producer, Observable, Subscriber, Subscription}

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey}
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.Topology.{ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.containers.schedulers.ContainerScheduler._
import org.midonet.cluster.services.containers.schedulers.HostSelector.Policy
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.{ContainersConfig, containerLog}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1, makeFunc4}

object ContainerScheduler {

    /** Indicates the scheduling state for the current container, representing
      * the possible states in the scheduling state machine.
      */
    trait State {
        def hostId: UUID
    }
    /** The container is not scheduled on a host.
      */
    case object Down extends State {
        override val hostId = null
    }
    /** The container has been scheduled on a host, but that host has not yet
      * reported the container as running. The state includes the time when the
      * container was scheduled, and a timeout subscription for the observable
      * that will emit a notification when the timeout expires.
      */
    case class Scheduled(hostId: UUID, container: ServiceContainer,
                         group: ServiceContainerGroup,
                         timeoutSubscription: Subscription) extends State
    /** The container has been scheduled on a host and has been reported as
      * running.
      */
    case class Up(hostId: UUID, container: ServiceContainer,
                  group: ServiceContainerGroup) extends State

    private case class ContainerSelector(portId: UUID, groupId: UUID)

    private case class BadHost(timestamp: Long, attempts: Long)

}

/**
  * Performs the scheduling operations for a given service container. An
  * instance of this class monitors the specified container, its corresponding
  * service container group and builds a new host selector based on the
  * current host selection policy. Starting from this policy, it monitors
  * the currently available hosts, and performs a random host selection given
  * the host set and available host weights.
  *
  * This class exposes an observable, which emits notifications when the
  * scheduling of the container has changed, as follows:
  * - Created: the container has been scheduled on a host
  * - Up: the container has been reported RUNNING by the host where it has been
  *       scheduled
  * - Down: the container has been reported STOPPED or ERROR by the host where
  *         it has been scheduled
  * - Deleted: the container has been deleted
  *
  * After a host selection is made, the class schedules the container on the
  * host by emitting an `Created` notification, which in turn should call
  * the appropriate container handler.
  *
  * After a scheduling is made, the class monitors that the container state
  * changes to RUNNING at the selected host within the specified timeout
  * interval. If the container does not become RUNNING, or whenever the
  * container or the host status indicate an error, the class performs a
  * rescheduling of the container on the next available host.
  *
  * To ensure that bad hosts, which fail to launch a container, are not
  * reselected in a subsequent scheduling, the class maintains a set of bad
  * hosts with the last time they failed to launch the container and the number
  * of failed attempts. Bad hosts are cleared after they spent a configured
  * interval in the bad hosts list, or when their status has changed.
  *
  * The notification flow is the following:
  *
  * +-----------+   +-----------+
  * | Container |-->|   Group   |
  * +-----------+   +-----------+
  *       |               |
  *       +---------------+---------------------------------------+
  *       |     +---------------+           +-----------+         +-->
  *    policy ->| Host selector |-> hosts ->| Scheduler |-> host -+
  *             +---------------+           +-----------+    |
  *                                               | feedback |
  *                                               +----------+
  *                                    container state / expiration timer
  */
class ContainerScheduler(containerId: UUID, context: Context,
                         config: ContainersConfig,
                         selectorProvider: HostSelectorProvider)
    extends ObjectTracker[SchedulerEvent] {

    private val log = Logger(LoggerFactory.getLogger(containerLog(containerId)))
    private val random = new Random()

    private var state: State = Down

    private var currentContainer: ServiceContainer = null
    private var currentHosts: HostsEvent = Map.empty
    private var hostSelector: HostSelector = null

    private val badHosts = new mutable.HashMap[UUID, BadHost]

    private var groupReady = false
    private var hostsReady = false

    // A subject that emits notifications when retrying a scheduling because a
    // previous attempt has failed either because of a timeout or because the
    // container status did not report a running container. This subject
    // provides the feedback loop necessary to adjust the scheduling based on
    // the reported container status.
    private val feedbackSubject = BehaviorSubject.create[Boolean](false)

    private val containerObservable = context.store
        .observable(classOf[ServiceContainer], containerId)
        .distinctUntilChanged[ContainerSelector](makeFunc1(c =>
            ContainerSelector(c.getPortId.asJava, c.getServiceGroupId.asJava)))
        .observeOn(context.scheduler)
        .doOnNext(makeAction1(containerUpdated))
        .doOnCompleted(makeAction0 { log debug "Container deleted" })

    private val groupSubject = PublishSubject.create[Observable[ServiceContainerGroup]]

    private val groupObservable = Observable
        .switchOnNext(groupSubject)
        .distinctUntilChanged[Policy](makeFunc1(HostSelector.policyOf))
        .doOnNext(makeAction1(policyUpdated))

    private val hostsSubject = PublishSubject.create[Observable[HostsEvent]]

    private val hostsObservable = Observable
        .switchOnNext(hostsSubject)
        .distinctUntilChanged()

    private val namespaceSubject = PublishSubject.create[String]

    private val statusSubscription = context.stateStore
        .keyObservable(namespaceSubject, classOf[ServiceContainer], containerId,
                       StatusKey)
        .filter(makeFunc1(containerStatusUpdated))
        .map[Boolean](makeFunc1(_ => true))
        .subscribe(feedbackSubject)

    private val statusSubject = PublishSubject.create[SchedulerEvent]

    override val observable: Observable[SchedulerEvent] = Observable
        .combineLatest[Boolean, HostsEvent, ServiceContainerGroup,
                       ServiceContainer, Observable[SchedulerEvent]](
            feedbackSubject,
            hostsObservable,
            groupObservable,
            containerObservable,
            makeFunc4(schedule))
        .flatMap(makeFunc1(o => o))
        .mergeWith(statusSubject)
        .takeUntil(mark)
        .lift(new CleanupOperator)

    override def isReady = groupReady && hostsReady

    /** Returns the current state of the scheduler state machine.
      */
    @VisibleForTesting
    def schedulerState = state

    /** Returns a timer observable that emits a notification after the
      * scheduler timeout interval.
      */
    @VisibleForTesting
    protected def timerObservable: Observable[java.lang.Long] = {
        Observable.timer(config.schedulerTimeoutMs, TimeUnit.MILLISECONDS,
                         context.scheduler)
    }

    /** Returns the current system time.
      */
    @VisibleForTesting
    protected def currentTime: Long = Platform.currentTime

    /** Computes the host that should launch the container from the specified
      * list, using a weighted random selection. If there is no available host,
      * the method returns null.
      */
    @Nullable
    private def hostCalculator(hosts: HostsEvent): UUID = {
        if (hosts.isEmpty)
            return null

        val totalWeight = hosts.foldLeft(0L)((seed, host) =>
                                                seed + host._2.status.getWeight)
        val randomWeight = random.nextLong() % totalWeight
        var sumWeight = 0L
        var selectedId: UUID = null

        val hostIterator = hosts.iterator
        while (hostIterator.hasNext && sumWeight <= randomWeight) {
            val host = hostIterator.next()
            selectedId = host._1
            sumWeight += host._2.status.getWeight
        }

        selectedId
    }

    /** Handles updates to this container. The method verifies if this is the
      * first container notification
      */
    private def containerUpdated(container: ServiceContainer): Unit = {
        val groupId = container.getServiceGroupId.asJava
        val portId = container.getPortId.asJava
        log debug s"Container updated with group $groupId at port $portId"

        if ((currentContainer eq null) ||
            currentContainer.getServiceGroupId != container.getServiceGroupId) {
            groupReady = false
            groupSubject onNext context.store
                .observable(classOf[ServiceContainerGroup], groupId)
                .observeOn(context.scheduler)
                .doOnNext(makeAction1(_ => groupReady = true))
        }
        currentContainer = container
    }

    /** Handles changes to the container group policy, by creating a new
      * host selector for the new policy and triggering a new scheduling.
      */
    private def policyUpdated(group: ServiceContainerGroup): Unit = {
        log debug s"Group scheduling policy updated: ${HostSelector.policyOf(group)}"

        // If the change in policy returns a new host selector, emit the
        // selector observable on the hosts subject.
        val selector = selectorProvider.selectorOf(group)
        if (hostSelector ne selector) {
            hostSelector = selector
            hostsReady = false
            hostsSubject onNext hostSelector.observable
                .doOnNext(makeAction1(_ => hostsReady = true))
        }
    }

    /** Performs the scheduling of the current container for the specified set
      * of hosts. The method examines the eligible hosts
      */
    private def schedule(retry: Boolean, hosts: HostsEvent,
                         group: ServiceContainerGroup, container: ServiceContainer)
    : Observable[SchedulerEvent] = {
        if (!isReady) {
            // Intermediary update: still waiting on the group policy or the
            // hosts list. However, if a previous scheduling exists unschedule
            // because any previous scheduling must be invalid.
            state match {
                case Scheduled(id, cont, gr, sub) =>
                    log info s"Cancel scheduling on host $id because the " +
                             "container configuration has changed"
                    sub.unsubscribe()
                    state = Down
                    return Observable.just(UnscheduleEvent(cont, gr, id))
                case Up(id, cont, gr) =>
                    log info s"Delete container on host $id because the " +
                             "container configuration has changed"
                    state = Down
                    return Observable.just(UnscheduleEvent(cont, gr, id))
                case Down =>
                    log debug s"Container configuration not ready"
                    return Observable.empty()
            }
        }

        log debug s"Scheduling from hosts: ${hosts.keySet}"

        // Select all hosts where the container service is running and the hosts
        // have a positive weight.
        val runningHosts = hosts.filter(host => host._2.running &&
                                                host._2.status.getWeight > 0)
        log debug s"Scheduling from running hosts: ${runningHosts.keySet}"

        // Clear the bad hosts set.
        clearBadHosts(runningHosts)

        // Filter the bad hosts.
        val eligibleHosts = runningHosts -- badHosts.keySet
        log debug s"Scheduling from eligible hosts: ${eligibleHosts.keySet}"

        // If a host is currently scheduled on a host, and that host belongs to
        // the eligible set, no rescheduling needed.
        if ((state.hostId ne null) && eligibleHosts.contains(state.hostId)) {
            log debug s"Scheduling completed: keeping current host ${state.hostId}"
            return Observable.empty()
        }

        // Select a host from the eligible set based on the total weight.
        val selectedHostId = hostCalculator(eligibleHosts)

        // Ensure the container status is loaded from the selected host.
        namespaceSubject onNext selectedHostId.asNullableString

        /** Changes the scheduler state to [[Scheduled]] and subscribes to
          * a timer observable that emits a notification after the scheduler
          * timeout interval.
          */
        def scheduleWithTimeout(hostId: UUID): Unit = {
            val subscription = Observable
                .timer(config.schedulerTimeoutMs, TimeUnit.MILLISECONDS,
                       context.scheduler)
                .filter(makeFunc1(_ => scheduleTimeout(hostId)))
                .map[Boolean](makeFunc1(_ => true))
                .subscribe(feedbackSubject)
            state = Scheduled(hostId, container, group, subscription)
        }

        // Take a scheduling action that depends on the current state.
        state match {
            case Down if selectedHostId ne null =>
                log info s"Scheduling on host $selectedHostId timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                scheduleWithTimeout(selectedHostId)
                Observable.just(ScheduleEvent(container, group, selectedHostId))
            case Down =>
                log warn "Cannot schedule container: no hosts available"
                Observable.empty()
            case Scheduled(id, _, _, sub) if selectedHostId ne null =>
                log info s"Cancel scheduling on host $id and reschedule on " +
                         s"host $selectedHostId timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                sub.unsubscribe()
                scheduleWithTimeout(selectedHostId)
                Observable.just(UnscheduleEvent(container, group, id),
                                ScheduleEvent(container, group, selectedHostId))
            case Scheduled(id, _, _, sub) =>
                log warn s"Cancel scheduling on host $id and cannot reschedule " +
                         s"container: no hosts available"
                sub.unsubscribe()
                state = Down
                Observable.just(UnscheduleEvent(container, group, id))
            case Up(id, _, _) if selectedHostId == id =>
                log debug s"Container already scheduled on host $id"
                Observable.empty()
            case Up(id, _, _) if selectedHostId ne null =>
                log info s"Unschedule from host $id and reschedule on " +
                         s"host $selectedHostId timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                scheduleWithTimeout(selectedHostId)
                Observable.just(UnscheduleEvent(container, group, id),
                                ScheduleEvent(container, group, selectedHostId))
            case Up(id, _, _) =>
                log warn s"Unschedule from host $id and cannot reschedule " +
                         s"container: no hosts available"
                state = Down
                Observable.just(UnscheduleEvent(container, group, id))
        }
    }

    /** Handles the expiration of the timeout interval when scheduling a
      * container at a specified host. The method returns `true` if the timeout
      * expiration should re-trigger a rescheduling, `false` otherwise.
      */
    private def scheduleTimeout(hostId: UUID): Boolean = state match {
        case Scheduled(id, _, _, _) if hostId == id =>
            log warn s"Scheduling on host $id timed out after " +
                     s"${config.schedulerTimeoutMs} milliseconds: marking the " +
                     s"host as bad for ${config.schedulerBadHostLifetimeMs} " +
                     "milliseconds and retrying scheduling"
            badHosts += hostId -> BadHost(currentTime, 0)
            true
        case _ => // Ignore because the scheduling state has changed.
            false
    }

    /** Handles the container status reported by the remote agent where the
      * container has been scheduled. The method returns `true` if the new
      * status should re-trigger a rescheduling, ``
      */
    private def containerStatusUpdated(key: StateKey): Boolean = {
        // Parse the container status from the container state key.
        val status = key match {
            case SingleValueKey(_,Some(value),_) =>
                val builder = ContainerStatus.newBuilder()
                try {
                    TextFormat.merge(value, builder)
                    log debug s"Container status changed ${builder.getStatusCode} " +
                              s"host: ${builder.getHostId.asJava} " +
                              s"namespace: ${builder.getNamespaceName} " +
                              s"interface: ${builder.getInterfaceName} " +
                              s"(message: ${builder.getStatusMessage})"
                    builder.build()
                } catch {
                    case NonFatal(e) =>
                        log.warn("Failed to read container status", e)
                        null
                }
            case _ =>
                log debug s"Container status cleared"
                null
        }

        // Match the reported container status with the current state of the
        // scheduling state machine, and take an appropriate action such
        // updating the scheduling state, cancelling the timeout in case of
        // success, or retrying the scheduling in case of failure.
        state match {
            case Down if status eq null => false
            case Down if status.getStatusCode == Code.RUNNING =>
                // The container is running at the specified host, however the
                // state is inconsistent: try to reschedule.
                true
            case Scheduled(id, _, _, _) if status eq null =>
                // Waiting on the host to report the container status.
                false
            case Scheduled(id, container, group, sub)
                if status.getHostId.asJava == id =>
                if (status.getStatusCode == Code.STOPPING ||
                    status.getStatusCode == Code.ERROR) {
                    log warn s"Failed to start container at host $id with " +
                             s"status ${status.getStatusCode}: marking the " +
                             s"host as bad for ${config.schedulerBadHostLifetimeMs} " +
                             "milliseconds and rescheduling"
                    sub.unsubscribe()
                    badHosts += id -> BadHost(currentTime,
                                              config.schedulerTimeoutMs)
                    state = Down
                    statusSubject onNext DownEvent(container, group, status)
                    true
                } else if (status.getStatusCode == Code.RUNNING) {
                    log info s"Container running at host $id"
                    sub.unsubscribe()
                    state = Up(id, container, group)
                    statusSubject onNext UpEvent(container, group, status)
                    false
                } else false
            case Up(id, container, group) if status eq null =>
                log warn s"Container reported down at current host $id: " +
                         s"rescheduling"
                state = Down
                statusSubject onNext DownEvent(container, group, status)
                true
            case Up(id, container, group) if status.getHostId.asJava == id =>
                if (status.getStatusCode == Code.STOPPING ||
                    status.getStatusCode == Code.ERROR) {
                    log info s"Container stopped or failed at host $id with " +
                             s"status ${status.getStatusCode}: marking the " +
                             s"host as bad for ${config.schedulerBadHostLifetimeMs} " +
                             "milliseconds and rescheduling"
                    badHosts += id -> BadHost(currentTime, 0)
                    state = Down
                    statusSubject onNext DownEvent(container, group, status)
                    true
                } else false
            case _ =>
                // Ignore other matches because the state does not correspond to
                // the current scheduled host.
                false
        }
    }

    /** Clears the bad hosts, which includes the hosts whose bad lifetime has
      * expired, and hosts that have been currently added to the hosts set.
      */
    private def clearBadHosts(hosts: HostsEvent): Unit = {
        val addedHosts = hosts.keySet -- currentHosts.keySet
        val expiryTime = currentTime - config.schedulerBadHostLifetimeMs
        badHosts --= addedHosts
        for ((hostId, badHost) <- badHosts.toList
             if badHost.timestamp < expiryTime) {
            badHosts -= hostId
        }
        currentHosts = hosts
    }

    private class CleanupOperator
        extends Operator[SchedulerEvent, SchedulerEvent] {

        override def call(child: Subscriber[_ >: SchedulerEvent])
        : Subscriber[_ >: SchedulerEvent] = {
            val pa = new ProducerArbiter
            val sub = new SerialSubscription
            val parent = new Subscriber[SchedulerEvent] {
                private var done = false

                override def onCompleted(): Unit = {
                    if (done) return
                    done = true
                    unsubscribe()
                    cleanup(child)
                }

                override def onError(e: Throwable): Unit = {
                    if (done) {
                        Exceptions.throwIfFatal(e)
                        return
                    }
                    done = true
                    try {
                        RxJavaPlugins.getInstance().getErrorHandler.handleError(e)
                        unsubscribe()
                        cleanup(child)
                    } catch {
                        case ex: Throwable => child onError ex
                    }
                }

                override def onNext(e: SchedulerEvent): Unit = {
                    if (done) return
                    child onNext e
                }

                override def setProducer(producer: Producer): Unit = {
                    pa.setProducer(producer)
                }
            }
            child add sub
            sub set parent
            child setProducer pa
            parent
        }
    }

    /** Returns an [[OnSubscribe]] handle that emits the cleanup notifications
      * for the state of scheduler at the moment of subscription.
      */
    private def cleanup(subscriber: Subscriber[_ >: SchedulerEvent]): Unit = {
        statusSubscription.unsubscribe()
        state match {
            case Scheduled(id, container, group, sub) =>
                log info s"Cancel scheduling on host $id because the " +
                         "scheduling update stream has completed"
                sub.unsubscribe()
                state = Down
                subscriber onNext UnscheduleEvent(container, group, id)
                subscriber.onCompleted()
            case Up(id, container, group) =>
                log info s"Deleting container on host $id because the " +
                         "scheduling update stream has completed"
                state = Down
                subscriber onNext UnscheduleEvent(container, group, id)
                subscriber.onCompleted()
            case Down =>
                subscriber.onCompleted()
        }
    }

}
