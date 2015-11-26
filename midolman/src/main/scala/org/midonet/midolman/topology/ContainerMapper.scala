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

package org.midonet.midolman.topology

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import javax.annotation.Nullable

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Future, Promise}

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.MoreObjects
import com.typesafe.scalalogging.Logger

import rx.Observable.OnSubscribe
import rx.subjects.PublishSubject
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber, Subscription}

import org.midonet.cluster.data.ZoomConvert.fromProto
import org.midonet.cluster.data.{Zoom, ZoomField, ZoomObject}
import org.midonet.cluster.models.Topology.{ServiceContainer, Host, Port}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter, _}
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.ContainerMapper.{BindingState, Notification}
import org.midonet.midolman.topology.DeviceMapper.MapperState
import org.midonet.midolman.topology.containers.ContainerPort
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object ContainerMapper {

    trait Notification
    case class Created(port: ContainerPort) extends Notification
    case class Changed(port: ContainerPort) extends Notification
    case class Deleted(port: ContainerPort) extends Notification

    private val NullBinding = PortBinding(null, null, null, null)

    case class PortBinding @Zoom()(@ZoomField(name = "id",
                                              converter = classOf[UUIDConverter])
                                   portId: UUID,
                                   @ZoomField(name = "host_id",
                                              converter = classOf[UUIDConverter])
                                   hostId: UUID,
                                   @ZoomField(name = "interface_name")
                                   interfaceName: String,
                                   @ZoomField(name = "service_container_id",
                                              converter = classOf[UUIDConverter])
                                   containerId: UUID)
        extends ZoomObject {

        /** The port is bound.
          */
        def isBound: Boolean = (hostId ne null) && (interfaceName ne null)
        /** The port is bound to a container.
          */
        def hasContainer = isBound && (containerId ne null)

        override def toString =
            MoreObjects.toStringHelper(this).omitNullValues()
                .add("portId", portId)
                .add("hostId", hostId)
                .add("interfaceName", interfaceName)
                .add("containerId", containerId)
                .toString

    }

    /**
      * Stores the local state for a service container. An instance of this
      * class monitors the service container with the specified identifier
      * and exposes an observable that emits whether the state contains the
      * first container configuration.
      */
    private final class ContainerState(containerId: UUID, vt: VirtualTopology,
                                       log: Logger) {

        private var currentConfigurationId: UUID = null
        private var currentGroupId: UUID = null
        private val mark = PublishSubject.create[ServiceContainer]()

        val observable =
            vt.store.observable(classOf[ServiceContainer], containerId)
                .map[(UUID, UUID)](makeFunc1 { c =>
                    (c.getConfigurationId.asJava, c.getServiceGroupId.asJava)
                })
                .distinctUntilChanged()
                .observeOn(vt.vtScheduler)
                .map[Boolean](makeFunc1(containerUpdated))
                .takeUntil(mark)

        /** Completes the observable exposed by this container state.
          */
        def complete(): Unit = mark.onCompleted()
        /** Indicates whether the state received the service container state.
          */
        def isReady = currentConfigurationId ne null
        /** Returns the current container configuration identifier.
          */
        @Nullable
        def configurationId = currentConfigurationId
        /** Returns the current container container group identifier.
          */
        @Nullable
        def groupId = currentGroupId

        private def containerUpdated(ids: (UUID, UUID)): Boolean = {
            val first = currentConfigurationId eq null
            log debug s"Container $containerId updated with configuration " +
                      s"${ids._1} group ${ids._2}"
            currentConfigurationId = ids._1
            currentGroupId = ids._2
            first
        }
    }

    /**
      * Stores the local state for a port binding. An instance of this class
      * monitors updates for the specified port identifier. If any moment the
      * port is attached to a container, the class loads the corresponding
      * service container data and emits a [[Created]] notification. After
      * emitting a [[Created]] notification, whenever the port becomes detached
      * from the container, or the port is deleted, or emits an error, or the
      * `complete` method is called, the observable will emit a [[Deleted]]
      * notification.
      */
    private final class BindingState(hostId: UUID, portId: UUID,
                                     vt: VirtualTopology, log: Logger) {

        private var currentBinding: PortBinding = NullBinding
        private var currentContainer: ContainerState = null
        private val mark = PublishSubject.create[Notification]

        val observable =
            vt.store.observable(classOf[Port], portId)
                .map[PortBinding](makeFunc1(fromProto(_, classOf[PortBinding])))
                .distinctUntilChanged()
                .observeOn(vt.vtScheduler)
                .flatMap(makeFunc1(bindingUpdated))
                .doOnCompleted(makeAction0(bindingDeleted()))
                .onErrorResumeNext(makeFunc1(bindingError))
                .takeUntil(mark)

        /**
          * Returns an initial notification from this binding state, to publish
          * to new subscribers.
          */
        def initial: Option[Notification] = {
            vt.assertThread()
            if (currentBinding.hasContainer && (currentContainer ne null) &&
                currentContainer.isReady) {
                log debug s"Container ${currentBinding.containerId} notified " +
                          s"for binding $currentBinding upon new subscription"
                Some(Created(ContainerPort(portId, hostId,
                                           currentBinding.interfaceName,
                                           currentBinding.containerId,
                                           currentContainer.groupId,
                                           currentContainer.configurationId)))
            } else {
                None
            }
        }

        /**
          * Completes the observable stream for the current bindings state. The
          * method returns an observable, which if this binding state has
          * previously emitted a [[Created]] notification to create a container,
          * will emit a [[Deleted]] state to delete the container.
          */
        def complete(): Observable[Notification] = {
            mark.onCompleted()
            cleanup
        }

        /**
          * Processes an update from the current binding. The method verifies if
          * the binding container has changed and: (a) if the port has a new
          * container identifier, it loads the container, (b) if the container
          * has changed it will emit a [[Deleted]] notification for the
          * current container and loads the new container, or (c) if the
          * container was removed it will emit a [[Deleted]] notification for
          * the current container.
          *
          * The method returns an observable that will complete immediately if
          * the new binding does not correspond to a container, or will emit
          * the current container configuration whenever the container changes.
          */
        private def bindingUpdated(newBinding: PortBinding)
        : Observable[Notification] = {
            vt.assertThread()

            val oldBinding = currentBinding
            currentBinding = newBinding

            val observable =
                if (oldBinding.hasContainer && newBinding.hasContainer) {
                    changeContainer(oldBinding, newBinding)
                } else if (newBinding.hasContainer) {
                    addContainer(newBinding)
                } else if (oldBinding.hasContainer) {
                    removeContainer(oldBinding)
                } else {
                    log debug s"Non-container port binding updated $newBinding"
                    Observable.empty[Notification]()
                }

            observable
        }

        /**
          * Handles the deletion of this port binding.
          */
        private def bindingDeleted(): Unit = {
            log debug s"Port binding $currentBinding deleted"
        }

        /**
          * Handles errors emitted by the binding or container observables. The
          * method returns an observable which emits any cleanup notifications
          * for this binding state.
          */
        private def bindingError(e: Throwable): Observable[Notification] = {
            log.warn(s"Port binding host $hostId port $portId completed with " +
                     "error", e)
            cleanup
        }

        /**
          * Processes updates to this binding, when the binding was connected to
          * a new container. The method creates a new container state, and
          * returns its observable to be flat mapped into the binding
          * observable.
          */
        private def addContainer(binding: PortBinding): Observable[Notification] = {
            // Verify the binding is for the current host.
            if (binding.hostId != hostId)
                return Observable.empty()

            log debug s"Container ${binding.containerId} added to " +
                      s"binding $binding"

            // If there is a current container state, complete its observable:
            // this is only a sanity-check because any previous state should
            // have been completed, and therefore we do not need to include a
            // deleted notification.
            if (currentContainer ne null) {
                currentContainer.complete()
            }

            // Subscribe to the service container for this binding.
            currentContainer = new ContainerState(binding.containerId, vt, log)
            currentContainer.observable.map(makeFunc1 { first =>
                if (first)
                    Created(ContainerPort(portId, hostId,
                                          binding.interfaceName,
                                          binding.containerId,
                                          currentContainer.groupId,
                                          currentContainer.configurationId))
                else
                    Changed(ContainerPort(portId, hostId,
                                          binding.interfaceName,
                                          binding.containerId,
                                          currentContainer.groupId,
                                          currentContainer.configurationId))
            })
        }

        /**
          * Processes changes to the current binding, that may modify any of
          * the host identifier, interface name or the container identifier.
          */
        private def changeContainer(oldBinding: PortBinding,
                                    newBinding: PortBinding)
        : Observable[Notification] = {
            removeContainer(oldBinding).concatWith(addContainer(newBinding))
        }

        /**
          * Processes updates to this binding, when the binding was disconnected
          * from a container. The method completes the update stream of the
          * current container state, and then returns the cleanup observable.
          */
        private def removeContainer(binding: PortBinding): Observable[Notification] = {
            log debug s"Container ${binding.containerId} removed from " +
                      s"binding $binding"

            val container = currentContainer
            currentContainer = null

            if (container eq null) {
                Observable.empty()
            } else  if (container.isReady) {
                Observable.just(Deleted(ContainerPort(portId, hostId,
                                                      binding.interfaceName,
                                                      binding.containerId,
                                                      container.groupId,
                                                      container.configurationId)))
            } else {
                Observable.empty()
            }
        }

        /**
          * Returns an observable that, if a previous container was created for
          * this binding, returns a [[Deleted]] notification that deletes the
          * container before completing the observable stream.
          */
        private def cleanup: Observable[Notification] = {
            if (currentBinding.hasContainer && (currentContainer ne null) &&
                currentContainer.isReady) {
                log debug s"Container ${currentBinding.containerId} deleted " +
                          s"for binding $currentBinding upon completion"
                val groupId = currentContainer.groupId
                val configurationId = currentContainer.configurationId

                currentContainer.complete()
                currentContainer = null

                Observable.just(Deleted(ContainerPort(portId, hostId,
                                                      currentBinding.interfaceName,
                                                      currentBinding.containerId,
                                                      groupId,
                                                      configurationId)))
            } else {
                Observable.empty()
            }
        }

    }

}

