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
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import collection.JavaConversions._

import akka.actor.ActorSystem
import com.google.inject.Inject
import org.apache.zookeeper.CreateMode

import rx.Observable

import org.midonet.cluster.client.{RouterBuilder, ArpCache}
import org.midonet.cluster.{ClusterRouterManager, DataClient}
import org.midonet.cluster.models.Topology.{Router}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.layer3.{InvalidationTrie, Route, IPv4RoutingTable}
import org.midonet.midolman.simulation.{Router => SimRouter, ArpTable => SimArpTable, ArpTableImpl}
import org.midonet.midolman.state.zkManagers.{RouterZkManager, RouteZkManager}
import org.midonet.midolman.state.{ZookeeperConnectionWatcher, ArpTable}
import org.midonet.midolman.topology.RouterManager.RouterInvTrieTagCountModified
import org.midonet.packets.{IPv4Addr, IPAddr}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.functors._


class RouterMapper(id: UUID, vt: VirtualTopology, dataClient: DataClient,
                   routerMgr: RouterZkManager, routeMgr: RouteZkManager)
                      (implicit actorSystem: ActorSystem)

    extends VirtualDeviceMapper[SimRouter](id, vt)
    with DeviceWithChains {

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

    override def logSource =
        s"${classOf[org.midonet.midolman.simulation.Router].getName}-$id"

    private val observableCreated = new AtomicBoolean(false)
    private var outStream: Observable[SimRouter] = _

    private val onRouterCompleted = makeAction0({
        // TODO
    })

    private val routerBuilder = new RouterBuilderImpl(id)
    private val dstIpTagTrie: InvalidationTrie = new InvalidationTrie()
    private val tagToFlowCount: mutable.Map[IPAddr, Int]
        = new mutable.HashMap[IPAddr, Int]

    @Inject
    private val midolmanConfig: MidolmanConfig = _

    @Inject
    private val zkConnectionWatcher: ZookeeperConnectionWatcher = _

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
    def topologyReady() {
        log.debug("Sending Router with id {} to the VTA", id)

        val router = new SimRouter(id, cfg, rTable, new TagManagerImpl, arpTable)

        // Not using context.actorFor("..") because in tests it will
        // bypass the probes and make it harder to fish for these messages
        // Should this need to be decoupled from the VTA, the parent
        // actor reference should be passed in the constructor
        VirtualTopologyActor ! router

        if (routerChanged) {
            // TODO: Will the VTARedirector take care of this?
            VirtualTopologyActor ! InvalidateFlowsByTag(router.deviceTag)
            routerChanged = false
        }
    }

    var cfg: RouterConfig = _
    var arpCache: ArpCache = _
    var rTable: RoutingTableWrapper[IPv4Addr] = _
    var arpTable: SimArpTable = _
    var routerChanged = false

    private def invalidateFlowsByIp(ip: IPv4Addr): Unit =
        onDeviceChanged(FlowTagger.tagForDestinationIp(id, ip))

    //TODO: This method should mimic the reception of a TriggerUpdate msg by the RouterManager
    private def triggerUpdate(newCfg: RouterConfig, newArpCache: ArpCache,
                              newRoutingTable: RoutingTableWrapper[IPv4Addr]): Unit = {
        if (newCfg != cfg && cfg != null)
            routerChanged = true

        cfg = newCfg

        if (arpCache == null && newArpCache != null) {
            arpCache = newArpCache
            arpTable = new ArpTableImpl(arpCache, midolmanConfig,
                                       (ip: IPv4Addr, _, _) => invalidateFlowsByIp(ip))
            arpTable.start()
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
    }

    private def isRouterReady = makeFunc1 { router: SimRouter =>
        // TODO(nicolas): to complete
    }

    private def initRouteSet(): Unit = {
        val routeSet = new ClusterRouterManager#ReplicatedRouteSet(
            routerMgr.getRoutingTableDirectory(id), CreateMode.EPHEMERAL,
            routerBuilder)
        routeSet.setConnectionWatcher(zkConnectionWatcher)

        // Note that the following may trigger a call to routerBuilder.build()
        // it should be the last call in the mapper initialization code path.
        routeSet.start()
    }

    private def initArpTable(): Unit = {
        val arpTable = new ArpTable(routerMgr.getArpTableDirectory(id))
        arpTable.setConnectionWatcher(zkConnectionWatcher)
        routerBuilder.setArpCache(new ClusterRouterManager#ArpCacheImpl(arpTable, id))
        arpTable.start()
    }

    override def observable: Observable[SimRouter] = {
        if (observableCreated.compareAndSet(false, true)) {
            initArpTable()
            initRouteSet()

            outStream = vt.store.observable(classOf[Router], id)
                .map(handleUpdate)
                .doOnCompleted(onRouterCompleted)
                .filter(makeFunc1(isRouterReady))
        }
        outStream
    }
}
