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

import java.util
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import scala.compat.Platform
import scala.util.Random
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.TextFormat

import rx.Observable.{OnSubscribe, Operator}
import rx._
import rx.exceptions.Exceptions
import rx.internal.producers.ProducerArbiter
import rx.plugins.RxJavaPlugins
import rx.subjects.{BehaviorSubject, PublishSubject}
import rx.subscriptions.{SerialSubscription, Subscriptions}

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey}
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.Topology.{Port, ServiceContainer, ServiceContainerGroup, ServiceContainerPolicy}
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.containers.schedulers.ContainerScheduler._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.{ContainersConfig, ContainersLog}
import org.midonet.containers.{Context, ObjectTracker}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1, makeFunc5}
import org.midonet.util.logging.Logging

object ContainerScheduler {

    /** Indicates the scheduling state for the current container, representing
      * the possible states in the scheduling state machine.
      */
    trait State {
        def hostId: UUID
    }
    /** The container is not scheduled at a host. The state may include a
      * retry subscription
      */
    object DownState extends DownState(Subscriptions.unsubscribed(),
                                       attempts = 0)
    case class DownState(retrySubscription: Subscription,
                         attempts: Int) extends State {
        override val hostId = null
    }
    /** The container has been scheduled on a host, but that host has not yet
      * reported the container as running. The state includes a timeout
      * subscription for the observable that will emit a notification when the
      * timeout expires.
      */
    case class ScheduledState(hostId: UUID, container: ServiceContainer,
                              timeoutSubscription: Subscription) extends State
    /** The container has been rescheduled from a previous host to a new host,
      * but that host has not yet reported the container as running. The state
      * includes a timeout subscription for the observable that will emit a
      * notification when the timeout expires.
      */
    case class RescheduledState(oldHostId: UUID, hostId: UUID,
                                container: ServiceContainer,
                                timeoutSubscription: Subscription) extends State
    /** The container has been scheduled on a host and has been reported as
      * running.
      */
    case class UpState(hostId: UUID, container: ServiceContainer) extends State

    /** The feedback notification stream is used to process changes to the
      * container status as [[StatusFeedback]], and notifications from the
      * scheduler to itself, such as scheduling timeouts [[TimeoutFeedback]]
      * and scheduling retries [[RetryFeedback]].
      */
    trait Feedback
    case object StatusFeedback extends Feedback
    case object TimeoutFeedback extends Feedback
    case object RetryFeedback extends Feedback

    private case class ContainerSelector(portId: UUID, groupId: UUID)

