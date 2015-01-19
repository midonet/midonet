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
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import collection.JavaConversions._

import akka.actor.ActorSystem
import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger
import org.apache.zookeeper.CreateMode
import org.slf4j.LoggerFactory

import rx.Observable
import rx.subjects.BehaviorSubject

import org.midonet.cluster.client.{RouterBuilder, ArpCache}
import org.midonet.cluster.DataClient
import org.midonet.cluster.models.Topology.Router
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.guice.zookeeper.ZKConnectionProvider
import org.midonet.midolman.layer3.{InvalidationTrie, Route, IPv4RoutingTable}
import org.midonet.midolman.serialization.{SerializationException, Serializer}
import org.midonet.midolman.simulation.{Router => SimRouter, ArpTable => SimArpTable, ArpTableImpl}
import org.midonet.midolman.state.zkManagers.{RouterZkManager, RouteZkManager}
import org.midonet.midolman.state._
import org.midonet.midolman.topology.RouterManager.RouterInvTrieTagCountModified
import org.midonet.packets.{MAC, IPv4Addr, IPAddr}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.eventloop.Reactor
import org.midonet.util.functors._

class RouterMapper(id: UUID, vt: VirtualTopology, dataClient: DataClient,
                   routerMgr: RouterZkManager, routeMgr: RouteZkManager)
                      (implicit actorSystem: ActorSystem)

    extends VirtualDeviceMapper[SimRouter](id, vt)
    with DeviceWithChains {

    import context.dispatcher

    // TODO: Find a better way to avoid inheriting from two different 'log'
    override val log = Logger(LoggerFactory.getLogger(logSource))

    private class RouteWatcher(builder: RouterBuilder)
        extends ReplicatedSet.Watcher[Route] {

        def process(added: util.Collection[Route], removed: util.Collection[Route]) {
            import scala.collection.JavaConversions._
            for (rt <- removed) {
                builder.removeRoute(rt)
            }
            import scala.collection.JavaConversions._
            for (rt <- added) {
                builder.addRoute(rt)
            }
            log.debug("RouteWatcher - Routes added {}, routes removed {}. " +
                      "Notifying builder", added, removed)
            builder.build
        }
    }

    private class RouteEncoder {

        @Inject private var serializer: Serializer = null

        def encode(rt: Route): String = {
            try {
                new String(serializer.serialize(rt))
            }
            catch {
                case e: SerializationException =>
                    log.error("Could not serialize route {}", rt, e)
                    null
            }
        }

        def decode(str: String): Route = {
            try {
                serializer.deserialize(str.getBytes, classOf[Route])
            }
            catch {
                case e: SerializationException =>
                    log.error("Could not deserialize route {}", str, e)
                    null
            }
        }
    }

    protected[this] class ReplicatedRouteSet(d: Directory, mode: CreateMode,
                                             builder: RouterBuilder)
                                         extends ReplicatedSet[Route](d, mode) {

        private val encoder = new RouteEncoder

        this.addWatcher(new RouteWatcher(builder))

        protected def encode(item: Route): String = {
            encoder.encode(item)
        }

        protected def decode(str: String): Route = {
            encoder.decode(str)
        }
    }

    class ArpCacheImpl(arpTable: ArpTable, routerId: UUID)
        extends ArpCache
        with ReplicatedMap.Watcher[IPv4Addr, ArpCacheEntry] {

        arpTable.addWatcher(this)
        private val listeners = new util.LinkedHashSet[Callback3[IPv4Addr, MAC, MAC]]

        def getRouterId: UUID = {
            return routerId
        }

        def processChange(key: IPv4Addr, oldV: ArpCacheEntry,
                          newV: ArpCacheEntry) {
            if (oldV == null && newV == null) {
                return
            }
            if (newV != null && oldV != null) {
                if (newV.macAddr == null && oldV.macAddr == null) {
                    return
                }
                if (newV.macAddr != null && oldV.macAddr != null &&
                    (newV.macAddr == oldV.macAddr)) {
                    return
                }
            }
            listeners synchronized {
                import scala.collection.JavaConversions._
                for (cb <- listeners) {
                    cb.call(key, if ((oldV != null)) oldV.macAddr else null,
                            if ((newV != null)) newV.macAddr else null)
                }
            }
        }

        def get(ipAddr: IPv4Addr): ArpCacheEntry = {
            return arpTable.get(ipAddr)
        }

        def add(ipAddr: IPv4Addr, entry: ArpCacheEntry) {
            reactorLoop.submit(new Runnable {
                def run {
                    try {
                        arpTable.put(ipAddr, entry)
                   }
                   catch {
                       case e: Exception => {
                           log.error("Failed adding ARP entry. IP: {} MAC: {}",
                               Array[AnyRef](ipAddr, entry, e))
                       }
                   }
               }
           })
        }

        def remove(ipAddr: IPv4Addr) {
            reactorLoop.submit( new Runnable {
                def run {
                    try {
                        arpTable.removeIfOwner(ipAddr)
                   }
                   catch {
                       case e: Exception => {
                           log.error("Could not remove Arp entry for IP: {}",
                                     ipAddr, e)
                       }
                   }
               }
           })
        }

        def notify(cb: Callback3[IPv4Addr, MAC, MAC]) {
            listeners synchronized {
                listeners.add(cb)
            }
        }

        def unsubscribe(cb: Callback3[IPv4Addr, MAC, MAC]) {
            listeners synchronized {
                listeners.remove(cb)
            }
        }
    }

    // This is a slighlty changed implementation of the RouterBuilder trait.
    // Its sister class resides in org.midonet.midolman.topology.builders.
    private final class RouterBuilderImpl(val id: UUID) extends RouterBuilder {

        var cfg: RouterConfig = new RouterConfig
        var arpCache: ArpCache = null
        val routes = new scala.collection.mutable.HashSet[Route]()
        val routesToAdd = new scala.collection.mutable.HashSet[Route]()
        val routesToRemove = new scala.collection.mutable.HashSet[Route]()

        def setArpCache(table: ArpCache) {
            if (arpCache != null)
                throw new RuntimeException("RouterBuilder: attempt to re-set arp cache")
            if (table != null)
                arpCache = table
        }

        def addRoute(rt: Route) {
            routes.add(rt)
            routesToAdd.add(rt)
        }

        def removeRoute(rt: Route) {
            routes.remove(rt)
            routesToRemove.add(rt)
        }

        def setAdminStateUp(adminStateUp: Boolean) = {
            cfg = cfg.copy(adminStateUp = adminStateUp)
            this
        }

        def setInFilter(filterID: UUID) = {
            cfg = cfg.copy(inboundFilter = filterID)
            this
        }

        def setOutFilter(filterID: UUID) = {
            cfg = cfg.copy(outboundFilter = filterID)
            this
        }

        def setLoadBalancer(loadBalancerID: UUID) = {
            cfg = cfg.copy(loadBalancer = loadBalancerID)
        }

        def build() {
            // we always pass a new copy of the RoutingTable since this is accessed
            // by the RCU Router
            val table = new IPv4RoutingTable()
            for (rt <- routes)
                table.addRoute(rt)
            if (routesToAdd.size > 0 || routesToRemove.size > 0) {
                val added = routesToAdd.clone()
                val deleted = routesToRemove.clone()
                invalidateFlows(added, deleted)
            }
            routesToAdd.clear()
            routesToRemove.clear()

            triggerUpdate(cfg, arpCache, new RoutingTableWrapper(table))
        }

        def start() = null
    }

    override def logSource = s"org.midonet.devices.router.router-$id"

    private val observableCreated = new AtomicBoolean(false)
    // The stream of router updates coming from zoom
    private var zoomRouterStream: Observable[Router] = _
    /*
     * The stream of router updates coming out of the router mapper
     * We have two observables because [[outStream]] emits
     * updates when the router mapper gets a callback from the
     * TopologyPrefetcher and thus one obserbable is not enough.
     */
    private val outStream = BehaviorSubject.create[SimRouter]()
    private val onRouterCompleted = makeAction0({
        outStream.onCompleted()
        stateArpTable.stop()
        routeSet.stop()

    })

    private val routerBuilder = new RouterBuilderImpl(id)
    private val dstIpTagTrie: InvalidationTrie = new InvalidationTrie()
    private val tagToFlowCount: mutable.Map[IPAddr, Int]
        = new mutable.HashMap[IPAddr, Int]

    @Inject
    private var midolmanConfig: MidolmanConfig = _
    @Inject
    private var zkConnectionWatcher: ZookeeperConnectionWatcher = _
    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    private var reactorLoop: Reactor = _

    var cfg: RouterConfig = _
    var arpCache: ArpCache = _
    var rTable: RoutingTableWrapper[IPv4Addr] = _
    var stateArpTable: ArpTable = _
    var simArpTable: SimArpTable = _
    var routeSet: ReplicatedRouteSet = _
    var routerChanged = new AtomicBoolean(false)
    
    // This method does exactly the same thing as when the RouterManager
    // receives a message of type InvalidateFlows.
    private def invalidateFlows(addedRoutes: mutable.HashSet[Route],
                                deletedRoutes: mutable.HashSet[Route]): Unit = {
        for (route <- deletedRoutes)
            onDeviceChanged(FlowTagger.tagForRoute(route))

        for (route <- addedRoutes) {
            log.debug("Projecting added route {}", route)
            val subTree = dstIpTagTrie.projectRouteAndGetSubTree(route)
            val ipToInvalidate = InvalidationTrie.getAllDescendantsIpDestination(subTree)
            log.debug("Got the following ip destination to invalidate {}", ipToInvalidate)

            val it = ipToInvalidate.iterator()
            it.foreach(ip => onDeviceChanged(FlowTagger.tagForDestinationIp(id, ip)))
        }
    }

    private class TagManagerImpl extends TagManager {

        def addTag(dstIp: IPAddr) {
            // TODO: This used to be a message sent to the RouterMapper, by changing this
            // to a method call are we introducing a blocking operation?
            addTag(dstIp)
        }

        def getFlowRemovalCallback(dstIp: IPAddr) = {
            new Callback0 {
                def call() {
                    // TODO: This used to be a message sent to the RouterMapper, by changing this
                    // to a method call are we introducing a blocking operation?
                    removeTag(dstIp)
                }
            }
        }
    }

    // This is the exact same method as the one in RouterManager
    // The thread that calls this method is the same as the one
    // that receives messages in the TopologyPrefetcher.
    // This used to send the router to the VTA. We now
    // set the boolean routerReady to true such that the new
    // router is made available in the router observable.
    def topologyReady() {
        log.debug("Sending Router with id {} to the VTA", id)

        val router = new SimRouter(id, cfg, rTable, new TagManagerImpl, simArpTable)

        outStream.onNext(router)

        if (routerChanged.get) {
            // TODO: Will the VTARedirector take care of this?
            VirtualTopologyActor ! InvalidateFlowsByTag(router.deviceTag)
            routerChanged.set(false)
        }
    }

    private def invalidateFlowsByIp(ip: IPv4Addr): Unit =
        onDeviceChanged(FlowTagger.tagForDestinationIp(id, ip))

    //TODO: This method should mimic the reception of a TriggerUpdate msg by the RouterManager
    // This method is called by the thread that gets updates for the set of routes and
    // the thread that receives notifications from the router observable.
    private def triggerUpdate(newCfg: RouterConfig, newArpCache: ArpCache,
                              newRoutingTable: RoutingTableWrapper[IPv4Addr]): Unit = {
        if (newCfg != cfg && cfg != null)
            routerChanged.set(true)

        cfg = newCfg

        if (arpCache == null && newArpCache != null) {
            arpCache = newArpCache
            simArpTable = new ArpTableImpl(arpCache, midolmanConfig,
                                          (ip: IPv4Addr, _, _) => invalidateFlowsByIp(ip))
            simArpTable.start()
        } else if (arpCache != newArpCache) {
            throw new RuntimeException("Trying to re-set the arp cache")
        }
        rTable = newRoutingTable

        // Calling prefetchTopology will be fine once we have the VTARedirector
        // to make use of the new cluster to obtain the corresponding devices.
        prefetchTopology(loadBalancer(newCfg.loadBalancer))
    }

    private def adjustMapValue[A, B](m: mutable.Map[A, B], k: A)(f: B => B) {
        m.update(k, f(m(k)))
    }

    private def createSingleHostRoute(dstIP: IPAddr): Route = {
        val route: Route = new Route()
        route.setDstNetworkAddr(dstIP.toString)
        route.dstNetworkLength = 32
        route
    }

    //This is the same as receiving a message AddTag in the router manager
    private def addTag(dstIp: IPAddr): Unit = {
        // check if the tag is already in the map
        if (tagToFlowCount contains dstIp) {
            adjustMapValue(tagToFlowCount, dstIp)(_ + 1)
            log.debug("Increased count for tag ip {} count {}", dstIp,
                      tagToFlowCount(dstIp).underlying())
        } else {
            tagToFlowCount += (dstIp -> 1)
            dstIpTagTrie.addRoute(createSingleHostRoute(dstIp))
            log.debug("Added IP {} to invalidation trie", dstIp)
        }
        //TODO: Figure out what this does
        actorSystem.eventStream.publish(
            new RouterInvTrieTagCountModified(dstIp, tagToFlowCount(dstIp)))
    }

    //This is the same a receiving a message RemoveTag in the router manager
    private def removeTag(dstIp: IPAddr): Unit = {
        if (!(tagToFlowCount contains dstIp)) {
            log.debug("{} is not in the invalidation trie, cannot remove it!", dstIp)
        } else {
            if (tagToFlowCount(dstIp) == 1) {
                // we need to remove the tag
                tagToFlowCount.remove(dstIp)
                dstIpTagTrie.deleteRoute(createSingleHostRoute(dstIp))
                log.debug("Removed IP {} from invalidation trie", dstIp)
            } else {
                adjustMapValue(tagToFlowCount, dstIp)(_ - 1)
                log.debug("Decreased count for tag IP {} count {}", dstIp,
                          tagToFlowCount(dstIp).underlying())
            }
        }
        val count = if (tagToFlowCount contains dstIp) tagToFlowCount(dstIp) else 0
        actorSystem.eventStream.publish(new RouterInvTrieTagCountModified(dstIp,
                                                                          count))
    }

    private val handleUpdate = makeFunc1 { router: Router =>
        log.debug("Update configuration for router {}", id)

        routerBuilder.setAdminStateUp(router.getAdminStateUp)
            .setInFilter(router.getInboundFilterId)
            .setOutFilter(router.getOutboundFilterId)
            .setLoadBalancer(router.getLoadBalancerId)

        // TODO: This results eventually in sending the router to the VTA.
        // However, the handleUpdate method should return a router.
        routerBuilder.build()

        router
    }

    private def isRouterReady = makeFunc1 { router: SimRouter =>
        // TODO(nicolas): to complete
    }

    private def initRouteSet(): Unit = {
        routeSet = new ReplicatedRouteSet(
            routerMgr.getRoutingTableDirectory(id), CreateMode.EPHEMERAL,
            routerBuilder)
        routeSet.setConnectionWatcher(zkConnectionWatcher)

        // Note that the following may trigger a call to routerBuilder.build()
        // it should be the last call in the mapper initialization code path.
        routeSet.start()
    }

    private def initArpTable(): Unit = {
        stateArpTable = new ArpTable(routerMgr.getArpTableDirectory(id))
        stateArpTable.setConnectionWatcher(zkConnectionWatcher)
        routerBuilder.setArpCache(new ArpCacheImpl(stateArpTable, id))
        stateArpTable.start()
    }

    override def observable: Observable[SimRouter] = {
        if (observableCreated.compareAndSet(false, true)) {
            initArpTable()
            initRouteSet()

            // TODO: This observable is kind of useless as its output is not used
            //       (it calls routerBuilder.build)
            zoomRouterStream = vt.store.observable(classOf[Router], id)
                .map[Router](handleUpdate)
                .doOnCompleted(onRouterCompleted)
        }
        outStream
    }
}
