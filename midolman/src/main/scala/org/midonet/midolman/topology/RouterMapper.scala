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

import java.lang.{Boolean => JBoolean}
import java.util.{UUID, ArrayList => JArrayList}

import javax.annotation.Nullable

import scala.collection.JavaConverters._
import scala.collection.mutable

import rx.Observable
import rx.subjects.{PublishSubject, Subject}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Route => TopologyRoute, Router => TopologyRouter}
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.layer3.{IPv4RoutingTable, Route}
import org.midonet.midolman.simulation.Router.{Config, RoutingTable, TagManager}
import org.midonet.midolman.simulation.{Chain, LoadBalancer, Mirror, RouterPort, Router => SimulationRouter}
import org.midonet.midolman.SimulationBackChannel.{BackChannelMessage, Broadcast}
import org.midonet.midolman.state.ArpCache
import org.midonet.midolman.topology.RouterMapper._
import org.midonet.odp.FlowMatch
import org.midonet.packets.{IPAddr, IPv4Addr}
import org.midonet.util.collection.IPv4InvalidationArray
import org.midonet.util.functors._
import org.midonet.util.logging.Logger

object RouterMapper {

    private val EmptyRouteSet = Set.empty[Route]
    private val EmptyRouteUpdates = RouteUpdates(EmptyRouteSet, EmptyRouteSet)

    case class InvalidateFlows(routerId: UUID,
                               addedRoutes: Set[Route],
                               deletedRoutes: Set[Route])
        extends BackChannelMessage with Broadcast

    case class RouterInvTrieTagCountModified(dstIp: IPAddr, count: Int)

    case class RouteUpdates(added: Set[Route], removed: Set[Route]) {
        def nonEmpty = added.nonEmpty || removed.nonEmpty
        override def toString = s"[added=$added removed=$removed]"
    }

