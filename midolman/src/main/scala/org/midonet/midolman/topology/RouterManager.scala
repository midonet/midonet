/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import collection.{Set => ROSet, mutable, Iterable}
import collection.JavaConversions._
import java.util.UUID

import org.midonet.cluster.Client
import org.midonet.cluster.client.ArpCache
import org.midonet.midolman.FlowController
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.layer3.{RoutingTableIfc, InvalidationTrie, Route}
import org.midonet.midolman.simulation.{ArpTable, ArpTableImpl, Router}
import org.midonet.midolman.topology.RouterManager._
import org.midonet.midolman.topology.builders.RouterBuilderImpl
import org.midonet.packets.{IPAddr, IPv4Addr, MAC}
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.util.functors.Callback0
import org.midonet.midolman.FlowController.InvalidateFlowsByTag


class RoutingTableWrapper[IP <: IPAddr](val rTable: RoutingTableIfc[IP]) {
    import collection.JavaConversions._
    def lookup(wmatch: WildcardMatch): Iterable[Route] =
        // TODO (ipv6) de facto implementation for ipv4, that explains
        // the casts at this point.
        rTable.lookup(wmatch.getNetworkSourceIP.asInstanceOf[IP],
                      wmatch.getNetworkDestinationIP.asInstanceOf[IP])
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
                        outboundFilter: UUID = null)

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
 * @param id
 * @param client
 * @param config
 */
class RouterManager(id: UUID, val client: Client, val config: MidolmanConfig)
        extends DeviceManager(id) {
    import context.system

    private var cfg: RouterConfig = null
    private var changed = false
    private var rTable: RoutingTableWrapper[IPv4Addr]= null
    private var arpCache: ArpCache = null
    private var arpTable: ArpTable = null
    // This trie is to store the tag that represent the ip destination to be
    // able to do flow invalidation properly when a route is added or deleted
    private val dstIpTagTrie: InvalidationTrie = new InvalidationTrie()
    // key is dstIp tag, value is the count
    private val tagToFlowCount: mutable.Map[IPAddr, Int]
                                = new mutable.HashMap[IPAddr, Int]

    override def chainsUpdated() {
        makeNewRouter()
        if (changed) {
            VirtualTopologyActor.getRef() !
                InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(id))
            changed = false
        }
    }

    private def makeNewRouter() {
        if (chainsReady && null != rTable && null != arpTable) {
            log.debug("Send an RCU router to the VTA")
            // Not using context.actorFor("..") because in tests it will
            // bypass the probes and make it harder to fish for these messages
            // Should this need to be decoupled from the VTA, the parent
            // actor reference should be passed in the constructor
            VirtualTopologyActor.getRef() !
                new Router(id, cfg, rTable, inFilter, outFilter,
                           new TagManagerImpl, arpTable)
        } else {
            log.debug("The chains aren't ready yet. ")
        }
    }

    override def preStart() {
        client.getRouter(id, new RouterBuilderImpl(id, self))
    }

    override def isAdminStateUp = {
        cfg match {
            case null => false
            case _ => cfg.adminStateUp
        }
    }

    override def getInFilterID = {
        cfg match {
            case null => null
            case _ => cfg.inboundFilter
        }
    }

    override def getOutFilterID = {
        cfg match {
            case null => null
            case _ => cfg.outboundFilter
        }
    }

    private def invalidateFlowsByIp(ip: IPv4Addr) {
        FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
            FlowTagger.invalidateByIp(id, ip))
    }

    override def receive = super.receive orElse {
        case TriggerUpdate(newCfg, newArpCache, newRoutingTable) =>
            log.debug("TriggerUpdate with {} {} {}",
                Array(newCfg, newArpCache, newRoutingTable))

            if (newCfg != cfg && cfg != null)
                changed = true

            cfg = newCfg

            if (arpCache == null && newArpCache != null) {
                arpCache = newArpCache
                arpTable = new ArpTableImpl(arpCache, config,
                    (ip: IPv4Addr, mac: MAC) => invalidateFlowsByIp(ip))
                arpTable.start()
            } else if (arpCache != newArpCache) {
                throw new RuntimeException("Trying to re-set the arp cache")
            }
            rTable = newRoutingTable

            configUpdated()

        case InvalidateFlows(addedRoutes, deletedRoutes) =>
            for (route <- deletedRoutes) {
                FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateByRoute(id, route.hashCode())
                )
            }
            for (route <- addedRoutes) {
                log.debug("Projecting added route {}", route)
                val subTree = dstIpTagTrie.projectRouteAndGetSubTree(route)
                val ipToInvalidate = InvalidationTrie.getAllDescendantsIpDestination(subTree)
                log.debug("Got the following ip destination to invalidate {}",
                          ipToInvalidate)

                val it = ipToInvalidate.iterator()
                it.foreach(ip => FlowController.getRef() !
                    FlowController.InvalidateFlowsByTag(
                        FlowTagger.invalidateByIp(id, ip)))
                }

        case AddTag(dstIp) =>
            // check if the tag is already in the map
            if (tagToFlowCount contains dstIp) {
                adjustMapValue(tagToFlowCount, dstIp)(_ + 1)
                log.debug("Increased count for tag ip {} count {}", dstIp,
                    tagToFlowCount(dstIp))
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
                        tagToFlowCount(dstIp))
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
