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
import java.util.UUID

import javax.annotation.concurrent.ThreadSafe

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem

import com.google.common.annotations.VisibleForTesting

import rx.Observable.OnSubscribe
import rx.subjects.PublishSubject
import rx.{Observable, Subscriber}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Route => TopologyRoute, Router => TopologyRouter}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.layer3.{IPv4RoutingTable, InvalidationTrie, Route}
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.Router.{Config, RoutingTable, TagManager}
import org.midonet.midolman.simulation.{ArpTable => SimulationArpTable, ArpTableImpl, LoadBalancer, Router => SimulationRouter}
import org.midonet.midolman.state.{ArpCache, ArpCacheEntry, ArpCacheUpdate, ReplicatedMap, StateAccessException}
import org.midonet.midolman.topology.RouterMapper._
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.odp.FlowMatch
import org.midonet.packets.{IPAddr, IPv4Addr, MAC}
import org.midonet.sdn.flows.FlowTagger.{tagForDestinationIp, tagForRoute}
import org.midonet.util.functors._

object RouterMapper {

    /** Represents an update for the router invalidation trie. */
    private[topology]
    case class RouterInvalidationTrieUpdate(dstAddress: IPAddr, count: Int)

    /** Represents a route update. */
    private[topology]
    case class RouteUpdate(added: Option[Route], removed: Option[Route]) {
        def nonEmpty = added.nonEmpty || removed.nonEmpty
        override def toString = s"[added=$added removed=$removed]"
    }

    /**
     * Stores the state for a router port, and exposes an [[Observable]] that
     * emits updates for this port. The observable completes either when the
     * port is deleted, or when calling the complete() method, which is used to
     * signal that the port is no longer used by the router.
     */
    private class PortState(val portId: UUID, vt: VirtualTopology) {

        private var currentPort: RouterPort = null
        private var currentRoute: Route = null
        private val mark = PublishSubject.create[RouteUpdate]

        private val routes = new mutable.HashMap[UUID, RouteState]
        private val routesSubject = PublishSubject
            .create[Observable[RouteUpdate]]
        private val routesRemovalSubject = PublishSubject.create[RouteUpdate]

        private val portObservable = VirtualTopology
            .observable[RouterPort](portId)
            .map[RouteUpdate](makeFunc1(portUpdated))
        private val routesObservable = Observable.merge(routesSubject)

        val observable = Observable.merge(routesRemovalSubject,
                                          routesObservable,
                                          portObservable)
            .filter(makeFunc1(_.nonEmpty))
            .distinctUntilChanged()
            .takeUntil(mark)

        /** Completes the observable corresponding to this port state, and to
          * each port route state. */
        def complete(): Unit = {
            routes.foreach(_._2.complete())
            routesSubject.onCompleted()
            routesRemovalSubject.onCompleted()
            mark.onCompleted()
        }
        /** Indicates whether the port state has received the port data. */
        def isReady: Boolean = currentPort ne null
        /** Gets the route update for when this port is deleted. */
        def deletionRouteUpdates = RouteUpdate(None, Option(currentRoute))

        /**
         * A method called when the router port has changed.
         * Generates a port route update when the port changes:
         * - if the port is exterior, create a route if the port is active
         * - if the port is interior, always create a route
         * - otherwise, don't create a route
         */
        private def portUpdated(port: RouterPort): RouteUpdate = {

            // Remove the state for the routes that are no longer part of this
            // port.
            for ((routeId, route) <- routes.toList
                 if !port.routeIds.contains(routeId)) {
                routes -= routeId
                routesRemovalSubject onNext RouteUpdate(None,
                                                        Option(route.route))
                route.complete()
            }

            // Add the state for the new routes of this port, and notify its
            // observable on the routes observable.
            for (routeId <- port.routeIds if !routes.contains(routeId)) {
                val routeState = new RouteState(routeId, vt)
                routes += routeId -> routeState
                routesSubject onNext routeState.observable
            }

            // Compute the local port route.
            val route = if (port.isExterior) {
                if (port.isActive) createLocalRoute(port) else null
            } else if (port.isInterior) {
                createLocalRoute(port)
            } else null

            val update =
                if (route != currentRoute) RouteUpdate(Option(route),
                                                       Option(currentRoute))
                else RouteUpdate(None, None)

            currentPort = port
            currentRoute = route

            update
        }
    }