    /**
     * Stores the state for a router port, and exposes an [[Observable]] that
     * emits updates for this port's routes. The observable completes either
     * when the port is deleted, or when calling the complete() method, which is
     * used to signal that the port is no longer used by the router.
     */
    private class PortState(portId: UUID,
                            vniMap: mutable.HashMap[Int, UUID],
                            vt: VirtualTopology,
                            log: Logger) {

        @Nullable private var currentPort: RouterPort = null
        private val mark = PublishSubject.create[RouteUpdates]

        private val routes = new mutable.HashMap[UUID, RouteState]
        private val routesSubject = PublishSubject
            .create[Observable[RouteUpdates]]

        // Caches the current routes for this port. Each route state emits route
        // updates that include both the added and removed route. The routes
        // cache merge these updates into the set of current routes for this
        // port.
        private val routesCache = new mutable.HashSet[Route]

        private val portStateSubject = PublishSubject.create[UUID]
        private var portStateReady = false

        private val portObservable = VirtualTopology
            .observable(classOf[RouterPort], portId)
            .map[RouteUpdates](makeFunc1(portUpdated))
        private val routesObservable = Observable
            .merge(routesSubject)
            .map[RouteUpdates](makeFunc1(routeUpdated))
        private val learnedRoutesObservable = vt.stateStore
            .portRoutesObservable(portId, portStateSubject)
            .observeOn(vt.vtScheduler)
            .map[RouteUpdates](makeFunc1(learnedRoutesUpdated))

        // The output observable for this port state. It merges the
        // notifications for port and route updates, and emits route updates
        // for this port. Route updates are emitted when both the route for the
        // port changes, and when the port changes (routes are added, removed
        // or the port changes state between active and inactive).
        //             +-------------+
        // VT[Port] -> | portUpdated |---------------------+
        //             +-------------+                     |
        //                    | route added/removed        |
        //                    |          +--------------+  |
        //              routesSubject -> | routeUpdated |--+-> route updates
        //                               +--------------+  |
        //                                                 |
        //                        +----------------------+ |
        // State[Port, Routes] -> | learnedRoutesUpdated |-+
        //                        +----------------------+
        val observable = Observable.merge(routesObservable,
                                          learnedRoutesObservable,
                                          portObservable)
            .onErrorResumeNext(Observable.just(RouteUpdates(EmptyRouteSet,
                                                            publishedRoutes)))
            .takeUntil(mark)

        /** Completes the observable corresponding to this port state, and to
          * each port route state. The method returns a route update that
          * removes all routes previously published by the port. */
        def complete(): RouteUpdates = {
            if ((currentPort ne null) && currentPort.isL2)
                vniMap.remove(currentPort.vni)
            routes.foreach(_._2.complete())
            routesSubject.onCompleted()
            mark.onCompleted()

            RouteUpdates(EmptyRouteSet, publishedRoutes)
        }
        /** Indicates whether the port state has received the port data. */
        def isReady: Boolean = {
            (currentPort ne null) && portStateReady &&
            routes.forall(_._2.isReady)
        }

        /**
         * A method called when a router port has changed. The method updates
         * the current port, and emits a set of route updates corresponding
         * to the port state change and its route set.
         * - If the port was and remains active, then emit only the removed
         *   routes.
         * - If the port changed to active, then add all routes.
         * - If the port changed to inactive, then remove all routes.
         * - If the port was and remains inactive, then do not emit any updates.
         */
        private def portUpdated(port: RouterPort): RouteUpdates = {
            vt.assertThread()

            log.debug("Router port updated: {}", port)

            if (port.isL2)
                vniMap.update(port.vni, port.id)

            val oldPublish = isPublishingRoutes
            val newPublish = port.isInterior || port.isExterior &&
                                                port.isActive

            if ((currentPort eq null) || (currentPort.hostId != port.hostId)) {
                val hostId = port.hostId
                log.debug("Monitoring port state for host: {}", hostId)
                portStateReady = false
                portStateSubject onNext hostId
            }

            currentPort = port

            val removedRoutes = new mutable.HashSet[Route]
            val currentRoutesSet = routesCache.toSet

            // Remove the state for the routes that are no longer part of this
            // port.
            for ((routeId, routeState) <- routes.toList
                 if !port.routeIds.contains(routeId)) {
                routes -= routeId
                if (routeState.isReady) {
                    removedRoutes += routeState.route
                    routesCache -= routeState.route
                }
                routeState.complete()
            }

            // Add the state for the new routes of this port, and notify its
            // observable on the routes observable.
            val addedRoutes = new mutable.MutableList[RouteState]
            for (routeId <- port.routeIds if !routes.contains(routeId)) {
                val routeState = new RouteState(routeId, vt, log)
                routes += routeId -> routeState
                addedRoutes += routeState
            }
            for (routeState <- addedRoutes) {
                routesSubject onNext routeState.observable
            }

            if (oldPublish && newPublish) {
                // If the port previously published the updates, publish only
                // the difference.
                RouteUpdates(EmptyRouteSet, removedRoutes.toSet)
            } else if (!oldPublish && newPublish) {
                // If the port did not previously publish routes, but does so
                // now publish all routes.
                RouteUpdates(currentRoutesSet, EmptyRouteSet)
            } else if (oldPublish && !newPublish) {
                // If the port did previously publish routes, but does not now,
                // remove all routes.
                RouteUpdates(EmptyRouteSet,
                             currentRoutesSet ++ removedRoutes.toSet)
            } else {
                // The port does not publishes routes.
                EmptyRouteUpdates
            }
        }

        /** A method called when a route is updated. It publishes routes if the
          * port is interior, or exterior and active. */
        private def routeUpdated(updates: RouteUpdates): RouteUpdates = {
            vt.assertThread()
            log.debug("Port route updated: {}", updates)

            routesCache ++= updates.added
            routesCache --= updates.removed

            if (isPublishingRoutes) updates else EmptyRouteUpdates
        }

        /** A method called when the set of learned routes is updated. It
          * computes the difference between the previous learned routes update
          * and this one, and returns a [[RouteUpdates]] instance with the
          * difference. */
        private def learnedRoutesUpdated(routes: Set[Route]): RouteUpdates = {
            vt.assertThread()
            log.debug("Learned port routes updated: {} routes",
                      Int.box(routes.size))
            portStateReady = true

            val added = new mutable.HashSet[Route]
            val removed = new mutable.HashSet[Route]

            for (route <- routes if !routesCache.contains(route)) {
                added += route
            }

            for (route <- routesCache
                 if route.learned && !routes.contains(route)) {
                removed += route
            }

            routesCache ++= added
            routesCache --= removed

            if (isPublishingRoutes) RouteUpdates(added.toSet, removed.toSet)
            else EmptyRouteUpdates
        }

        /** Indicates whether the state has currently published routes. */
        private def isPublishingRoutes: Boolean = {
            (currentPort ne null) && currentPort.adminStateUp &&
            (currentPort.isInterior || currentPort.isExterior &&
                                       currentPort.isActive)
        }

        /** Gets the routes currently published by the port. */
        private def publishedRoutes: Set[Route] = {
            if (isPublishingRoutes) routesCache.toSet else EmptyRouteSet
        }
    }