/**
  * An implementation of the [[OnSubscribe]] interface for an [[rx.Observable]]
  * that emits updates for managing service containers.
  */
final class ContainerMapper(hostId: UUID, vt: VirtualTopology)
    extends OnSubscribe[Notification] with MidolmanLogging {

    override def logSource = "org.midonet.containers"

    private val state = new AtomicReference(MapperState.Unsubscribed)

    private lazy val unsubscribeAction = makeAction0(onUnsubscribe())

    @volatile private var error: Throwable = null
    @volatile private var hostSubscription: Subscription = null

    private lazy val hostObservable =
        vt.store.observable(classOf[Host], hostId)
            .map[Set[UUID]](makeFunc1(_.getPortIdsList.asScala.map(_.asJava).toSet))
            .distinctUntilChanged()
            .observeOn(vt.vtScheduler)
            .doOnNext(makeAction1(bindingsUpdated))
            .doOnCompleted(makeAction0(hostDeleted()))
            .doOnError(makeAction1(hostError))
            .map[Observable[Notification]](makeFunc1(_ => Observable.empty()))

    private val bindings = new mutable.HashMap[UUID, BindingState]

    private val subject = PublishSubject.create[Observable[Notification]]
    private lazy val observable = Observable.merge(subject)

    /**
      * Completes the notifications from the current mapper, after notifying
      * the completion for all port bindings that have a container on the
      * current host. The method returns a future, which completes when all
      * notifications have been emitted successfully.
      */
    def complete(): Future[Unit] = {
        val promise = Promise[Unit]()
        vt.executeVt {
            log info s"Stopping all containers on demand"

            for (bindingState <- bindings.values) {
                subject onNext bindingState.complete()
            }
            subject onError DeviceMapper.MapperClosedException
            state set MapperState.Closed

            // Unsubscribe from the underlying observables.
            val subscription = hostSubscription
            if (subscription ne null) {
                subscription.unsubscribe()
                hostSubscription = null
            }

            promise.success(())
        }
        promise.future
    }

    /**
      * Processes new subscriptions to an observable created for this mapper.
      */
    override def call(child: Subscriber[_ >: Notification]): Unit = {
        // If the mapper is in any terminal state, complete the child
        // immediately and return.
        if (state.get == MapperState.Completed) {
            child.onCompleted()
            return
        }
        if (state.get == MapperState.Error) {
            child onError error
            return
        }
        if (state.get == MapperState.Closed) {
            child onError DeviceMapper.MapperClosedException
            return
        }

        // Otherwise, schedule the subscription on the VT thread.
        vt.executeVt {
            if (state.compareAndSet(MapperState.Unsubscribed,
                                    MapperState.Subscribed)) {

                hostSubscription = hostObservable subscribe subject
            }

            observable.startWith(initialUpdates) subscribe child
            child add Subscriptions.create(unsubscribeAction)
        }
    }

    /** Returns the current mapper state. */
    @VisibleForTesting
    protected[topology] def mapperState = state.get

    /** Returns whether the mapper has any subscribed observers. */
    @VisibleForTesting
    protected[topology] def hasObservers = subject.hasObservers

    /**
      * A method called when a subscriber unsubscribes. If there are no more
      * subscriber, the method unsubscribes from the underlying observables and
      * clears any internal state. The mapper is set in a closed state, such
      * that any subsequent subscriber will be notified immediately that the
      * mapper is no longer available.
      */
    private def onUnsubscribe(): Unit = {
        // Ignore notifications if the mapper is in a terminal state.
        if (state.get.isTerminal) return

        val subscription = hostSubscription
        if (!subject.hasObservers && (subscription ne null) &&
            state.compareAndSet(MapperState.Subscribed, MapperState.Closed)) {
            log debug s"Closing container notification stream for host $hostId"

            subject onError DeviceMapper.MapperClosedException
            subscription.unsubscribe()
            hostSubscription = null
        }
    }

    /**
      * Processes updates when the bindings of the current host have changed.
      * The method subscribes to the ports for any new bindings, and completes
      * the state of any bindings that have been removed.
      */
    private def bindingsUpdated(portIds: Set[UUID]): Unit = {
        vt.assertThread()
        log debug s"Host $hostId bindings updated: $portIds"

        // Complete the observables for the ports that are not part of the
        // port bindings for this host.
        for ((portId, bindingState) <- bindings.toList
             if !portIds.contains(portId)) {
            subject onNext bindingState.complete()
            bindings -= portId
        }

        // Create observables for the new bindings in the port bindings set, and
        // emit them on the notifications subject.
        val addedBindings = new mutable.MutableList[BindingState]
        for (portId <- portIds if !bindings.contains(portId)) {
            val bindingState = new BindingState(hostId, portId, vt, log)
            bindings += portId -> bindingState
            addedBindings += bindingState
        }

        // Publish the observables for the added bindings.
        for (portState <- addedBindings) {
            subject onNext portState.observable
        }
    }

    /**
      * The method is called when the host is deleted. It triggers the
      * completion of the output observable, by completing all port bindings
      * observables.
      */
    private def hostDeleted(): Unit = {
        vt.assertThread()
        log debug s"Host $hostId deleted: stopping all containers"

        state set MapperState.Completed

        for (bindingState <- bindings.values) {
            subject onNext bindingState.complete()
        }
        subject.onCompleted()

        // Unsubscribe from the underlying observables.
        val subscription = hostSubscription
        if (subscription ne null) {
            subscription.unsubscribe()
            hostSubscription = null
        }
    }

    /**
      * The method is called when an error occurs in the host observable. It
      * completes the output observable with an error, after completing all port
      * bindings observable.
      */
    private def hostError(e: Throwable): Unit = {
        vt.assertThread()
        log.error(s"Host $hostId emitted error: stopping all containers", e)

        error = e
        state set MapperState.Error

        for (bindingState <- bindings.values) {
            subject onNext bindingState.complete()
        }
        subject onError e

        // Unsubscribe from the underlying observables.
        val subscription = hostSubscription
        if (subscription ne null) {
            subscription.unsubscribe()
            hostSubscription = null
        }
    }

    /**
      * Returns an observable that publishes the current containers to a new
      * subscriber.
      */
    private def initialUpdates: Observable[Notification] = {
        val updates = for (bindingState <- bindings.values) yield {
            bindingState.initial
        }
        Observable.from(updates.filter(_.isDefined).map(_.get).asJava)
    }

}
