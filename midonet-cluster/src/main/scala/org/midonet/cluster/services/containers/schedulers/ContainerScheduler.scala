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
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import scala.compat.Platform
import scala.util.Random
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.TextFormat
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Observable.{OnSubscribe, Operator}
import rx.exceptions.Exceptions
import rx.internal.producers.ProducerArbiter
import rx.plugins.RxJavaPlugins
import rx.subjects.{BehaviorSubject, PublishSubject}
import rx.subscriptions.SerialSubscription
import rx.{Observable, Producer, Subscriber, Subscription}

import org.midonet.cluster.data.storage.{SingleValueKey, StateKey}
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.Topology.{Port, ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.containers.schedulers.ContainerScheduler._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.{ContainersConfig, containerLog}
import org.midonet.containers.{Context, ObjectTracker}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1, makeFunc5}

object ContainerScheduler {

    /** Indicates the scheduling state for the current container, representing
      * the possible states in the scheduling state machine.
      */
    trait State {
        def hostId: UUID
    }
    /** The container is not scheduled on a host.
      */
    case object DownState extends State {
        override val hostId = null
    }
    /** The container has been scheduled on a host, but that host has not yet
      * reported the container as running. The state includes the time when the
      * container was scheduled, and a timeout subscription for the observable
      * that will emit a notification when the timeout expires.
      */
    case class ScheduledState(hostId: UUID, container: ServiceContainer,
                              timeoutSubscription: Subscription) extends State
    /** The container has been scheduled on a host and has been reported as
      * running.
      */
    case class UpState(hostId: UUID, container: ServiceContainer) extends State

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
    extends ObjectTracker[SchedulerEvent] {

    private val log = Logger(LoggerFactory.getLogger(containerLog(containerId)))
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
    private val feedbackSubject = BehaviorSubject.create[Boolean](false)
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

    private val namespaceSubject = PublishSubject.create[String]
    private val containerDeletedSubject = PublishSubject.create[SchedulerEvent]

    @volatile private var statusSubscription: Subscription = _

    private val statusSubject = PublishSubject.create[SchedulerEvent]

    private val schedulerObservable = Observable
        .combineLatest[Boolean, Option[Port], HostsEvent, ServiceContainerGroup,
                       ServiceContainer, Observable[SchedulerEvent]](
            feedbackObservable,
            portObservable,
            hostsObservable,
            groupObservable,
            containerObservable,
            makeFunc5(schedule))
        .flatMap(makeFunc1(o => o))
        .mergeWith(statusSubject)
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
                    .map[Boolean](makeFunc1(_ => true))
                    .subscribe(feedbackSubject)
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

    /** Selects the host that should launch the container from the specified
      * list, using a weighted random selection. If there is no available host,
      * the method returns null.
      */
    private def selectHost(hosts: HostsEvent): Option[UUID] = {
        if (hosts.isEmpty)
            return None

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
                    .onErrorResumeNext(Observable.just[Option[Port]](None))
                    .take(1)
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
    private def schedule(retry: Boolean, port: Option[Port], hosts: HostsEvent,
                         group: ServiceContainerGroup, container: ServiceContainer)
    : Observable[SchedulerEvent] = {

        /** Changes the scheduler state to [[ScheduledState]] and subscribes to
          * a timer observable that emits a notification after the scheduler
          * timeout interval.
          */
        def scheduleWithTimeout(hostId: UUID): Unit = {
            val subscription = timerObservable
                .filter(makeFunc1(_ => scheduleTimeout(hostId)))
                .map[Boolean](makeFunc1(_ => true))
                .subscribe(feedbackSubject)
            state = ScheduledState(hostId, container, subscription)
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
                case DownState =>
                    log debug s"Container configuration not ready"
                    return Observable.empty()
            }
        }

        if (!portReady) {
            // If this is the first scheduling, check the container has not
            // been scheduled previously by another scheduler instance. If
            // the container is already scheduled, assume the previous
            // scheduling is correct and set the state to scheduled with
            // timeout. If the current host is not eligible, it will be changed
            // below.
            portReady = true
            if (port.nonEmpty && port.get.hasHostId) {
                val hostId = port.get.getHostId.asJava
                log info s"Container is already scheduled at host $hostId: " +
                         "waiting for status confirmation with timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                scheduleWithTimeout(hostId)
            }
        }

        log debug s"Scheduling from hosts: ${hosts.keySet}"

        // Select all hosts where the container service is running and the hosts
        // have a positive weight.
        val runningHosts = hosts.filter(host => host._2.running &&
                                                host._2.status.getWeight > 0)
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
                // Select a host from the eligible set based at the total weight.
                selectHost(eligibleHosts).orNull
            }

        namespaceSubject onNext selectedHostId.asNullableString

        // Take a scheduling action that depends on the current state.
        state match {
            case DownState if selectedHostId ne null =>
                log info s"Scheduling at host $selectedHostId timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                scheduleWithTimeout(selectedHostId)
                Observable.just(Schedule(container, selectedHostId))
            case DownState =>
                log warn "Cannot schedule container: no hosts available"
                Observable.empty()
            case ScheduledState(id, _, sub) if selectedHostId == id =>
                log debug s"Container already scheduled at host $id: " +
                          "refreshing container state"
                Observable.empty()
            case ScheduledState(id, _, sub) if selectedHostId ne null =>
                log info s"Cancel scheduling at host $id and reschedule on " +
                         s"host $selectedHostId timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                sub.unsubscribe()
                scheduleWithTimeout(selectedHostId)
                Observable.just(Unschedule(container, id),
                                Schedule(container, selectedHostId))
            case ScheduledState(id, _, sub) =>
                log warn s"Cancel scheduling at host $id and cannot reschedule " +
                         s"container: no hosts available"
                sub.unsubscribe()
                state = DownState
                Observable.just(Unschedule(container, id))
            case UpState(id, _) if selectedHostId == id =>
                log debug s"Container already scheduled at host $id"
                Observable.empty()
            case UpState(id, _) if selectedHostId ne null =>
                log info s"Unschedule from host $id and reschedule at " +
                         s"host $selectedHostId timeout in " +
                         s"${config.schedulerTimeoutMs} milliseconds"
                scheduleWithTimeout(selectedHostId)
                Observable.just(Down(container, null),
                                Unschedule(container, id),
                                Schedule(container, selectedHostId))
            case UpState(id, _) =>
                log warn s"Unschedule from host $id and cannot reschedule " +
                         s"container: no hosts available"
                state = DownState
                Observable.just(Down(container, null),
                                Unschedule(container, id))
        }
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
            badHosts += hostId -> BadHost(currentTime, 0)
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

        // Match the reported container status with the current state of the
        // scheduling state machine, and take an appropriate action such
        // updating the scheduling state, cancelling the timeout in case of
        // success, or retrying the scheduling in case of failure.
        state match {
            case DownState if status eq null => false
            case DownState if status.getStatusCode == Code.RUNNING =>
                // The container is running at the specified host, however the
                // state is inconsistent: try to reschedule.
                true
            case ScheduledState(id, _, _) if status eq null =>
                // Waiting on the host to report the container status.
                false
            case ScheduledState(id, container, sub)
                if status.getHostId.asJava == id =>
                if (status.getStatusCode == Code.STOPPING ||
                    status.getStatusCode == Code.ERROR) {
                    log warn s"Failed to start container at host $id with " +
                             s"status ${status.getStatusCode}: marking the " +
                             s"host as bad for ${config.schedulerBadHostLifetimeMs} " +
                             "milliseconds and rescheduling"
                    sub.unsubscribe()
                    badHosts += id -> BadHost(currentTime, 0)
                    state = DownState
                    statusSubject onNext Down(container, status)
                    statusSubject onNext Unschedule(container, id)
                    true
                } else if (status.getStatusCode == Code.RUNNING) {
                    log info s"Container running at host $id"
                    sub.unsubscribe()
                    state = UpState(id, container)
                    statusSubject onNext Up(container, status)
                    false
                } else false
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
                             s"host as bad for ${config.schedulerBadHostLifetimeMs} " +
                             "milliseconds and rescheduling"
                    badHosts += id -> BadHost(currentTime, 0)
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
        val expiryTime = currentTime - config.schedulerBadHostLifetimeMs
        badHosts --= addedHosts
        for ((hostId, badHost) <- badHosts.toList
             if badHost.timestamp < expiryTime) {
            badHosts -= hostId
        }
        currentHosts = hosts
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
            case UpState(id, container) =>
                log info s"Deleting container at host $id because the " +
                         "scheduling update stream has completed"
                state = DownState
                subscriber onNext Unschedule(container, id)
                subscriber.onCompleted()
            case DownState =>
                subscriber.onCompleted()
        }
    }

}
