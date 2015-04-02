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

import java.util
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import javax.annotation.Nullable

import scala.collection.mutable

import com.google.common.annotations.VisibleForTesting
import com.typesafe.scalalogging.Logger

import rx.Observable.OnSubscribe
import rx.subscriptions.Subscriptions
import rx.{Subscription, Observer, Observable, Subscriber}
import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.BgpMapper.{BgpPort, State, BgpState, BgpUpdate, EmptyUpdateList}
import org.midonet.cluster.models.Topology.{Bgp => TopologyBgp, BgpRoute => TopologyRoute}
import org.midonet.midolman.topology.devices.{RouterPort, Port}
import org.midonet.midolman.topology.routing.{BgpRoute, Bgp}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object BgpMapper {
    trait BgpUpdate
    case class BgpPort(port: RouterPort) extends BgpUpdate
    case class BgpAdded(bgp: Bgp) extends BgpUpdate
    case class BgpUpdated(bgp: Bgp) extends BgpUpdate
    case class BgpRemoved(bgpId: UUID) extends BgpUpdate
    case class BgpRouteAdded(route: BgpRoute) extends BgpUpdate
    case class BgpRouteRemoved(route: BgpRoute) extends BgpUpdate

    /** The state of the mapper subscription to the underlying storage
      * observables. Unlike the device mappers, the BGP mapper subscribes
      * itself to storage in order to expose only the BGP updates. */
    private[topology] object State extends Enumeration {
        type State = Value
        val Subscribed, Unsubscribed, Completed, Error = Value
    }

    private final val EmptyUpdateArray = Array.empty[BgpUpdate]
    private final val EmptyUpdateList = new util.ArrayList[BgpUpdate](0)

    /**
     * Stores the state for a BGP peering, and exposes an [[rx.Observable]] that
     * emits updates for this BGP peering. The observable completes either when
     * the peering is deleted, or when calling the `complete()` method, which is
     * used to signal that a peering no longer belongs to a port.
     */
    private final class BgpState(val bgpId: UUID, vt: VirtualTopology,
                                 log: Logger) {

        private var currentBgp: Bgp = null

        private val routes = new mutable.HashMap[UUID, RouteState]
        private val bgpSubject = PublishSubject.create[Observable[BgpUpdate]]

        private val bgpSubscriber = new Subscriber[Bgp] {
            override def onNext(bgp: Bgp) = bgpUpdated(bgp)
            override def onCompleted() = bgpDeleted()
            override def onError(e: Throwable) = bgpError(e)
        }
        private val bgpSubscription = vt.store
            .observable(classOf[TopologyBgp], bgpId)
            .distinctUntilChanged()
            .map[Bgp](makeFunc1(ZoomConvert.fromProto(_, classOf[Bgp])))
            .observeOn(vt.vtScheduler)
            .subscribe(bgpSubscriber)

        /** An observable that emits BGP updates for this BGP peering state. */
        val observable = Observable.merge(bgpSubject)

        /** Completes the observable corresponding to this BGP peering state,
          * and all corresponding route states. Before completion, the state
          * will notify the removal of all current BGP routes and a
          * [[BgpRemoved]] update. */
        def complete(): Unit = {
            if (!bgpSubscription.isUnsubscribed) {
                bgpSubscription.unsubscribe()
                bgpDeleted()
            }
        }
        /** Gets the underlying BGP peering for this state. */
        @inline @Nullable def bgp: Bgp = currentBgp
        /** Indicates whether the BGP state has received the BGP data. */
        @inline def isReady: Boolean = currentBgp ne null
        /** Gets the number of routes advertised by this BGP peering. */
        @inline def routeCount = routes.size

        /**
         * Sets the sequence of initialization updates for this BGP peering.
         * This is used by subsequent subscribers to receive the updates from
         * all BGP peering entries, including any advertised routes.
         * @param updates A list to which this method appends the initialization
         *                updates.
         */
        def initializationUpdates(updates: util.ArrayList[BgpUpdate]) = {
            log.debug("Initialize updates for BGP peering {} ready {}", bgpId,
                      Boolean.box(isReady))
            if (isReady) {
                updates add BgpAdded(bgp)
                for (route <- routes.values) {
                    route.initializationUpdates(updates)
                }
            }
        }

        /** Processes updates to the BGP peering, by emitting a [[BgpAdded]] or
          * [[BgpUpdated]] notification and updating any of the corresponding
          * BGP routes. */
        private def bgpUpdated(bgp: Bgp): Unit = {
            vt.assertThread()
            log.debug("BGP peering updated: {}", bgp)

            // If this is the first notification, emit a BGP added update.
            val message: BgpUpdate =
                if (currentBgp eq null) BgpAdded(bgp) else BgpUpdated(bgp)
            bgpSubject onNext Observable.just(message)

            // Remove the state for the routes that are no longer part of this
            // BGP peering.
            for ((routeId, route) <- routes.toList
                 if !bgp.bgpRouteIds.contains(routeId)) {
                routes -= routeId
                route.complete()
            }

            // Add the state for the new routes of this BGP peering, and notify
            // its observable on the routes observable.
            for (routeId <- bgp.bgpRouteIds if !routes.contains(routeId)) {
                val routeState = new RouteState(routeId, vt, log)
                routes += routeId -> routeState
                bgpSubject onNext routeState.observable
            }

            currentBgp = bgp
        }

        /** Method called when the BGP peering is deleted either by receiving
          * an `onCompleted` notification, or when calling the `complete()`
          * method. It completes each route state for this BGP peering and
          * emits a [[BgpRemoved]] notification. */
        private def bgpDeleted(): Unit = {
            log.debug("BGP peering deleted {}", bgpId)
            for (route <- routes.values) {
                route.complete()
            }
            bgpSubject onNext Observable.just(BgpRemoved(bgpId))
            bgpSubject.onCompleted()
        }

        /** Method called when the BGP peering receives an error. It behaves
          * the same as the `bgpDeleted()` method, except that it propagates
          * the error to the output observable. */
        private def bgpError(e: Throwable): Unit = {
            log.debug("BGP peering error {}", bgpId, e)
            for (route <- routes.values) {
                route.complete()
            }
            bgpSubject onNext Observable.just(BgpRemoved(bgpId))
            bgpSubject onError e
        }
    }

    /**
     * Stores the state for a BGP route, and exposes an [[rx.Observable]] that
     * emits updates for this BGP route. The observable completes either when
     * the route is deleted, or when calling the `complete()` method, which is
     * used to signal that a route no longer belongs to a BGP peering.
     */
    private final class RouteState(routeId: UUID, vt: VirtualTopology,
                                   log: Logger) {

        private var currentRoute: BgpRoute = null

        private val routeSubject = PublishSubject.create[BgpUpdate]
        private val routeSubscription = vt.store
            .observable(classOf[TopologyRoute], routeId)
            .distinctUntilChanged()
            .map[BgpRoute](makeFunc1(ZoomConvert.fromProto(_, classOf[BgpRoute])))
            .observeOn(vt.vtScheduler)
            .onErrorResumeNext(Observable.empty())
            .doOnCompleted(makeAction0(routeDeleted()))
            .flatMap[BgpUpdate](makeFunc1(routeUpdated))
            .subscribe(routeSubject)

        /** Observable that emits BGP updates for this route. */
        val observable = routeSubject.asObservable

        /** Completes the observable corresponding to this route state. */
        def complete(): Unit = {
            routeSubscription.unsubscribe()
            if (currentRoute ne null) {
                routeSubject onNext BgpRouteRemoved(currentRoute)
            }
            routeSubject.onCompleted()
        }

        /** Indicates whether the route state has received the route data. */
        @inline def isReady: Boolean = currentRoute ne null

        /** Sets the initialization update for this route. */
        def initializationUpdates(updates: util.ArrayList[BgpUpdate]): Unit = {
            if (isReady) updates add BgpRouteAdded(currentRoute)
        }

        /** Called when a BGP route is updated. It generates zero or more route
          * updates when the route changes. */
        private def routeUpdated(route: BgpRoute): Observable[BgpUpdate] = {
            // Ignore the update when the route has not changed.
            if (route == currentRoute) {
                return Observable.empty()
            }
            vt.assertThread()
            log.debug("BGP route updated: {}", route)

            val updatesObservable = routesAsObservable(route, currentRoute)
            currentRoute = route
            updatesObservable
        }

        /** Called when a BGP route is deleted or its observable completes due
          * to an error. It emits a [[BgpRouteRemoved]] notification that
          * deletes the current advertised route. */
        private def routeDeleted(): Unit = {
            vt.assertThread()
            log.debug("BGP route deleted: {}", currentRoute)

            if (currentRoute ne null) {
                routeSubject onNext BgpRouteRemoved(currentRoute)
            }
        }

        @inline
        private def routesAsObservable(added: BgpRoute, removed: BgpRoute)
        : Observable[BgpUpdate] = {
            val updates: Array[BgpUpdate] =
                if ((added ne null) && (removed ne null))
                    Array(BgpRouteRemoved(removed), BgpRouteAdded(added))
                else if (added ne null)
                    Array(BgpRouteAdded(added))
                else if (removed ne null)
                    Array(BgpRouteRemoved(removed))
                else
                    EmptyUpdateArray
            Observable.from(updates)
        }
    }
}