    /**
     * Stores the state for a route, and exposes an [[Observable]] that emits
     * updates when the route has changed. The observable completes either when
     * the route is deleted, or when calling the complete() method, which is
     * used to signal that the route is no longer used by the router or port.
     */
    private class RouteState(val routeId: UUID, vt: VirtualTopology,
                             log: Logger) {

        @Nullable private var currentRoute: Route = null
        private val mark = PublishSubject.create[RouteUpdates]

        val observable = vt.store.observable(classOf[TopologyRoute], routeId)
            .observeOn(vt.vtScheduler)
            .flatMap[RouteUpdates](makeFunc1(routeUpdated))
            .onErrorResumeNext(makeFunc1(routeError))
            .takeUntil(mark)

        /** Completes the observable corresponding to this route state. */
        def complete(): Unit = mark.onCompleted()
        /** Gets the last route published by this route state. */
        @Nullable def route: Route = currentRoute
        /** Indicates whether the route state has received the route data. */
        def isReady: Boolean = currentRoute ne null

        /** Generates a route update when the route changes. */
        private def routeUpdated(tr: TopologyRoute): Observable[RouteUpdates] = {
            val route = ZoomConvert.fromProto(tr, classOf[Route])
            log.debug("Route updated: {}", route)

            val updateObservable =
                if (route != currentRoute)
                    Observable.just(RouteUpdates(routeAsSet(route),
                                                 routeAsSet(currentRoute)))
                else Observable.empty[RouteUpdates]

            currentRoute = route

            updateObservable
        }

        /** Handles the error emitted by the route observable by filtering
          * all errors, logging the error, and if the route is ready advertise
          * its removal. */
        private def routeError(e: Throwable): Observable[RouteUpdates] = {
            log.warn(s"Update stream emitted error for route $routeId: the " +
                     s"route will be ignored", e)
            if (isReady) Observable.just(RouteUpdates(EmptyRouteSet,
                                                      routeAsSet(currentRoute)))
            else Observable.empty()
        }
    }

    /**
     * Stores the state for a router load-balancer, and exposes an
     * [[Observable]] that emits updates for this load-balancer. The observable
     * completes either when the load-balancer is deleted, or when calling the
     * complete() method, which is used to signal that the load-balancer is no
     * longer used by the router.
     */
    private class LoadBalancerState(val loadBalancerId: UUID, log: Logger) {

        private var currentLoadBalancer: LoadBalancer = null
        private val mark = PublishSubject.create[LoadBalancer]

