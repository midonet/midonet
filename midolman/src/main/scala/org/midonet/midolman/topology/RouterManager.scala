/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.topology

import collection.{Set => ROSet, immutable, mutable, Iterable}
import java.util.UUID

import org.midonet.midolman.layer3.{InvalidationTrie, Route, RoutingTable}
import org.midonet.midolman.simulation.{ArpTable, ArpTableImpl, Router}
import org.midonet.midolman.topology.builders.RouterBuilderImpl
import org.midonet.cluster.Client
import org.midonet.cluster.client.ArpCache
import org.midonet.midolman.FlowController
import org.midonet.midolman.topology.RouterManager._
import org.midonet.midolman.config.MidolmanConfig
import scala.collection.JavaConversions._
import org.midonet.util.functors.Callback0
import org.midonet.packets.{IntIPv4, MAC, IPv4}
import org.midonet.midolman.topology.RouterManager.InvalidateFlows
import org.midonet.midolman.topology.RouterManager.RemoveTag
import org.midonet.midolman.topology.RouterManager.TriggerUpdate
import org.midonet.midolman.topology.RouterManager.AddTag
import org.midonet.sdn.flows.WildcardMatch

class RoutingTableWrapper(val rTable: RoutingTable) {
    import collection.JavaConversions._
    def lookup(wmatch: WildcardMatch): Iterable[Route] =
            rTable.lookup(wmatch.getNetworkSource,
                          wmatch.getNetworkDestination)
}

object RouterManager {
    val Name = "RouterManager"

    case class TriggerUpdate(cfg: RouterConfig, arpCache: ArpCache,
                             rTable: RoutingTableWrapper)
    case class InvalidateFlows(addedRoutes: ROSet[Route],
                               deletedRoutes: ROSet[Route])

    case class AddTag(dstIp: Int)

    case class RemoveTag(dstIp: Int)

    // these msg are used for testing
    case class RouterInvTrieTagCountModified(dstIp: Int, count: Int)
}

class RouterConfig {
    var inboundFilter: UUID = null
    var outboundFilter: UUID = null

    override def hashCode: Int = {
        var hCode = 0
        if (null != inboundFilter)
            hCode += inboundFilter.hashCode
        if (null != outboundFilter)
            hCode = hCode * 17 + outboundFilter.hashCode
        hCode
    }

    override def equals(other: Any) = other match {
        case that: RouterConfig =>
            (that canEqual this) &&
                (this.inboundFilter == that.inboundFilter) &&
                (this.outboundFilter == that.outboundFilter)
        case _ =>
            false
    }

    def canEqual(other: Any) = other.isInstanceOf[RouterConfig]

    override def clone: RouterConfig = {
        val ret = new RouterConfig()
        ret.inboundFilter = this.inboundFilter
        ret.outboundFilter = this.outboundFilter
        ret
    }
}

trait TagManager {
    def addTag(dstIp: Int)

    def getFlowRemovalCallback(dstIp: Int): Callback0
}

