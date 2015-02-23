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
import java.util
import java.util.UUID

import javax.annotation.concurrent.{NotThreadSafe, ThreadSafe}

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem

import com.google.common.annotations.VisibleForTesting

import rx.Observable.OnSubscribe
import rx.subjects.PublishSubject
import rx.subscriptions.Subscriptions
import rx.{Observable, Subscriber}

import org.midonet.cluster.models.Topology.{Router => TopologyRouter}
import org.midonet.cluster.state.StateStorage.ReplicatedRouteSet
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.layer3.{InvalidationTrie, IPv4RoutingTable, Route}
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.{ArpTable => SimulationArpTable, Router => SimulationRouter, LoadBalancer, ArpTableImpl}
import org.midonet.midolman.simulation.Router.{RoutingTable, Config, TagManager}
import org.midonet.midolman.state.ReplicatedSet.Watcher
import org.midonet.midolman.state.{ArpCache, ArpCacheEntry, ArpCacheUpdate, ReplicatedMap, StateAccessException}
import org.midonet.midolman.topology.DeviceMapper.MapperState
import org.midonet.midolman.topology.RouterMapper._
import org.midonet.odp.FlowMatch
import org.midonet.packets.{MAC, IPAddr, IPv4Addr}
import org.midonet.sdn.flows.FlowTagger.{tagForDestinationIp, tagForRoute}
import org.midonet.util.functors._

object RouterMapper {

    /** Represents a set of route updates. */
    case class RouteUpdates(added: Set[Route], removed: Set[Route])

    /** Represents an update for the router invalidation trie. */
    private[topology]
    case class RouterInvalidationTrieUpdate(dstAddress: IPAddr, count: Int)

    /**
     * Stores the state for a router load balancer, and exposes an
     * [[Observable]] that emits updates for this load balancer. The observable
     * completes either when the load balancer is deleted, or when calling the
     * complete() method, which is used to signal that the load balancer is no
     * longer used by the router.
     */
    private class LoadBalancerState(val loadBalancerId: UUID) {

        private var currentLoadBalancer: LoadBalancer = null
        private val mark = PublishSubject.create[LoadBalancer]

        val observable = VirtualTopology.observable[LoadBalancer](loadBalancerId)
            .doOnNext(makeAction1(currentLoadBalancer = _))
            .takeUntil(mark)

        /** Completes the observable corresponding to this load balancer
          * state. */
        def complete(): Unit = mark.onCompleted()
        /** Get the load balancer for this load balancer state. */
        def loadBalancer: LoadBalancer = currentLoadBalancer
        /** Indicates whether the load balancer state has received the load
          * balancer data. */
        def isReady: Boolean = currentLoadBalancer ne null
    }

