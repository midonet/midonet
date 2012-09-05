/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import akka.actor.ActorRef
import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.dispatch.Future.flow
import akka.pattern.ask
import collection.{Iterable, mutable}
import compat.Platform
import java.util.UUID

import com.midokura.midolman.layer3.{Route, RoutingTable}
import com.midokura.midolman.simulation.{Coordinator, Router}
import com.midokura.midolman.state.ArpCacheEntry
import com.midokura.midolman.topology.RouterManager.TriggerUpdate
import com.midokura.midolman.topology.builders.RouterBuilderImpl
import com.midokura.midonet.cluster.Client
import com.midokura.midonet.cluster.client.ArpCache
import com.midokura.packets.{IntIPv4, MAC}
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.util.functors.Callback1
import com.midokura.midolman.FlowController


/* The ArpTable is called from the Coordinators' actors and dispatches
 * to the RouterManager's actor to send and schedule ARPs. */
trait ArpTable {
    def get(ip: IntIPv4, expiry: Long, ec: ExecutionContext): Future[MAC]
    def set(ip: IntIPv4, mac: MAC)
}

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
}

class RouterConfig {
    var inboundFilter: UUID = null
    var outboundFilter: UUID = null

    override def hashCode = (inboundFilter.toString + outboundFilter.toString).hashCode()

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

class RouterManager(id: UUID, val client: Client)
        extends DeviceManager(id) {
    private var cfg: RouterConfig = null
    private var rTable: RoutingTableWrapper = null
    private var arpCache: ArpCache = null
    private val arpTable = new ArpTableImpl
    private val ARP_STALE_MILLIS: Long = 1800 * 1000
    private val ARP_EXPIRATION_MILLIS: Long = 3600 * 1000
    private val arpWaiters = new mutable.HashMap[IntIPv4,
                                                 mutable.Set[ActorRef]] with
                                 mutable.MultiMap[IntIPv4, ActorRef]
    private var filterChanged = false;

    override def chainsUpdated = makeNewRouter

    private def makeNewRouter() = {
        if (chainsReady() && null != rTable)
            context.actorFor("..").tell(
                new Router(id, cfg, rTable, arpTable, inFilter, outFilter));

        if(filterChanged){
            FlowController.getRef() ! FlowController.InvalidateFlowByTag(
            FlowTagger.invalidateAllDeviceFlowsTag(id))
        }
        filterChanged = false
    }

    override def preStart() {
        client.getRouter(id, new RouterBuilderImpl(id, self))
    }

    override def getInFilterID = {
        cfg match {
            case null => null;
            case _ => cfg.inboundFilter
        }
    }

    override def getOutFilterID = {
        cfg match {
            case null => null;
            case _ => cfg.outboundFilter
        }
    }

    private case class SetArpEntry(ip: IntIPv4, mac: MAC)
    private case class ArpForAddress(ip: IntIPv4)
    private case class WaitForArpEntry(ip: IntIPv4)

    override def receive() = super.receive orElse {
        case SetArpEntry(ip, mac) =>
            val now = Platform.currentTime
            val entry = new ArpCacheEntry(mac, now+ARP_STALE_MILLIS,
                                          now+ARP_EXPIRATION_MILLIS, 0)
            arpCache.add(ip, entry)
            // XXX: Remove any scheduled ARP retries.
            // XXX: Reschedule the ARP expiration.

            // Trigger any actors waiting from WaitForArpEntrys
            arpWaiters.remove(ip) match {
                case Some(waiters) => waiters map { _ ! mac }
                case None => /* do nothing */
            }
        case ArpForAddress(ip) =>
            // XXX: Ignore if already ARP'ing for this address.
            // XXX: Send an ARP and schedule retries.
        case WaitForArpEntry(ip) =>
            arpWaiters.addBinding(ip, sender)
        case TriggerUpdate(newCfg, newArpCache, newRoutingTable) =>
            if(newCfg != cfg && cfg != null){
                // the cfg of this router changed, invalidate all the flows
                filterChanged = true
            }
            cfg = newCfg
            arpCache = newArpCache
            rTable = newRoutingTable
            configUpdated()
    }

    private class ArpTableImpl extends ArpTable {
        def get(ip: IntIPv4, expiry: Long, ec: ExecutionContext):
                Future[MAC] = {
            val promise = Promise[ArpCacheEntry]()(ec)
            val rv = Promise[MAC]()(ec)
            arpCache.get(ip, new Callback1[ArpCacheEntry] {
                def call(value: ArpCacheEntry) {
                    promise.success(value)
                }
            }, expiry)
            val now = Platform.currentTime
            flow {
                val entry = promise()
                if (entry == null || entry.stale < now)
                    self ! ArpForAddress(ip)
                if (entry != null && entry.expiry >= now)
                    rv.success(entry.macAddr)
                else {
                    // There's no arpCache entry, or it's expired.
                    // Wait for the arpCache to become populated by an ARP reply
                    rv << Coordinator.expiringAsk(self, WaitForArpEntry(ip),
                                                  expiry)(ec).mapTo[MAC]
                }
            }(ec)
            return rv
        }

        def set(ip: IntIPv4, mac: MAC) {
            self ! SetArpEntry(ip, mac)
        }
    }
}