class RouterManager(id: UUID, val client: Client, val config: MidolmanConfig)
        extends DeviceManager(id) {
    private var cfg: RouterConfig = null
    private var rTable: RoutingTableWrapper = null
    private var arpCache: ArpCache = null
    private var arpTable: ArpTable = null
    private var filterChanged = false
    // This trie is to store the tag that represent the ip destination to be able
    // to do flow invalidation properly when a route is added or deleted
    private val dstIpTagTrie: InvalidationTrie = new InvalidationTrie()
    // key is dstIp tag, value is the count
    private val tagToFlowCount: mutable.Map[Int, Int] = new mutable.HashMap[Int, Int]

    override def chainsUpdated() {
        makeNewRouter()
    }

    private def makeNewRouter() {
        if (chainsReady && null != rTable && null != arpTable) {
            log.debug("Send an RCU router to the VTA")
            // Not using context.actorFor("..") because in tests it will
            // bypass the probes and make it harder to fish for these messages
            // Should this need to be decoupled from the VTA, the parent
            // actor reference should be passed in the constructor
            VirtualTopologyActor.getRef().tell(
                new Router(id, cfg, rTable, arpTable, inFilter, outFilter,
                    new TagManagerImpl))
    } else {
        log.debug("The chains aren't ready yet. ")
    }

        if(filterChanged){
            FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
            FlowTagger.invalidateFlowsByDevice(id))
        }
        filterChanged = false
    }

    override def preStart() {
        client.getRouter(id, new RouterBuilderImpl(id, self))
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

    private def invalidateFlowsByIp(ip: IntIPv4) {
        FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
            FlowTagger.invalidateByIp(id, ip.addressAsInt))
    }

    override def receive = super.receive orElse {
        case TriggerUpdate(newCfg, newArpCache, newRoutingTable) =>
            log.debug("TriggerUpdate with {} {} {}",
                Array(newCfg, newArpCache, newRoutingTable))
            if (newCfg != cfg && cfg != null) {
                // the cfg of this router changed, invalidate all the flows
                filterChanged = true
            }
            cfg = newCfg.clone
            if (arpCache == null && newArpCache != null) {
                arpCache = newArpCache
                arpTable = new ArpTableImpl(arpCache, config,
                    (ip: IntIPv4, mac: MAC) => invalidateFlowsByIp(ip))
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
                    ipToInvalidate.map(ip => IPv4.fromIPv4Address(ip)))
                val it = ipToInvalidate.iterator()
                it.foreach(ip => FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateByIp(id, ip)) )
                }

        case AddTag(dstIp) =>
            // check if the tag is already in the map
            if(tagToFlowCount contains dstIp){
                adjustMapValue(tagToFlowCount, dstIp)(_ + 1)
                log.debug("Increased count for tag ip {} count {}", dstIp,
                    tagToFlowCount(dstIp))
            }
            else {
                tagToFlowCount += (dstIp -> 1)
                dstIpTagTrie.addRoute(createSingleHostRoute(dstIp))
                log.debug("Added ip {} to invalidation trie", dstIp)
            }
            context.system.eventStream.publish(
                new RouterInvTrieTagCountModified(dstIp, tagToFlowCount(dstIp)))


        case RemoveTag(dstIp: Int) =>
            if (!(tagToFlowCount contains dstIp)){
                log.debug("{} is not in the invalidation trie, cannot remove it!",
                    dstIp)

            }
            else {
                if(tagToFlowCount(dstIp) == 1) {
                    // we need to remove the tag
                    tagToFlowCount.remove(dstIp)
                    dstIpTagTrie.deleteRoute(createSingleHostRoute(dstIp))
                    log.debug("Removed ip {} from invalidation trie", dstIp)
                }
                else {
                    adjustMapValue(tagToFlowCount, dstIp)(_ - 1)
                    log.debug("Decreased count for tag ip {} count {}", dstIp,
                        tagToFlowCount(dstIp))
                }
            }
            context.system.eventStream.publish(
                new RouterInvTrieTagCountModified(dstIp,
                    if(tagToFlowCount contains dstIp) tagToFlowCount(dstIp) else 0))

    }

    def adjustMapValue[A, B](m: mutable.Map[A, B], k: A)(f: B => B) {m.update(k,f(m(k)))}

    def createSingleHostRoute(dstIp: Int): Route = {
        val route: Route = new Route()
        route.setDstNetworkAddr(IPv4.fromIPv4Address(dstIp))
        route.dstNetworkLength = 32
        route
    }

    private class TagManagerImpl extends TagManager {

        def addTag(dstIp: Int) {
            self ! AddTag(dstIp)
        }

        def getFlowRemovalCallback(dstIp: Int) = {
            new Callback0 {
                def call() {
                    self ! RemoveTag(dstIp)
                }
            }

        }
    }
}