    /**
     * Implements the [[OnSubscribe]] interface for an observable that emits
     * routing table updates for a given router. It wraps an underlying
     * [[ReplicatedRouteSet]] replicated set, which is initialized on the first
     * subscription, and started whenever there exists at least one subscriber.
     * The underlying table is stopped when all current subscribers unsubscribe.
     */
    private class OnSubscribeRoutingTable(vt: VirtualTopology, routerId: UUID)
        extends OnSubscribe[RouteUpdates] with Watcher[Route]
        with MidolmanLogging {

        override def logSource =
            s"org.midonet.devices.router.router-$routerId.routing-table"

        private val subject = PublishSubject.create[RouteUpdates]()
        private val retryHandler = makeRunnable(start())

        private var state = MapperState.Latent
        private var table: ReplicatedRouteSet = null

        /** Called when a [[rx.Subscriber]] subscribes to the corresponding
          * [[Observable]]. */
        @ThreadSafe
        protected override def call(child: Subscriber[_ >: RouteUpdates])
        : Unit = {
            synchronized {
                log.debug("Subscribing to routing table")
                start()
                child.add(Subscriptions.create(makeAction0(unsubscribe())))
                subject.subscribe(child)
            }
        }

        /** Processes notifications from the underlying replicated set. */
        override def process(added: util.Collection[Route],
                             removed: util.Collection[Route]): Unit = {
            subject.onNext(RouteUpdates(added.asScala.toSet,
                                        removed.asScala.toSet))
        }

        /** Initializes and starts the underlying routing table. The method
          * can be called when a new subscription occurs, or when retrying
          * to initialize the underlying map after a previous storage error. */
        @NotThreadSafe
        private def start(): Unit = {
            log.debug("Starting the routing table")
            if (!state.isInitialized) {
                try {
                    table = vt.state.routerRoutingTable(routerId)
                    table.addWatcher(this)
                    state = MapperState.Stopped
                } catch {
                    case e: StateAccessException =>
                        log.warn("Initializing the routing table failed: " +
                                 "retrying.", e)
                        vt.connectionWatcher.handleError(routerId.toString,
                                                         retryHandler, e)
                }
            }
            if (state.isInitialized && !state.isStarted) {
                table.start()
                state = MapperState.Started
            }
        }

        /** Called when a subscribers unsubscribes. The method stops the
          * underlying routing table if there are no more subscribers. */
        @ThreadSafe
        private def unsubscribe(): Unit = {
            synchronized {
                log.debug("Unsubscribing from routing table")
                if (state.isStarted && !subject.hasObservers) {
                    table.stop()
                    state = MapperState.Stopped
                }
            }
        }
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
     * Provides an implmentation for a router's [[RoutingTable]], wrapping an
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

    /** Creates a single host route for an IPv4 destination. */
    private def createSingleHostRoute(dstAddress: IPAddr): Route = {
        new Route(0, 0, dstAddress.asInstanceOf[IPv4Addr].toInt, 32, null, null,
                  0, 0, null, null)
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
    private val routes = new mutable.HashSet[Route]
    private var arpTable: SimulationArpTable = null

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
    private lazy val invalidationTrieSubject =
        PublishSubject.create[RouterInvalidationTrieUpdate]

    private lazy val loadBalancerSubject =
        PublishSubject.create[Observable[LoadBalancer]]
    private var loadBalancerState: LoadBalancerState = null

    private lazy val routerObservable =
        vt.store.observable(classOf[TopologyRouter], routerId)
            .observeOn(vt.vtScheduler)
            .doOnCompleted(makeAction0(routerDeleted()))
            .map[Config](makeFunc1(routerUpdated))
    private lazy val routingTableObservable = Observable
        .create(new OnSubscribeRoutingTable(vt, routerId))
        .map[Config](makeFunc1(routesUpdated))
    private lazy val arpTableObservable = Observable
        .create(new OnSubscribeArpCache(vt, routerId))
        .map[Config](makeFunc1(arpCacheUpdated))

    protected override lazy val observable: Observable[SimulationRouter] =
        Observable.merge(routingTableObservable,
                         arpTableObservable,
                         routerObservable)
            .filter(makeFunc1(isRouterReady))
            .map[SimulationRouter](makeFunc1(deviceUpdated))

    @VisibleForTesting
    private[topology] def invalidationTrieObservable =
        invalidationTrieSubject.asObservable

    private def routerDeleted(): Unit = {
        log.debug("Router deleted")
        assertThread()

        if (arpTable ne null) arpTable.stop()
    }

    private def routerUpdated(router: TopologyRouter): Config = {
        assertThread()

        // Create the router configuration.
        val cfg = Config(
            if (router.hasAdminStateUp) router.getAdminStateUp else false,
            if (router.hasInboundFilterId) router.getInboundFilterId else null,
            if (router.hasOutboundFilterId) router.getOutboundFilterId else null,
            if (router.hasLoadBalancerId) router.getLoadBalancerId else null)
        log.debug("Router updated: {}", cfg)

        // Complete the previous load balancer state, if any and different from
        // the current one.
        if ((loadBalancerState ne null) &&
            (loadBalancerState.loadBalancerId != cfg.loadBalancer)) {
            loadBalancerState.complete()
            loadBalancerState = null
        }
        // Create a new load balancer state and notify its observable on the
        // load balancer subject.
        if ((loadBalancerState eq null) && (cfg.loadBalancer ne null)) {
            loadBalancerState = new LoadBalancerState(cfg.loadBalancer)
            loadBalancerSubject onNext loadBalancerState.observable
        }

        // Update the router configuration.
        config = cfg
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

    private def arpCacheUpdated(arpCache: ArpCache): Config = {
        log.debug("ARP cache updated")
        assertThread()

        if (arpTable ne null) {
            throw new DeviceMapperException(classOf[SimulationRouter], routerId,
                                            "Router ARP table is already set")
        }

        arpTable = new ArpTableImpl(arpCache, vt.config, invalidateFlowsByIp)
        arpTable.start()
        config
    }

    /**
     * Indicates the router device is ready.
     */
    private def isRouterReady(cfg: Config): JBoolean = {
        val ready: Boolean =
            (config ne null) && (arpTable ne null) &&
            (if (loadBalancerState ne null) loadBalancerState.isReady else true)
        log.debug("Router ready: {}", Boolean.box(ready))
        ready
    }

    private def deviceUpdated(config: Config)
    : SimulationRouter = {
        log.debug("Updating router device")
        assertThread()

        new SimulationRouter(
            routerId,
            config,
            new RouterRoutingTable(routes),
            tagManager,
            arpTable
            )
    }

    /** Invalidated the flow for the given IP address. */
    private def invalidateFlowsByIp(ipAddr: IPv4Addr, oldMac: MAC, newMac: MAC)
    : Unit = {
        vt.invalidate(tagForDestinationIp(routerId, ipAddr))
    }

    /**
     * Adds a tag for a destination address in the router's invalidation trie.
     */
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