        val observable = VirtualTopology
            .observable(classOf[LoadBalancer], loadBalancerId)
            .onErrorResumeNext(DeviceMapper.handleException(loadBalancerId,
                                                            !isReady, log))
            .doOnNext(makeAction1(currentLoadBalancer = _))
            .takeUntil(mark)

        /** Completes the observable corresponding to this load-balancer
          * state. */
        def complete(): Unit = mark.onCompleted()
        /** Gets the load-balancer for this load-balancer state. */
        def loadBalancer: LoadBalancer = currentLoadBalancer
        /** Indicates whether the load-balancer state has received the load
          * balancer data. */
        def isReady: Boolean = currentLoadBalancer ne null
    }

    /**
     * Provides an implementation for a router's [[RoutingTable]], wrapping an
     * underlying IPv4 routing table.
     */
    private class RouterRoutingTable(routes: mutable.Set[Route])
        extends RoutingTable {

        private val ipv4RoutingTable = new IPv4RoutingTable()

        for (route <- routes) {
            ipv4RoutingTable.addRoute(route)
        }

        override def lookup(flowMatch: FlowMatch): java.util.List[Route] = {
            ipv4RoutingTable.lookup(
                flowMatch.getNetworkSrcIP.asInstanceOf[IPv4Addr],
                flowMatch.getNetworkDstIP.asInstanceOf[IPv4Addr])
        }

        override def lookup(flowMatch: FlowMatch, log: Logger): java.util.List[Route] = {
            ipv4RoutingTable.lookup(
                flowMatch.getNetworkSrcIP.asInstanceOf[IPv4Addr],
                flowMatch.getNetworkDstIP.asInstanceOf[IPv4Addr],
                log.underlying)
        }
    }

    /** Converts a nullable route to a [[Set]]. */
    @inline
    private def routeAsSet(route: Route) = {
        if (route ne null) Set(route) else EmptyRouteSet
    }
}

/**
 * A class that implements the [[VirtualDeviceMapper]] for a
 * [[SimulationRouter]].
 */