    /**
     * Stores the state for a route, and exposes an [[Observable]] that emits
     * updates when the route has changed. The observable completes either when
     * the route is deleted, or when calling the complete() method, which is
     * used to signal that the route is no longer used by the router or port.
     */
    private class RouteState(val routeId: UUID, vt: VirtualTopology) {

        private var currentRoute: Route = null
        private val mark = PublishSubject.create[RouteUpdate]

        val observable = vt.store.observable(classOf[TopologyRoute], routeId)
            .observeOn(vt.vtScheduler)
            .map[RouteUpdate](makeFunc1(routeUpdated))
            .filter(makeFunc1(_.nonEmpty))
            .distinctUntilChanged()
            .takeUntil(mark)

        /** Completes the observable corresponding to this route state. */
        def complete(): Unit = mark.onCompleted()
        /** Indicates whether the route state has received the route data. */
        def isReady: Boolean = currentRoute ne null
        /** Gets the last route published by this route state. */
        def route: Route = currentRoute

        /** Generates a route update when the route changes. */
        private def routeUpdated(tr: TopologyRoute): RouteUpdate = {
            val route = ZoomConvert.fromProto(tr, classOf[Route])
            val update =
                if (route != currentRoute) RouteUpdate(Option(route),
                                                       Option(currentRoute))
                else RouteUpdate(None, None)

            currentRoute = route

            update
        }
    }

    /**
     * Stores the state for a router load-balancer, and exposes an
     * [[Observable]] that emits updates for this load-balancer. The observable
     * completes either when the load-balancer is deleted, or when calling the
     * complete() method, which is used to signal that the load-balancer is no
     * longer used by the router.
     */
    private class LoadBalancerState(val loadBalancerId: UUID) {

        private var currentLoadBalancer: LoadBalancer = null
        private val mark = PublishSubject.create[LoadBalancer]

        val observable = VirtualTopology.observable[LoadBalancer](loadBalancerId)
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
     * Implements the [[OnSubscribe]] interface for an observable that emits
     * only one notification with the current ARP cache for the given router
     * and then completes. The observable may emit the notification either at
     * the moment of subscription, or later if the connection to storage fails.
     */
    private class OnSubscribeArpCache(vt: VirtualTopology, routerId: UUID)
        extends OnSubscribe[ArpCache] with MidolmanLogging {

        override def logSource =
            s"org.midonet.devices.router.router-$routerId.arp-cache"

        @ThreadSafe
        protected override def call(child: Subscriber[_ >: ArpCache]): Unit = {
            log.debug("Subscribing to the ARP cache")
            try {
                val arpCache = new RouterArpCache(vt, routerId)
                child.onNext(arpCache)
                child.onCompleted()
            } catch {
                case e: StateAccessException =>
                    // If initializing the ARP cache fails, use the connection
                    // watcher to retry emitting the ARP cache notification for
                    // the subscriber.
                    vt.connectionWatcher.handleError(
                        routerId.toString, makeRunnable { call(child) }, e)
            }
        }
    }

