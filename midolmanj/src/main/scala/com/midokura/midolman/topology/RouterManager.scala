/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import collection.Iterable
import java.util.UUID

import com.midokura.midolman.layer3.{InvalidationTrie, Route, RoutingTable}
import com.midokura.midolman.simulation.{ArpTable, ArpTableImpl, Router}
import com.midokura.midolman.topology.builders.RouterBuilderImpl
import com.midokura.midonet.cluster.Client
import com.midokura.midonet.cluster.client.ArpCache
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.midolman.FlowController
import com.midokura.midolman.topology.RouterManager.{RemoveTag, AddTag, InvalidateFlows, TriggerUpdate}
import com.midokura.midolman.config.MidolmanConfig
import scala.collection.JavaConversions._
import com.midokura.util.functors.Callback0
import collection.{Set => ROSet}
import com.midokura.packets.IPv4

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

}

class RouterConfig {
    var inboundFilter: UUID = null
    var outboundFilter: UUID = null

    override def hashCode: Int = {
        var hCode = 0;
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
}

trait TagManager {
    def addTag(dstIp: Int)

    def getTagRemovalCallback(dstIp: Int): Callback0
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

    override def chainsUpdated = makeNewRouter

    private def makeNewRouter() = {
        if (chainsReady && null != rTable && null != arpTable) {
            log.debug("Send an RCU router to the VTA")
            context.actorFor("..").tell(
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

    override def receive = super.receive orElse {
        case TriggerUpdate(newCfg, newArpCache, newRoutingTable) =>
            log.debug("TriggerUpdate with {} {} {}",
                Array(newCfg, newArpCache, newRoutingTable))
            if (newCfg != cfg && cfg != null) {
                // the cfg of this router changed, invalidate all the flows
                filterChanged = true
            }
            cfg = newCfg
            if (arpCache == null && newArpCache != null) {
                arpCache = newArpCache
                arpTable = new ArpTableImpl(arpCache, config)
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
                val routesToInvalidate = InvalidationTrie.getAllDescendants(subTree)
                for (node <- routesToInvalidate) {
                    for (route <- node.getRoutes){
                        FlowController.getRef() ! FlowController.InvalidateFlowsByTag(
                            FlowTagger.invalidateByIp(id, route.dstNetworkAddr)
                        )
                    }
                }
            }

        case AddTag(dstIp) =>
            dstIpTagTrie.addRoute(createSingleHostRoute(dstIp))
            log.debug("Added ip {} to invalidation trie", dstIp)

        case RemoveTag(dstIp: Int) =>
            dstIpTagTrie.deleteRoute(createSingleHostRoute(dstIp))
            log.debug("Removed ip {} to invalidation trie", dstIp)

    }

    def createSingleHostRoute(dstIp: Int): Route = {
        val route: Route = new Route()
        route.setDstNetworkAddr(IPv4.fromIPv4Address(dstIp).toString)
        route.dstNetworkLength = 32
        route
    }

    private class TagManagerImpl extends TagManager {

        def addTag(dstIp: Int) {
            self ! AddTag(dstIp)
        }

        def getTagRemovalCallback(dstIp: Int) = {
            new Callback0 {
                def call() {
                    self ! RemoveTag(dstIp)
                }
            }

        }
    }
}