/**
 * An implementation of the [[OnSubscribe]] interface for an [[rx.Observable]]
 * that emits [[BgpUpdate]] notifications.
 */
final class BgpMapper(portId: UUID, vt: VirtualTopology = VirtualTopology.self)
    extends OnSubscribe[BgpUpdate] with MidolmanLogging {

    override def logSource = s"org.midonet.routing.bgp.bgp-$portId"

    private val state = new AtomicReference(State.Unsubscribed)
    @volatile private var error: Throwable = null

    private var portSubscription: Subscription = null
    private lazy val portObserver = new Observer[RouterPort] {
        override def onNext(port: RouterPort) = portUpdated(port)
        override def onCompleted() = portDeleted()
        override def onError(e: Throwable) = portOrBgpError(e)
    }

    private var port: RouterPort = null
    private var bgp: BgpState = null
    private val bgpSubject = PublishSubject.create[Observable[BgpUpdate]]

    private lazy val errorAction = makeAction1(portOrBgpError)
    private lazy val unsubscribeAction = makeAction0(onUnsubscribe())

    private lazy val observable = Observable.merge(bgpSubject)

    /** Indicates whether the underlying subject has one or more observers. */
    @VisibleForTesting
    private[topology] def hasObservers = bgpSubject.hasObservers
    /** Indicates the mapper state. */
    @VisibleForTesting
    private[topology] def mapperState = state.get

    /** Processes new subscriptions to an observable created for this mapper. */
    override def call(child: Subscriber[_ >: BgpUpdate]): Unit = {
        // If the mapper is in any terminal state, complete the child
        // immediately and return.
        if (state.get == State.Completed) {
            child.onCompleted()
            return
        }
        if (state.get == State.Error) {
            child.onError(error)
            return
        }
        // Otherwise, schedule the subscription on the VT thread.
        vt.executeVt {
            if (state.compareAndSet(State.Unsubscribed, State.Subscribed)) {
                portSubscription = VirtualTopology
                    .observable[Port](portId)
                    .map[RouterPort](makeFunc1(portMap))
                    .subscribe(portObserver)
            }
            observable.startWith(initializationUpdates) subscribe child
            child.add(Subscriptions.create(unsubscribeAction))
        }
    }

    /** Maps a port to a router port. If the port is not a router port, the
      * method throws an exception that will propagate via the observable. */
    private def portMap(port: Port): RouterPort = {
        port match {
            case routerPort: RouterPort => routerPort
            case _ =>
                throw new DeviceMapperException(s"Port $portId is not a " +
                                                s"router port")
        }
    }

    /** Processes updates from the topology port observable. This examines the
      * addition/removal of the port BGP entries, and add/removes the
      * corresponding BGP observables. */
    private def portUpdated(p: RouterPort): Unit = {
        vt.assertThread()
        // Ignore updates if the mapper is unsubscribed
        if (state.get != State.Subscribed) return
        log.debug("Port updated: {}", p)

        port = p
        bgpSubject onNext Observable.just(BgpPort(port))

        // Only advertised the routes for exterior ports.
        val bgpId = if (port.isExterior) port.bgpId else null

        // Complete the observables for the BGP peering no longer part of this
        // port.
        if ((bgp ne null) && bgp.bgpId != bgpId) {
            bgp.complete()
            bgp = null
        }

        // Create observables for the new BGP peering for this port, and notify
        // them on the BGP subject.
        if ((bgp eq null) && (bgpId ne null)) {
            bgp = new BgpState(bgpId, vt, log)
            bgpSubject onNext bgp.observable.doOnError(errorAction)
        }
    }

    /** Method called when the port is deleted. The method notifies the
      * completion updates before completing the underlying observables. */
    private def portDeleted(): Unit = {
        vt.assertThread()
        log.debug("Port deleted")
        state.set(State.Completed)
        if (bgp ne null) {
            bgp.complete()
        }
        bgp = null
        bgpSubject.onCompleted()
    }

    /** Method called when the port or a BGP state observable emits an error.
      * The method notifies the completion updates before completing the
      * underlying observables. */
    private def portOrBgpError(e: Throwable): Unit = {
        vt.assertThread()
        log.error("Port, BGP or BGP route error", e)
        state.set(State.Error)
        error = e
        if (bgp ne null) {
            bgp.complete()
        }
        bgp = null
        bgpSubject.onError(e)
    }

    /** A handler called when a subscriber unsubscribes. If there are no more
      * subscribers, the method unsubscribes from the underlying observbales,
      * and clears any internal state. */
    private def onUnsubscribe(): Unit = vt.executeVt {
        if (!bgpSubject.hasObservers &&
            state.compareAndSet(State.Subscribed, State.Unsubscribed)) {
            log.debug("Unsubscribing from port notifications")

            port = null
            if (bgp ne null) {
                bgp.complete()
            }
            bgp = null
            portSubscription.unsubscribe()
            portSubscription = null
        }
    }

    /** Returns an observable that emits a set of BGP updates corresponding to
      * the initial state of the router port. The purpose of this method is to
      * publish the current BGP state to any non-initial subscriber. */
    private def initializationUpdates: util.List[BgpUpdate] = {
        vt.assertThread()
        val updates = if (port ne null) {
            val updatesCount = if (bgp ne null) 2 + bgp.routeCount else 1
            val updates = new util.ArrayList[BgpUpdate](updatesCount)

            updates add BgpPort(port)
            if (bgp ne null) {
                bgp.initializationUpdates(updates)
            }
            updates
        } else EmptyUpdateList
        log.debug("Initialization updates for subscriber: {}", updates)
        updates
    }
}