    /**
     * Implements the [[ArpCache]] for a [[SimulationRouter]].
     */
    @throws[StateAccessException]
    private class RouterArpCache(vt: VirtualTopology,
                                 override val routerId: UUID)
        extends ArpCache with MidolmanLogging {

        override def logSource =
            s"org.midonet.devices.router.router-$routerId.arp-cache"

        private val subject = PublishSubject.create[ArpCacheUpdate]()
        private val watcher =
            new ReplicatedMap.Watcher[IPv4Addr, ArpCacheEntry] {
                override def processChange(ipAddr: IPv4Addr,
                                           oldEntry: ArpCacheEntry,
                                           newEntry: ArpCacheEntry): Unit = {

                    if ((oldEntry eq null) && (newEntry eq null)) return
                    if ((oldEntry ne null) && (newEntry ne null) &&
                        (oldEntry.macAddr == newEntry.macAddr)) return

                    subject.onNext(ArpCacheUpdate(
                        ipAddr,
                        if (oldEntry ne null) oldEntry.macAddr else null,
                        if (newEntry ne null) newEntry.macAddr else null
                        ))
                }
            }
        private val arpTable = vt.state.routerArpTable(routerId)

        arpTable.addWatcher(watcher)
        arpTable.start()

        /** Gets an entry from the underlying ARP table. The request only
          * queries local state. */
        override def get(ipAddr: IPv4Addr): ArpCacheEntry = arpTable.get(ipAddr)
        /** Adds an ARP entry to the underlying ARP table. The operation is
          * scheduled on the topology IO executor. */
        override def add(ipAddr: IPv4Addr, entry: ArpCacheEntry): Unit = {
            vt.executeIo {
                try {
                    arpTable.put(ipAddr, entry)
                } catch {
                    case e: Exception =>
                        log.error("Failed to add ARP entry IP: {} Entry: {}",
                                  ipAddr, entry, e)
                }
            }
        }
        /** Removes an ARP entry from the underlying ARP table. The operation is
          * scheduled on the topology IO executor. */
        override def remove(ipAddr: IPv4Addr): Unit = {
            vt.executeIo {
                try {
                    arpTable.removeIfOwner(ipAddr)
                } catch {
                    case e: Exception =>
                        log.error("Failed to remove ARP entry for IP: {}",
                                  ipAddr, e)
                }
            }
        }
        /** Observable that emits ARP cache updates. */
        override def observable: Observable[ArpCacheUpdate] =
            subject.asObservable()
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

        override def lookup(flowMatch: FlowMatch): Iterable[Route] = {
            ipv4RoutingTable.lookup(
                flowMatch.getNetworkSrcIP.asInstanceOf[IPv4Addr],
                flowMatch.getNetworkDstIP.asInstanceOf[IPv4Addr]).asScala
        }
    }

    /** Creates a single router for a router local port. */
    @throws[DeviceMapperException]
    private def createLocalRoute(port: RouterPort): Route = {
        port.portIp match {
            case addr: IPv4Addr =>
                new Route(0, 0, addr.toInt, 32, NextHop.LOCAL, port.id,
                          Route.NO_GATEWAY, 0, null, port.routerId)
            case _ => throw new DeviceMapperException(
                s"Port address not supported ${port.portIp}")
        }
    }

