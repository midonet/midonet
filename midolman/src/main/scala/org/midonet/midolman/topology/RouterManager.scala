/*
 * Copyright 2014 Midokura SARL
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

import collection.{Set => ROSet, mutable, Iterable}
import collection.JavaConversions._
import java.util.UUID

import org.midonet.cluster.Client
import org.midonet.cluster.client.ArpCache
import org.midonet.midolman.FlowController
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.layer3.{RoutingTableIfc, InvalidationTrie, Route}
import org.midonet.midolman.simulation.{ArpTable, ArpTableImpl, Router}
import org.midonet.midolman.topology.RouterManager._
import org.midonet.midolman.topology.builders.RouterBuilderImpl
import org.midonet.packets.{IPAddr, IPv4Addr, MAC}
import org.midonet.sdn.flows.{FlowTagger, WildcardMatch}
import org.midonet.util.functors.Callback0

class RoutingTableWrapper[IP <: IPAddr](val rTable: RoutingTableIfc[IP]) {

    import collection.JavaConversions._

    def lookup(wmatch: WildcardMatch): Iterable[Route] =
    // TODO (ipv6) de facto implementation for ipv4, that explains
    // the casts at this point.
        rTable.lookup(wmatch.getNetworkSrcIP.asInstanceOf[IP],
                      wmatch.getNetworkDstIP.asInstanceOf[IP])
}

object RouterManager {
    val Name = "RouterManager"

    case class TriggerUpdate(cfg: RouterConfig, arpCache: ArpCache,
                             rTable: RoutingTableWrapper[IPv4Addr])

    case class InvalidateFlows(addedRoutes: ROSet[Route],
                               deletedRoutes: ROSet[Route])

    case class AddTag(dstIp: IPAddr)

    case class RemoveTag(dstIp: IPAddr)

    // these msg are used for testing
    case class RouterInvTrieTagCountModified(dstIp: IPAddr, count: Int)

}

case class RouterConfig(adminStateUp: Boolean = true,
                        inboundFilter: UUID = null,
                        outboundFilter: UUID = null,
                        loadBalancer: UUID = null)

/**
 * Provided to the Router for operations on Tags.
 */
trait TagManager {
    def addTag(dstIp: IPAddr)

    def getFlowRemovalCallback(dstIp: IPAddr): Callback0
}

/**
 * TODO (galo, ipv6) this class is still heavily dependant on IPv4. There are
 * two points to tackle:
 * - Routes and Invalidation Tries. This should be rewritten with an agnostic
 * version so that it can work with both IP versions. A decent suggestion might
 * be to offer a Trie for byte[] since both versions can easily be translated
 * into a block of bytes.
 * - ARP: this is not used in IPv6, an idea can be to make this a generic
 * version for IPv6, then extend adding IPv4 and IPv6 "toolsets" to each.
 */
class RouterManager(id: UUID, val client: Client, val config: MidolmanConfig)
        extends DeviceWithChains {
    import context.system
    import context.dispatcher

    override def logSource = s"org.midonet.devices.router.router-$id"

    protected var cfg: RouterConfig = null
    private var changed = false
    private var rTable: RoutingTableWrapper[IPv4Addr] = null
    private var arpCache: ArpCache = null
    private var arpTable: ArpTable = null
    // This trie is to store the tag that represent the ip destination to be
    // able to do flow invalidation properly when a route is added or deleted
    private val dstIpTagTrie: InvalidationTrie = new InvalidationTrie()
    // key is dstIp tag, value is the count
    private val tagToFlowCount: mutable.Map[IPAddr, Int]
                                = new mutable.HashMap[IPAddr, Int]

    def topologyReady() {
        log.debug("Sending a Router to the VTA")

        val router = new Router(id, cfg, rTable, new TagManagerImpl, arpTable)

        // Not using context.actorFor("..") because in tests it will
        // bypass the probes and make it harder to fish for these messages
        // Should this need to be decoupled from the VTA, the parent
        // actor reference should be passed in the constructor
        VirtualTopologyActor ! router

        if (changed) {
            VirtualTopologyActor ! InvalidateFlowsByTag(router.deviceTag)
            changed = false
        }
    }

    override def preStart() {
        client.getRouter(id, new RouterBuilderImpl(id, self))
    }

    private def invalidateFlowsByIp(ip: IPv4Addr) {
        FlowController ! FlowController.InvalidateFlowsByTag(
            FlowTagger.tagForDestinationIp(id, ip))
    }

    override def receive = super.receive orElse {
        case TriggerUpdate(newCfg, newArpCache, newRoutingTable) =>
            log.debug("TriggerUpdate with {} {} {}",
                      newCfg, newArpCache, newRoutingTable)

            if (newCfg != cfg && cfg != null)
                changed = true

            cfg = newCfg

            if (arpCache == null && newArpCache != null) {
                arpCache = newArpCache
                arpTable = new ArpTableImpl(arpCache, config,
                    (ip: IPv4Addr, oldMac: MAC, newMac: MAC) => invalidateFlowsByIp(ip))
                arpTable.start()
            } else if (arpCache != newArpCache) {
                throw new RuntimeException("Trying to re-set the arp cache")
            }
            rTable = newRoutingTable

            prefetchTopology(loadBalancer(newCfg.loadBalancer))

        case InvalidateFlows(addedRoutes, deletedRoutes) =>
            for (route <- deletedRoutes) {
                FlowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.tagForRoute(route))
            }
            for (route <- addedRoutes) {
                log.debug("Projecting added route {}", route)
                val subTree = dstIpTagTrie.projectRouteAndGetSubTree(route)
                val ipToInvalidate = InvalidationTrie.getAllDescendantsIpDestination(subTree)
                log.debug("Got the following ip destination to invalidate {}",
                          ipToInvalidate)

                val it = ipToInvalidate.iterator()
                it.foreach(ip => FlowController !
                    FlowController.InvalidateFlowsByTag(
                        FlowTagger.tagForDestinationIp(id, ip)))
                }

        case AddTag(dstIp) =>
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
            context.system.eventStream.publish(
                new RouterInvTrieTagCountModified(dstIp, tagToFlowCount(dstIp)))


        case RemoveTag(dstIp: IPAddr) =>
            if (!(tagToFlowCount contains dstIp)) {
                log.debug("{} is not in the invalidation trie, cannot remove it!",
                    dstIp)

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
            context.system.eventStream.publish(
                new RouterInvTrieTagCountModified(dstIp,
                    if (tagToFlowCount contains dstIp) tagToFlowCount(dstIp)
                    else 0))
    }

    def adjustMapValue[A, B](m: mutable.Map[A, B], k: A)(f: B => B) {
        m.update(k, f(m(k)))
    }

    def createSingleHostRoute(dstIP: IPAddr): Route = {
        val route: Route = new Route()
        route.setDstNetworkAddr(dstIP.toString)
        route.dstNetworkLength = 32
        route
    }

    private class TagManagerImpl extends TagManager {

        def addTag(dstIp: IPAddr) {
            self ! AddTag(dstIp)
        }

        def getFlowRemovalCallback(dstIp: IPAddr) = {
            new Callback0 {
                def call() {
                    self ! RemoveTag(dstIp)
                }
            }
        }
    }

}