final class RouterMapper(routerId: UUID, vt: VirtualTopology,
                         val traceChainMap: mutable.Map[UUID,Subject[Chain,Chain]])
        extends VirtualDeviceMapper(classOf[SimulationRouter], routerId, vt)
        with TraceRequestChainMapper[SimulationRouter] {

    override def logSource = "org.midonet.devices.router"
    override def logMark = s"router:$routerId"

    private class RemoveTagCallback(dst: IPv4Addr) extends Callback0 {
        override def call(): Unit = {
            log.debug(s"Remove tag for destination address prefix $dst")
            IPv4InvalidationArray.current.unref(dst.toInt)
        }
    }

    private var config: Config = null
    private var ready: Boolean = false
    private val ports = new mutable.HashMap[UUID, PortState]
    private var loadBalancer: LoadBalancerState = null
    // Stores all routes received via notifications from the replicated routing
    // table.
    private val routes = new mutable.HashSet[Route]
    // Stores routes received via the router's configuration in storage.
    private val localRoutes = new mutable.HashMap[UUID, RouteState]
    private var arpCache: ArpCache = null
    private var traceChain: Option[UUID] = None
    private val vniToPort = new mutable.HashMap[Int, UUID]

    // Provides an implementation of the tag manager for the current router
    private val tagManager = new TagManager {
        override def addIPv4Tag(dst: IPv4Addr, matchLength: Int): Unit = {
            val refs = IPv4InvalidationArray.current.ref(dst.toInt, matchLength)
            log.debug(s"Increased ref count ip prefix $dst/$matchLength to $refs")
        }
        override def getFlowRemovalCallback(dst: IPv4Addr): Callback0 = {
            new RemoveTagCallback(dst)
        }
    }

    private val chainsTracker = new ObjectReferenceTracker(vt, classOf[Chain], log)
    private val mirrorsTracker = new ObjectReferenceTracker(vt, classOf[Mirror], log)

    private lazy val mark =
        PublishSubject.create[Config]
    private lazy val portRoutesSubject =
        PublishSubject.create[Observable[RouteUpdates]]
    private lazy val routesSubject =
        PublishSubject.create[Observable[RouteUpdates]]
    private lazy val loadBalancerSubject =
        PublishSubject.create[Observable[LoadBalancer]]

    private lazy val routerObservable =
        vt.store.observable(classOf[TopologyRouter], routerId)
            .observeOn(vt.vtScheduler)
            .doOnCompleted(makeAction0(routerDeleted()))
            .map[Config](makeFunc1(routerUpdated))
    private lazy val portRoutesObservable = Observable
        .merge(portRoutesSubject)
        .map[Config](makeFunc1(routingTableUpdated))
    private lazy val routesObservable = Observable
        .merge(routesSubject)
        .map[Config](makeFunc1(routingTableUpdated))
    private lazy val arpTableObservable = ArpCache
        .createAsObservable(vt, routerId, log)
        .map[Config](makeFunc1(arpCacheCreated))
    private lazy val loadBalancerObservable = Observable
        .merge(loadBalancerSubject)
        .map[Config](makeFunc1(loadBalancerUpdated))

    // The output device observable for the router mapper.
    //                on VT scheduler
    //                +----------------------------+  +--------------------+
    // store[Router]->| onCompleted(routerDeleted) |->| map(routerUpdated) |-+
    //                +----------------------------+  +--------------------+ |
    //         Add/remove ports                         |  |  |  |           |
    //         +----------------------------------------+  |  |  |           |
    //         |              +--------------------+       |  |  |           |
    // Obs[Obs[RouteUpdate]]->| map(routesUpdated) |--+    |  |  |           |
    //                        +--------------------+  |    |  |  |           |
    //         Add/remove routes                      |    |  |  |           |
    //         +-------------------------------------------+  |  |           |
    //         |              +--------------------+  |       |  |           |
    // Obs[Obs[RouteUpdate]]->| map(routesUpdated) |--+       |  |           |
    //                        +--------------------+  |       |  |           |
    //         Add/remove load balancer               |       |  |           |
    //         +----------------------------------------------+  |           |
    //         |                           +--------------------------+      |
    // Obs[Obs[LoadBalancer]]------------->| map(loadBalancerUpdated) |------+
    //                                     +--------------------------+      |
    //         Create ARP cache                       |          |           |
    //         +-------------------------------------------------+           |
    //         |      +----------------------+        |                      |
    // Obs[ArpCache]->| map(arpCacheCreated) |-------------------------------+
    //                +----------------------+        |                      |
    //                                  +-------------+                      |
    //                                  |  +--------------------------+      |
    // Routing table: Obs[RouteUpdate] -+->| map(routingTableUpdated) |--+---+
    //                                     +--------------------------+  |
    // +-----------------------------------------------------------------+
    // |  +-----------------+  +-----------------------+  +------------------+
    // +->| takeUntil(mark) |->| filter(isRouterReady) |->| map(buildRouter) |->
    //    +-----------------+  +-----------------------+  +------------------+
    //                                                          SimulationRouter
    protected override lazy val observable: Observable[SimulationRouter] =
        Observable.merge(arpTableObservable,
                         portRoutesObservable,
                         routesObservable,
                         traceChainObservable.map[Config](
                             makeFunc1(traceChainUpdated)),
                         chainsTracker.refsObservable.map[Config](makeFunc1(refUpdated)),
                         mirrorsTracker.refsObservable.map[Config](makeFunc1(refUpdated)),
                         loadBalancerObservable,
                         routerObservable)
            .takeUntil(mark)
            .filter(makeFunc1(isRouterReady))
            .map[SimulationRouter](makeFunc1(buildRouter))

    /**
     * Indicates the router device is ready, when the mapper has received all
     * of the following: the router's configuration, the ARP table, the router's
     * load-balancer (if any), and the states for all router's ports are ready.
     */
    private def isRouterReady(cfg: Config): JBoolean = {
        ready = (config ne null) &&
                (arpCache ne null) &&
                (if (loadBalancer ne null) loadBalancer.isReady else true) &&
                ports.forall(_._2.isReady) && localRoutes.forall(_._2.isReady) &&
                chainsTracker.areRefsReady && mirrorsTracker.areRefsReady && isTracingReady
        log.debug("Router ready: {} ", Boolean.box(ready))
        ready
    }

    /**
     * This method is called when the router is deleted. It triggers a
     * completion of the device observable, by completing all ports subjects,
     * load-balancer subject, and routing table broker observable.
     */
    private def routerDeleted(): Unit = {
        log.debug("Router deleted")
        assertThread()

        // Complete the mapper observables.
        completeTraceChain()
        chainsTracker.completeRefs()
        mirrorsTracker.completeRefs()
        mark.onCompleted()
        if (arpCache ne null) {
            arpCache.close()
        }
    }

    /**
     * A map method that processes updates from the topology router observable,
     * and returns the router configuration. The method examines any changes
     * in the router's ports, and load-balancer and updates the corresponding
     * observables.
     *                 +---------------+
     * store[Router]-->| routerUpdated |-->Observable[Config]
     *                 +---------------+
     */
    private def routerUpdated(router: TopologyRouter): Config = {
        assertThread()

        val preInFilterMirrors = new java.util.ArrayList[UUID]()
        preInFilterMirrors.addAll(router.getInboundMirrorIdsList.asScala.map(_.asJava).asJava)
        val postOutFilterMirrors = new java.util.ArrayList[UUID]()
        postOutFilterMirrors.addAll(router.getOutboundMirrorIdsList.asScala.map(_.asJava).asJava)

        val infilters = new JArrayList[UUID](2)
        val outfilters = new JArrayList[UUID](2)
        if (router.hasLocalRedirectChainId) {
            infilters.add(router.getLocalRedirectChainId)
        }
        if (router.hasInboundFilterId) {
            infilters.add(router.getInboundFilterId)
        }
        if (router.hasForwardChainId) {
            outfilters.add(router.getForwardChainId)
        }
        if (router.hasOutboundFilterId) {
            outfilters.add(router.getOutboundFilterId)
        }

        // Create the router configuration.
        val cfg = Config(
            if (router.hasAdminStateUp) router.getAdminStateUp else false,
            infilters, outfilters,
            if (router.hasLoadBalancerId) router.getLoadBalancerId else null,
            preInFilterMirrors,
            postOutFilterMirrors)
        log.debug("Router updated: {}", cfg)

        val portIds = router.getPortIdsList.asScala.map(_.asJava).toSet
        val routeIds = router.getRouteIdsList.asScala.map(_.asJava).toSet

        // Complete the observables for the ports no longer part of this router,
        // and notify the deletion route for the port state.
        for ((portId, portState) <- ports.toList if !portIds.contains(portId)) {
            val routeUpdates = portState.complete()
            ports -= portId
            routingTableUpdated(routeUpdates)
        }

        // Create observables for the new ports of this router, and notify them
        // on the ports observable.
        val addedPorts = new mutable.MutableList[PortState]
        for (portId <- portIds if !ports.contains(portId)) {
            val portState = new PortState(portId, vniToPort, vt, log)
            ports += portId -> portState
            addedPorts += portState
        }
        for (portState <- addedPorts) {
            portRoutesSubject onNext portState.observable
        }

        // Request trace chain be built if necessary
        requestTraceChain(router.getTraceRequestIdsList
                                .asScala.map(_.asJava).toList)

        // Request the chains for this router.
        chainsTracker.requestRefs(infilters.asScala ++ outfilters.asScala :_*)

        // Request the mirrors for this router.
        mirrorsTracker.requestRefs(
            router.getInboundMirrorIdsList.asScala.map(_.asJava) ++
            router.getOutboundMirrorIdsList.asScala.map(_.asJava) :_*)

        // Complete the previous load-balancer state, if any and different from
        // the current one.
        if ((loadBalancer ne null) &&
            (loadBalancer.loadBalancerId != cfg.loadBalancer)) {
            loadBalancer.complete()
            loadBalancer = null
        }
        // Create a new load-balancer state and notify its observable on the
        // load-balancer subject.
        if ((loadBalancer eq null) && (cfg.loadBalancer ne null)) {
            loadBalancer = new LoadBalancerState(cfg.loadBalancer, log)
            loadBalancerSubject onNext loadBalancer.observable
        }

        // Remove any local routes no longer part of the router's configuration
        for ((routeId, routeState) <- localRoutes.toList
             if !routeIds.contains(routeId)) {
            localRoutes -= routeId
            if (routeState.isReady) {
                routingTableUpdated(RouteUpdates(EmptyRouteSet,
                                                 Set(routeState.route)))
            }
            routeState.complete()
        }
        // Add any routes present in the router's configuration, and not part of
        // the local routes map.
        val addedRoutes = new mutable.MutableList[RouteState]
        for (routeId <- routeIds if !localRoutes.contains(routeId)) {
            val routeState = new RouteState(routeId, vt, log)
            localRoutes += routeId -> routeState
            addedRoutes += routeState
        }
        for (routeState <- addedRoutes) {
            routesSubject onNext routeState.observable
        }

        // Update the router configuration.
        config = cfg
        config
    }

    private def refUpdated(obj: AnyRef): Config = {
        assertThread()
        log.debug("Router reference updated: {}", obj)
        config
    }

    private def traceChainUpdated(chainId: Option[UUID]): Config = {
        log.debug("Trace chain updated: {}", chainId)
        traceChain = chainId
        config
    }

    /**
     * Processes route updates emitted by the router's routing table.
     */
    private def routingTableUpdated(routeUpdates: RouteUpdates): Config = {
        log.debug("Routes added {} removed {}", routeUpdates.added,
                  routeUpdates.removed)
        assertThread()
        // Update the current routes.
        routes ++= routeUpdates.added
        routes --= routeUpdates.removed
        vt.tellBackChannel(InvalidateFlows(
            id, routeUpdates.added, routeUpdates.removed))
        config
    }

    /**
     * Called when the ARP cache of the router is created, and it creates
     * the corresponding ARP table.
     *                        +-----------------+
     * Observable[ArpCache]-->| arpCacheCreated |-->Observable[Config]
     *                        +-----------------+
     *                                 |
     *                         Create ARP table
     */
    private def arpCacheCreated(newArpCache: ArpCache): Config = {
        log.debug("ARP cache updated")
        if (arpCache ne null) {
            throw new DeviceMapperException(classOf[SimulationRouter], routerId,
                                            "Router ARP table is already set")
        }
        arpCache = newArpCache
        config
    }

    /** Processes updates to the router's load balancer. */
    private def loadBalancerUpdated(loadBalancer: LoadBalancer): Config = {
        assertThread()
        log.debug("Load balancer updated: {}", loadBalancer)
        config
    }

    /**
     * Maps the router's configuration, routing table, tag manager and ARP table
     * to a [[SimulationRouter]] device.
     */
    private def buildRouter(config: Config) : SimulationRouter = {
        assertThread()

        val config2 = traceChain match {
            case Some(t) =>
                val infilters = new JArrayList[UUID](2)
                infilters.add(t)
                infilters.addAll(config.inboundFilters)
                config.copy(inboundFilters = infilters)
            case None => config
        }

        val device = new SimulationRouter(
            routerId,
            config2,
            new RouterRoutingTable(routes),
            tagManager,
            vniToPort.asJava,
            arpCache,
            vt.config.fip64)
        log.debug("Build router: {} {}", device, routes)

        device
    }
}