    /** Creates a single host route for an IPv4 destination. */
    @throws[DeviceMapperException]
    private def createSingleHostRoute(dstAddress: IPAddr): Route = {
        dstAddress match {
            case addr: IPv4Addr =>
                new Route(0, 0, addr.toInt, 32, null, null, 0, 0, null, null)
            case _ => throw new DeviceMapperException(
                s"Destination address not supported $dstAddress")
        }
    }
}

/**
 * A class that implements the [[DeviceMapper]] for a [[SimulationRouter]].
 */
final class RouterMapper(routerId: UUID, vt: VirtualTopology)
                        (implicit actorSystem: ActorSystem)
    extends DeviceMapper[SimulationRouter](routerId, vt) {

    override def logSource = s"org.midonet.devices.router.router-$routerId"

    private implicit val ec: ExecutionContext = actorSystem.dispatcher

    private var config: Config = null
    private val ports = new mutable.HashMap[UUID, PortState]
    private var loadBalancer: LoadBalancerState = null
    // Stores all routes received via notifications from the replicated routing
    // table.
    private val routes = new mutable.HashSet[Route]
    // Stores routes received via the router's configuration in storage.
    private val localRoutes = new mutable.HashMap[UUID, RouteState]
    private var arpTable: SimulationArpTable = null

    private val routingTableBroker = new RoutingTableBroker(vt, routerId)

    // This trie stores the tag that represents a destination IP, to be used for
    // flow invalidation when a route is added or removed
    private val dstIpTagTrie = new InvalidationTrie
    private val tagToFlowCount = new mutable.HashMap[IPAddr, Int]

    // Provides an implementation of the tag manager for the current router
    private val tagManager = new TagManager {
        override def addTag(dstAddress: IPAddr): Unit = vt.executeVt {
            RouterMapper.this.addTag(dstAddress)
        }
        override def getFlowRemovalCallback(dstAddress: IPAddr): Callback0 = {
            makeCallback0(vt.executeVt { removeTag(dstAddress) })
        }
    }

    private lazy val mark =
        PublishSubject.create[Config]
    private lazy val portsSubject =
        PublishSubject.create[Observable[RouteUpdate]]
    private lazy val routesSubject =
        PublishSubject.create[Observable[RouteUpdate]]
    private lazy val loadBalancerSubject =
        PublishSubject.create[Observable[LoadBalancer]]
    private lazy val invalidationTrieSubject =
        PublishSubject.create[RouterInvalidationTrieUpdate]

    private lazy val routerObservable =
        vt.store.observable(classOf[TopologyRouter], routerId)
            .observeOn(vt.vtScheduler)
            .doOnCompleted(makeAction0(routerDeleted()))
            .map[Config](makeFunc1(routerUpdated))
    private lazy val portsObservable = Observable
        .merge(portsSubject)
        .map[Config](makeFunc1(routeUpdated))
    private lazy val routesObservable = Observable
        .merge(routesSubject)
        .map[Config](makeFunc1(routeUpdated))
    private lazy val routingTableObservable = Observable
        .create(routingTableBroker)
        .observeOn(vt.vtScheduler)
        .map[Config](makeFunc1(routesUpdated))
    private lazy val arpTableObservable = Observable
        .create(new OnSubscribeArpCache(vt, routerId))
        .map[Config](makeFunc1(arpCacheCreated))

    protected override lazy val observable: Observable[SimulationRouter] =
        Observable.merge(routingTableObservable,
                         arpTableObservable,
                         portsObservable,
                         routesObservable,
                         routerObservable)
            .takeUntil(mark)
            .filter(makeFunc1(isRouterReady))
            .map[SimulationRouter](makeFunc1(deviceUpdated))

    @VisibleForTesting
    private[topology] def invalidationTrieObservable =
        invalidationTrieSubject.asObservable

    /**
     * Indicates the router device is ready, when the mapper has received all
     * of the following: the router's configuration, the ARP table, the router's
     * load-balancer (if any), and the states for all router's ports are ready.
     */
    private def isRouterReady(cfg: Config): JBoolean = {
        val ready: Boolean =
            (config ne null) && (arpTable ne null) &&
            (if (loadBalancer ne null) loadBalancer.isReady else true) &&
            ports.forall(_._2.isReady)
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
        mark.onCompleted()
        invalidationTrieSubject.onCompleted()

        // Stop the ARP table.
        if (arpTable ne null) arpTable.stop()
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

        // Create the router configuration.
        val cfg = Config(
            if (router.hasAdminStateUp) router.getAdminStateUp else false,
            if (router.hasInboundFilterId) router.getInboundFilterId else null,
            if (router.hasOutboundFilterId) router.getOutboundFilterId else null,
            if (router.hasLoadBalancerId) router.getLoadBalancerId else null)
        log.debug("Router updated: {}", cfg)

        val portIds = router.getPortIdsList.asScala.map(_.asJava).toSet
        val routeIds = router.getRouteIdsList.asScala.map(_.asJava).toSet

        // Complete the observables for the ports no longer part of this router,
        // and notify the deletion route for the port state.
        for ((portId, portState) <- ports.toList if !portIds.contains(portId)) {
            portState.complete()
            portsSubject onNext Observable.just(portState.deletionRouteUpdates)
            ports -= portId
        }

        // Create observables for the new ports of this router, and notify them
        // on the ports observable.
        for (portId <- portIds if !ports.contains(portId)) {
            val portState = new PortState(portId, vt)
            ports += portId -> portState
            portsSubject onNext portState.observable
        }

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
            loadBalancer = new LoadBalancerState(cfg.loadBalancer)
            loadBalancerSubject onNext loadBalancer.observable
        }

        val routesToRemove = new mutable.HashSet[Route]
        // Remove any local routes no longer part of the router's configuration
        for ((routeId, route) <- localRoutes.toList if !routeIds.contains(routeId)) {
            localRoutes -= routeId
            routesToRemove += route.route
            route.complete()
        }
        // Add any routes present in the router's configuration, and not part of
        // the local routes map.
        for (routeId <- routeIds if !localRoutes.contains(routeId)) {
            val routeState = new RouteState(routeId, vt)
            localRoutes += routeId -> routeState
            routesSubject onNext routeState.observable
        }
        routingTableBroker.update(Set.empty, routesToRemove.toSet)

        // Update the router configuration.
        config = cfg
        config
    }