    private case class BadHost(expires: Long)

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
  * - Scheduled: the container has been scheduled on a host
  * - Up: the container has been reported RUNNING by the host where it has been
  *       scheduled
  * - Down: the container has been reported STOPPED or ERROR by the host where
  *         it has been scheduled
  * - Unscheduled: the container has been unscheduled from a host
  *
  * After a host selection is made, the class schedules the container on the
  * host by emitting a `Scheduled` notification, which in turn should call
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
    extends ObjectTracker[SchedulerEvent] with Logging {

    override def logSource = ContainersLog
    override def logMark = s"container:$containerId"

    private val random = new Random()

    private val subscribed = new AtomicBoolean(false)
    private var state: State = DownState

    private var currentContainer: ServiceContainer = null
    private var currentHosts: HostsEvent = Map.empty
    private var hostSelector: HostSelector = null

    private val badHosts = new mutable.HashMap[UUID, BadHost]

    private var groupReady = false
    private var hostsReady = false
    private var portReady = false

    // A subject that emits notifications when retrying a scheduling because a
    // previous attempt has failed either because of a timeout or because the
    // container status did not report a running container. This subject
    // provides the feedback loop necessary to adjust the scheduling based on
    // the reported container status.
    private val feedbackSubject =
        BehaviorSubject.create[Feedback](StatusFeedback)
    private val feedbackObserver = new Observer[Feedback] {
        override def onNext(feedback: Feedback): Unit = {
            log debug s"Feedback notification $feedback"
            feedbackSubject onNext feedback
        }
        override def onError(e: Throwable): Unit = {
            log.warn(s"Feedback error", e)
            feedbackSubject onError e
        }
        override def onCompleted(): Unit = { }
    }
    private val feedbackObservable = feedbackSubject
        .observeOn(context.scheduler)

    private val containerObservable = context.store
        .observable(classOf[ServiceContainer], containerId)
        .distinctUntilChanged[ContainerSelector](makeFunc1(c =>
            ContainerSelector(c.getPortId.asJava, c.getServiceGroupId.asJava)))
        .observeOn(context.scheduler)
        .doOnNext(makeAction1(containerUpdated))
        .doOnCompleted(makeAction0(containerDeleted()))

    private val groupSubject = PublishSubject.create[Observable[ServiceContainerGroup]]
    private val groupObservable = Observable
        .switchOnNext(groupSubject)
        .observeOn(context.scheduler)
        .doOnNext(makeAction1(policyUpdated))

    private val hostsSubject = PublishSubject.create[Observable[HostsEvent]]
    private val hostsObservable = Observable
        .switchOnNext(hostsSubject)
        .distinctUntilChanged()
        .observeOn(context.scheduler)

    private val portSubject = PublishSubject.create[Observable[Option[Port]]]
    private val portObservable = Observable
        .merge(portSubject)
        .observeOn(context.scheduler)

    private var namespaceId: UUID = null
    private val namespaceSubject = PublishSubject.create[String]
    private val containerDeletedSubject = PublishSubject.create[SchedulerEvent]

    @volatile private var statusSubscription: Subscription = _

    private val statusSubject = PublishSubject.create[SchedulerEvent]

    private val schedulerObservable = Observable
        .combineLatest[Feedback, Option[Port], HostsEvent, ServiceContainerGroup,
                       ServiceContainer, Observable[SchedulerEvent]](
            feedbackObservable,
            portObservable,
            hostsObservable,
            groupObservable,
            containerObservable,
            makeFunc5(schedule))
        .flatMap(makeFunc1(o => o))
        .mergeWith(statusSubject)
        .doOnNext(makeAction1(handleSchedulerEvent))
        .takeUntil(containerDeletedSubject)
        .lift[SchedulerEvent](new CleanupOperator)
        .takeUntil(mark)

    /** An observable that emits notifications for this scheduler. The scheduler
      * observable allows only one subscribe guaranteeing a consistent stream
      * of scheduling updates for the current container.
      */
    override val observable = Observable.create(new OnSubscribe[SchedulerEvent] {
        override def call(child: Subscriber[_ >: SchedulerEvent]): Unit = {
            if (subscribed.compareAndSet(false, true)) {
                statusSubscription = context.stateStore
                    .keyObservable(namespaceSubject, classOf[ServiceContainer],
                                   containerId, StatusKey)
                    .onErrorResumeNext(Observable.just(null))
                    .observeOn(context.scheduler)
                    .filter(makeFunc1(containerStatusUpdated))
                    .map[Feedback](makeFunc1(_ => StatusFeedback))
                    .subscribe(feedbackObserver)
                schedulerObservable subscribe child
                child add statusSubscription
            } else {
                throw new IllegalStateException("The scheduler accepts only " +
                                                "one subscription")
            }
        }
    })

    override def isReady = groupReady && hostsReady

    /** Returns the current state of the scheduler state machine.
      */
    @VisibleForTesting
    def schedulerState = state

    /** Returns a timer observable that emits a notification after the scheduler
      * timeout interval.
      */
    @VisibleForTesting
    protected def timeoutObservable: Observable[java.lang.Long] = {
        Observable.timer(config.schedulerTimeoutMs, TimeUnit.MILLISECONDS,
                         context.scheduler)
    }

    /** Returns a timer observable that emits a notification after the scheduler
      * retry interval.
      */
    @VisibleForTesting
    protected def retryObservable: Observable[java.lang.Long] = {
        Observable.timer(config.schedulerRetryMs, TimeUnit.MILLISECONDS,
                         context.scheduler)
    }

    /** Returns the current system time.
      */
    @VisibleForTesting
    protected def currentTime: Long = Platform.currentTime

    /** Selects the host that should launch the container from the specified
      * list, using the specified selection policy. If there is no available
      * host, the method returns null.
      */
    private def selectHost(hosts: HostsEvent, policy: ServiceContainerPolicy)
    : Option[UUID] = {
        if (hosts.isEmpty)
            return None

        policy match {
            case ServiceContainerPolicy.WEIGHTED_SCHEDULER =>
                selectHostWeightedPolicy(hosts)
            case ServiceContainerPolicy.LEAST_SCHEDULER =>
                selectHostLeastPolicy(hosts)
            case _ =>
                log warn s"Unrecognized scheduling policy $policy"
                None
        }
    }

    /** Selects the host from the list using the weighted policy. This is a
      * random selection, where the probability of selecting a certain host
      * is proportional to that host's weight.
      */
    private def selectHostWeightedPolicy(hosts: HostsEvent): Option[UUID] = {
        val totalWeight = hosts.foldLeft(0L)((seed, host) =>
                                                 seed + host._2.status.getWeight)
        val randomWeight = Math.abs(random.nextLong()) % totalWeight
        var sumWeight = 0L
        var selectedId: UUID = null

        val hostIterator = hosts.iterator
        while (hostIterator.hasNext && sumWeight <= randomWeight) {
            val host = hostIterator.next()
            selectedId = host._1
            sumWeight += host._2.status.getWeight
        }

        Option(selectedId)
    }

    /** Selects the host from the list using the least policy. This selects the
      * host that currently runs the minimum number of containers as reported
      * by the host and read from NSDB.
      */
    private def selectHostLeastPolicy(hosts: HostsEvent): Option[UUID] = {
        Some(hosts.minBy(_._2.status.getCount)._1)
    }

    /** Determines whether a host is running to start a container: the host
      * must be running the container service, and it must report a positive
      * container weight.
      */
    @inline
    private def isHostRunning(entry: (UUID, HostEvent)): Boolean = {
        entry._2.running && entry._2.status.getWeight > 0
    }

    /** Determines whether a host is available to start a container: the host
      * must report a non-zero quota.
      */
    @inline
    private def isHostAvailable(entry: (UUID, HostEvent)): Boolean = {
        entry._2.status.getQuota != 0
    }

    /** Handles updates to this container. The method verifies if this is the
      * first container notification
      */
    private def containerUpdated(container: ServiceContainer): Unit = {
        val groupId = container.getServiceGroupId.asJava
        val portId = container.getPortId.asJava
        log debug s"Container updated with group $groupId at port $portId"

        // If the container group has changed, emit the new container group
        // observable, and set the group as not ready.
        if ((currentContainer eq null) ||
            currentContainer.getServiceGroupId != container.getServiceGroupId) {
            groupReady = false
            groupSubject onNext context.store
                .observable(classOf[ServiceContainerGroup], groupId)
                .observeOn(context.scheduler)
                .doOnNext(makeAction1(_ => groupReady = true))
        }

        // If this is the first container emit the current port, if any. This
        // ensures that we use a previous scheduling.
        if (currentContainer eq null) {
            if (container.hasPortId) {
                portSubject onNext context.store
                    .observable(classOf[Port], portId)
                    .observeOn(context.scheduler)
                    .map[Option[Port]](makeFunc1(Option(_)))
                    .doOnNext(makeAction1(_ => portReady = true))
                    .onErrorResumeNext(Observable.just[Option[Port]](None))
            } else {
                portSubject onNext Observable.just[Option[Port]](None)
            }
        }

        currentContainer = container
    }

    /** Handles the deletion of the the container.
      */
    private def containerDeleted(): Unit = {
        log debug "Container deleted"
        containerDeletedSubject.onCompleted()
    }

    /** Handles changes to the container group policy, by creating a new
      * host selector for the new policy and triggering a new scheduling.
      */
    private def policyUpdated(group: ServiceContainerGroup): Unit = {
        log debug "Group scheduling policy updated: " +
                  s"${HostSelector.policyOf(group)} with ${group.getPolicy}"

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

    /** Changes the scheduler state to [[DownState]] and schedules a retry
      * operation if the maximum number of retry attempts has not yet been
      * reached.
      */
    def downWithRetry(attempts: Int): Unit = {
        val subscription = retryObservable
            .filter(makeFunc1(_ => scheduleRetry(attempts)))
            .map[Feedback](makeFunc1(_ => RetryFeedback))
            .subscribe(feedbackObserver)
        state = DownState(subscription, attempts)
    }

    /** Performs the scheduling of the current container for the specified set
      * of hosts. The method examines the eligible hosts reported by the
      * current host selector. If the current host is found in the eligible set
      * then the host is not changed. Otherwise, the method switches the
      * container status observable to the new host, and determines a scheduling
      * action base on current scheduling state.
      *
      * Upon startup, the method verifies if the container is already scheduled,
      * in which case it will try to use the same scheduling given that the host
      * is still eligible and the container status reports the container as
      * running.
      */
    private def schedule(feedback: Feedback, port: Option[Port], hosts: HostsEvent,
                         group: ServiceContainerGroup, container: ServiceContainer)
    : Observable[SchedulerEvent] = {

        val events = new util.ArrayList[SchedulerEvent](6)
        var oldHostId: Option[UUID] = None

        /** Changes the scheduler state to [[ScheduledState]] or
          * [[RescheduledState]] and subscribes to a timer observable that emits
          * a notification after the scheduler timeout interval.
          */
        def scheduleWithTimeout(hostId: UUID, oldHostId: Option[UUID] = None)
        : Unit = {
            val subscription = timeoutObservable
                .filter(makeFunc1(_ => scheduleTimeout(hostId)))
                .map[Feedback](makeFunc1(_ => TimeoutFeedback))
                .subscribe(feedbackObserver)
            oldHostId match {
                case None =>
                    state = ScheduledState(hostId, container, subscription)
                case Some(oldId) =>
                    state = RescheduledState(oldId, hostId, container,
                                             subscription)
            }
        }

        /** Handles a change of the container scheduling by a third party, when
          * receiving a notification that the port binding has been set to a
          * different host.
          */
        def schedulingSet(hostId: UUID): Unit = {
            state match {
                case DownState(sub, attempts)
                    if feedback == RetryFeedback &&
                       attempts == config.schedulerMaxRetries =>
                    log warn s"Scheduled container at host $hostId failed to " +
                             "start after a retry: marking the host as bad " +
                             s"for ${config.schedulerBadHostLifetimeMs} " +
                             "milliseconds and retrying scheduling"
                    sub.unsubscribe()
                    badHosts += hostId -> BadHost(currentTime +
                                                  config.schedulerBadHostLifetimeMs)
                    events add Unschedule(container, hostId)
                    state = DownState
                case DownState(sub, _) =>
                    log info s"Container already scheduled at host $hostId: " +
                             "waiting for status confirmation with timeout in " +
                             s"${config.schedulerTimeoutMs} milliseconds"
                    sub.unsubscribe()
                    scheduleWithTimeout(hostId)
                case ScheduledState(id, _, sub) if hostId != id =>
                    log info "Scheduled container has been manually " +
                             s"rescheduled from host $id to $hostId: waiting " +
                             "for status confirmation with timeout in " +
                             s"${config.schedulerTimeoutMs} milliseconds"
                    sub.unsubscribe()
                    events add Notify(container, hostId)
                    scheduleWithTimeout(hostId)
                case RescheduledState(_, id, _, sub) if hostId != id =>
                    log info "Scheduled container has been manually " +
                             s"rescheduled from host $id to $hostId: waiting " +
                             "for status confirmation with timeout in " +
                             s"${config.schedulerTimeoutMs} milliseconds"
                    sub.unsubscribe()
                    events add Notify(container, hostId)
                    scheduleWithTimeout(hostId)
                case UpState(id, _) if hostId != id =>
                    log info "Running container has been manually " +
                             s"rescheduled from host $id to $hostId: waiting " +
                             "for status confirmation with timeout in " +
                             s"${config.schedulerTimeoutMs} milliseconds"
                    events add Notify(container, hostId)
                    scheduleWithTimeout(hostId)
                case _ => // Normal scheduling.
            }
        }

        /** Handles a change of the container scheduling by a third party, when
          * receiving a notification that the port binding has been cleared.
          */
        def schedulingCleared(): Unit = {
            state match {
                case ScheduledState(id, _, sub) if feedback == TimeoutFeedback =>
                    log info s"Container scheduling at host $id has timed out"
                    sub.unsubscribe()
                    state = DownState
                    oldHostId = Some(id)
                case ScheduledState(id, _, sub) =>
                    log info "Scheduled container has been unscheduled " +
                             s"manually from host $id: rescheduling"
                    sub.unsubscribe()
                    state = DownState
                    oldHostId = Some(id)
                case RescheduledState(_, id, _, sub) if feedback == TimeoutFeedback =>
                    log info s"Container scheduling at host $id has timed out"
                    sub.unsubscribe()
                    state = DownState
                    oldHostId = Some(id)
                case RescheduledState(oldId, newId, _, sub) =>
                    log debug "Container has been unscheduled while " +
                              s"rescheduling container from host $oldId to " +
                              s"$newId"
                case UpState(id, _) =>
                    log info "Running container has been unscheduled " +
                             s"manually from host $id: rescheduling"
                    state = DownState
                    oldHostId = Some(id)
                case DownState(_, _) => // Normal scheduling.
            }
        }

        if (!isReady) {
            // Intermediary update: still waiting on the group policy or the
            // hosts list. However, if a previous scheduling exists unschedule
            // because any previous scheduling must be invalid.
            state match {
                case ScheduledState(id, cont, sub) =>
                    log info s"Cancel scheduling at host $id because the " +
                             "container configuration has changed"
                    sub.unsubscribe()
                    state = DownState
                    return Observable.just(Unschedule(cont, id))
                case UpState(id, cont) =>
                    log info s"Delete container at host $id because the " +
                             "container configuration has changed"
                    state = DownState
                    return Observable.just(Down(cont, null),
                                           Unschedule(cont, id))
                case DownState(_, _) =>
                    log debug s"Container configuration not ready"
                    return Observable.empty()
            }
        }

        if (portReady && port.nonEmpty) {
            // Verify whether the container scheduling has been changed (set to
            // a different host or cleared) by a third party. If that is the
            // case compare the change with the current scheduling state where
            // certain cases can be ignored (e.g. the binding was changed as a
            // result of a previous decision made by the scheduler) and in other
            // cases the current scheduling state is abandoned, and the
            // scheduler should try to accommodate the external request.
            if (port.get.hasHostId) {
                schedulingSet(port.get.getHostId.asJava)
            } else {
                schedulingCleared()
            }
        }

        log debug s"Scheduling from hosts: ${hosts.keySet}"

        // Select all hosts that are running.
        val runningHosts = hosts.filter(isHostRunning)
        log debug s"Scheduling from running hosts: ${runningHosts.keySet}"

        // Clear the bad hosts set.
        checkBadHosts(runningHosts)

        // Filter the bad hosts.
        val eligibleHosts = runningHosts -- badHosts.keySet
        log debug s"Scheduling from eligible hosts: ${eligibleHosts.keySet}"

        val selectedHostId =
            if ((state.hostId ne null) && eligibleHosts.contains(state.hostId)) {
                // If the container is currently scheduled at a host, and that
                // host belongs to the eligible set, no rescheduling needed.
                state.hostId
            } else {
                val availableHosts = eligibleHosts.filter(isHostAvailable)

                log debug "Scheduling from available hosts: " +
                          s"${availableHosts.keySet} using ${group.getPolicy} " +
                          "policy"

                // Select a host from the available set based on the current
                // selection policy (currently only weighted-random).
                selectHost(availableHosts, group.getPolicy).orNull
            }

        if ((selectedHostId ne null) || (namespaceId ne null)){
            namespaceId = selectedHostId
            namespaceSubject onNext selectedHostId.asNullableString
        }

        // Take a scheduling action that depends on the current state.
        state match {
            case DownState(sub, _) if selectedHostId ne null =>
                log info s"Scheduling at host $selectedHostId timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                sub.unsubscribe()
                scheduleWithTimeout(selectedHostId, oldHostId)
                events add Schedule(container, selectedHostId)
            case DownState(sub, attempts) if attempts < config.schedulerMaxRetries =>
                log warn "Cannot schedule container: no hosts available " +
                         s"retrying in ${config.schedulerRetryMs} milliseconds"
                sub.unsubscribe()
                if (feedback == RetryFeedback) {
                    downWithRetry(attempts + 1)
                } else {
                    downWithRetry(attempts = 0)
                }
            case DownState(sub, _) =>
                log warn "Cannot schedule container: no hosts available"
                sub.unsubscribe()
                state = DownState
            case ScheduledState(id, _, sub) if selectedHostId == id =>
                log debug s"Container already scheduled at host $id: " +
                          "refreshing container state"
            case ScheduledState(id, _, sub) if selectedHostId ne null =>
                log info s"Cancel scheduling at host $id and reschedule on " +
                         s"host $selectedHostId timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                sub.unsubscribe()
                scheduleWithTimeout(selectedHostId, Some(id))
                events add Unschedule(container, id)
                events add Schedule(container, selectedHostId)
            case ScheduledState(id, _, sub) =>
                log warn s"Cancel scheduling at host $id and cannot reschedule " +
                         s"container: no hosts available"
                sub.unsubscribe()
                state = DownState
                events add Unschedule(container, id)
            case RescheduledState(_, id, _, sub) if selectedHostId == id =>
                log debug s"Container already scheduled at host $id: " +
                          "refreshing container state"
            case RescheduledState(_, id, _, sub) if selectedHostId ne null =>
                log info s"Cancel scheduling at host $id and reschedule on " +
                         s"host $selectedHostId timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                sub.unsubscribe()
                scheduleWithTimeout(selectedHostId, Some(id))
                events add Unschedule(container, id)
                events add Schedule(container, selectedHostId)
            case RescheduledState(_, id, _, sub) =>
                log warn s"Cancel scheduling at host $id and cannot reschedule " +
                         s"container: no hosts available"
                sub.unsubscribe()
                state = DownState
                events add Unschedule(container, id)
            case UpState(id, _) if selectedHostId == id =>
                log debug s"Container already scheduled at host $id"
            case UpState(id, _) if selectedHostId ne null =>
                log info s"Unschedule from host $id and reschedule at " +
                         s"host $selectedHostId timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                scheduleWithTimeout(selectedHostId, Some(id))
                events add Down(container, null)
                events add Unschedule(container, id)
                events add Schedule(container, selectedHostId)
            case UpState(id, _) =>
                log warn s"Unschedule from host $id and cannot reschedule " +
                         s"container: no hosts available"
                state = DownState
                events add Down(container, null)
                events add Unschedule(container, id)
        }
        Observable.from(events)
    }

    /** Handles the expiration of the timeout interval when scheduling a
      * container at a specified host. The method returns `true` if the timeout
      * expiration should re-trigger a rescheduling, `false` otherwise.
      */
    private def scheduleTimeout(hostId: UUID): Boolean = state match {
        case ScheduledState(id, _, _) if hostId == id =>
            log warn s"Scheduling at host $id timed out after " +
                     s"${config.schedulerTimeoutMs} milliseconds: marking the " +
                     s"host as bad for ${config.schedulerBadHostLifetimeMs} " +
                     "milliseconds and retrying scheduling"
            badHosts += hostId -> BadHost(currentTime +
                                          config.schedulerBadHostLifetimeMs)
            true
        case RescheduledState(oldId, newId, _, _) if hostId == newId =>
            log warn s"Rescheduling from host $oldId to $newId timed out after " +
                     s"${config.schedulerTimeoutMs} milliseconds: marking the " +
                     s"host as bad for ${config.schedulerBadHostLifetimeMs} " +
                     "milliseconds and retrying scheduling"
            badHosts += hostId -> BadHost(currentTime +
                                          config.schedulerBadHostLifetimeMs)
            true
        case _ => // Ignore because the scheduling state has changed.
            false
    }

    /** Handles the expiration of the retry interval.
      */
    private def scheduleRetry(attempts: Int): Boolean = state match {
        case DownState(_, _) =>
            log info s"Retrying container scheduling attempt ${attempts + 1} " +
                     s"of ${config.schedulerMaxRetries}"
            true
        case _ => // Ignore because the scheduling state has changed.
            false
    }

    /** Handles the container status reported by the remote agent where the
      * container has been scheduled. The method returns `true` if the new
      * status should re-trigger a rescheduling, `false` otherwise.
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
                              s"interface: ${builder.getInterfaceName}"
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

        def handleScheduledStatus(id: UUID, container: ServiceContainer,
                                  sub: Subscription): Boolean = {
            if (status.getStatusCode == Code.ERROR) {
                log warn s"Failed to start container at host $id with " +
                         s"status ${status.getStatusCode}: marking the " +
                         s"host as bad for ${config.schedulerRetryMs} " +
                         "milliseconds and rescheduling"
                sub.unsubscribe()
                badHosts += id -> BadHost(currentTime + config.schedulerRetryMs)
                state = DownState
                statusSubject onNext Down(container, status)
                statusSubject onNext Unschedule(container, id)
                true
            } else if (status.getStatusCode == Code.STOPPING) {
                // Note: The scheduler may reach this state when rescheduling
                // a container at the same host (since the container restarts).
                // To avoid entering a loop, the scheduler takes no action and
                // instead schedules a single retry for the same host. If
                // the retry interval expires and the container has not reached
                // the running state, then we reschedule the container.
                log info s"Unexpected status ${status.getStatusCode} when " +
                         "scheduling container: retrying host in " +
                         s"${config.schedulerRetryMs} milliseconds"
                downWithRetry(attempts = config.schedulerMaxRetries)
                false
            } else if (status.getStatusCode == Code.RUNNING) {
                log info s"Container running at host $id"
                sub.unsubscribe()
                state = UpState(id, container)
                statusSubject onNext Up(container, status)
                false
            } else false
        }

        // Match the reported container status with the current state of the
        // scheduling state machine, and take an appropriate action such
        // updating the scheduling state, cancelling the timeout in case of
        // success, or retrying the scheduling in case of failure.
        state match {
            case DownState(_, _) if status eq null => false
            case DownState(sub, _) if status.getStatusCode == Code.RUNNING =>
                // The container is running at the specified host, however the
                // state is inconsistent: try to reschedule.
                sub.unsubscribe()
                state = DownState
                true
            case ScheduledState(_, _, _) if status eq null =>
                // Waiting on the host to report the container status.
                false
            case ScheduledState(id, container, sub)
                if status.getHostId.asJava == id =>
                handleScheduledStatus(id, container, sub)
            case RescheduledState(_, _, _, _) if status eq null =>
                // Waiting on the host to report the container status.
                false
            case RescheduledState(_, id, container, sub)
                if status.getHostId.asJava == id =>
                handleScheduledStatus(id, container, sub)
            case UpState(id, container) if status eq null =>
                log warn s"Container reported down at current host $id: " +
                         s"rescheduling"
                state = DownState
                statusSubject onNext Down(container, status)
                true
            case UpState(id, container) if status.getHostId.asJava == id =>
                if (status.getStatusCode == Code.STOPPING ||
                    status.getStatusCode == Code.ERROR) {
                    log info s"Container stopped or failed at host $id with " +
                             s"status ${status.getStatusCode}: marking the " +
                             s"host as bad for ${config.schedulerRetryMs} " +
                             "milliseconds and rescheduling"
                    badHosts += id -> BadHost(currentTime +
                                              config.schedulerRetryMs)
                    state = DownState
                    statusSubject onNext Down(container, status)
                    true
                } else false
            case _ =>
                // Ignore other matches because the state does not correspond to
                // the current scheduled host.
                false
        }
    }

    /** Checks the bad hosts to clear the hosts whose bad lifetime has expired,
      * and hosts that have been currently added to the hosts set.
      */
    private def checkBadHosts(hosts: HostsEvent): Unit = {
        val addedHosts = hosts.keySet -- currentHosts.keySet
        val expiryTime = currentTime
        badHosts --= addedHosts
        for ((hostId, badHost) <- badHosts.toList
             if badHost.expires < expiryTime) {
            badHosts -= hostId
        }
        currentHosts = hosts
    }

    /** Invalidates the current port for any event emitted by the scheduler that
      * changes the scheduling of the container: this ensures that the
      * scheduling algorithm does not rely on stale port binding data after
      * a scheduling decision was made, until receiving a new port notification.
      */
    private def handleSchedulerEvent(event: SchedulerEvent): Unit = {
        event match {
            case Schedule(_,_) => portReady = false
            case Unschedule(_,_) => portReady = false
            case _ =>
        }
    }

    /** An [[Operator]] that ensures the scheduler emits the cleanup
      * notifications when the observable for the container completes or emits
      * an error. The changes applied by this operator do not take effect
      * when calling the `complete()` method.
      */
    private class CleanupOperator
        extends Operator[SchedulerEvent, SchedulerEvent] {

        override def call(child: Subscriber[_ >: SchedulerEvent])
        : Subscriber[_ >: SchedulerEvent] = {
            val pa = new ProducerArbiter
            val sub = new SerialSubscription
            val parent = new Subscriber[SchedulerEvent] {
                @volatile private var done = false

                override def onCompleted(): Unit = {
                    if (done) return
                    done = true
                    unsubscribe()
                    cleanup(child)
                }

                override def onError(e: Throwable): Unit = {
                    log.error(s"Unhandled exception in container scheduler", e)
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

    /** Emits the cleanup notifications to the specified subscriber.
      */
    private def cleanup(subscriber: Subscriber[_ >: SchedulerEvent]): Unit = {
        statusSubscription.unsubscribe()
        state match {
            case ScheduledState(id, container, sub) =>
                log info s"Cancel scheduling at host $id because the " +
                         "scheduling update stream has completed"
                sub.unsubscribe()
                state = DownState
                subscriber onNext Unschedule(container, id)
                subscriber.onCompleted()
            case RescheduledState(oldId, id, container, sub) =>
                log info s"Cancel rescheduling from host $oldId to $id " +
                         "because the scheduling update stream has completed"
                sub.unsubscribe()
                state = DownState
                subscriber onNext Unschedule(container, id)
                subscriber.onCompleted()
            case UpState(id, container) =>
                log info s"Deleting container at host $id because the " +
                         "scheduling update stream has completed"
                state = DownState
                subscriber onNext Unschedule(container, id)
                subscriber.onCompleted()
            case DownState(_, _) =>
                subscriber.onCompleted()
        }
    }

}