    /**
     * Handles route updates for router and ports, by adding them to the
     * router's replicated routing table via the routing table broker. The
     * method returns the router's current configuration to propagate the
     * notifications to the device observable.
     *                     +-------------+
     * Obs[RouteUpdate]--->| portUpdated |--->Obs[Config]
     *                     +-------------+
     */
    private def routeUpdated(routeUpdate: RouteUpdate): Config = {
        log.debug("Port route update {}", routeUpdate)
        assertThread()

        if (routeUpdate.nonEmpty) {
            routingTableBroker.update(routeUpdate.added.toSet,
                                      routeUpdate.removed.toSet)
        }

        config
    }

    /**
     * Processes route updates emitted by the router's routing table.
     */
    private def routesUpdated(routeUpdates: RouteUpdates): Config = {
        log.debug("Routes added {} removed {}", routeUpdates.added,
                  routeUpdates.removed)
        assertThread()

        // Update the current routes.
        routes ++= routeUpdates.added
        routes --= routeUpdates.removed

        // Invalidate the flows for the removed routes.
        for (route <- routeUpdates.removed) {
            vt.invalidate(tagForRoute(route))
        }
        // Invalidate the flows for the added routes
        for (route <- routeUpdates.added) {
            log.debug("Projecting added route {}", route)
            val subTree = dstIpTagTrie.projectRouteAndGetSubTree(route)
            val ipsToInvalidate =
                InvalidationTrie.getAllDescendantsIpDestination(subTree).asScala
            log.debug("Destination IP addresses to invalidate: {}",
                      ipsToInvalidate)

            for (ip <- ipsToInvalidate) {
                vt.invalidate(tagForDestinationIp(routerId, ip))
            }
        }

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
    private def arpCacheCreated(arpCache: ArpCache): Config = {
        log.debug("ARP cache updated")
        if (arpTable ne null) {
            throw new DeviceMapperException(classOf[SimulationRouter], routerId,
                                            "Router ARP table is already set")
        }

        arpTable = new ArpTableImpl(arpCache, vt.config, invalidateFlowsByIp)
        arpTable.start()
        config
    }

    /**
     * Maps the router's configuration, routing table, tag manager and ARP table
     * to a [[SimulationRouter]] device.
     */
    private def deviceUpdated(config: Config) : SimulationRouter = {
        assertThread()

        val device = new SimulationRouter(
            routerId,
            config,
            new RouterRoutingTable(routes),
            tagManager,
            arpTable
            )
        log.debug("Router ready: {}", device)

        device
    }

    /** Invalidated the flow for the given IP address. */
    private def invalidateFlowsByIp(ipAddr: IPv4Addr, oldMac: MAC, newMac: MAC)
    : Unit = {
        vt.invalidate(tagForDestinationIp(routerId, ipAddr))
    }

    /**
     * Adds a tag for a destination address in the router's invalidation trie.
     */
    @throws[DeviceMapperException]
    private def addTag(dstAddress: IPAddr): Unit = {
        log.debug("Add tag for destination address {}", dstAddress)
        assertThread()

        tagToFlowCount get dstAddress match {
            case Some(count) =>
                tagToFlowCount.update(dstAddress, count + 1)
                log.debug("Increased flow count for tag destination {} count {}",
                          dstAddress, Int.box(tagToFlowCount(dstAddress)))
            case None =>
                tagToFlowCount += dstAddress -> 1
                dstIpTagTrie.addRoute(createSingleHostRoute(dstAddress))
                log.debug("Added IP {} to invalidation trie", dstAddress)
        }

        invalidationTrieSubject.onNext(
            RouterInvalidationTrieUpdate(dstAddress, tagToFlowCount(dstAddress)))
    }

    /**
     * Removes a tag for a destination address from the router's invalidation
     * trie.
     */
    @throws[DeviceMapperException]
    private def removeTag(dstAddress: IPAddr): Unit = {
        log.debug("Remove tag for destination address {}", dstAddress)
        assertThread()

        tagToFlowCount get dstAddress match {
            case Some(count) if count > 1 =>
                tagToFlowCount.update(dstAddress, count - 1)
                log.debug("Decreased flow count for tag destination {} count " +
                          "{}", dstAddress, Int.box(tagToFlowCount(dstAddress)))
            case Some(_) =>
                tagToFlowCount remove dstAddress
                dstIpTagTrie deleteRoute createSingleHostRoute(dstAddress)
                log.debug("Removing destination {} from invalidation trie",
                          dstAddress)
            case None =>
                log.debug("Destination {} not found in the invalidation trie",
                          dstAddress)
        }
    }
}
