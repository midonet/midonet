/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import akka.actor.ActorRef
import akka.dispatch.{Await, ExecutionContext, Future, Promise}
import akka.dispatch.Future.flow
import akka.pattern.{ask, AskTimeoutException}
import akka.util.Timeout
import akka.util.duration._
import collection.mutable
import collection.JavaConversions._
import compat.Platform
import java.util.UUID

import org.apache.zookeeper.CreateMode

import com.midokura.midolman.layer3.{Route, RoutingTable}
import com.midokura.midolman.simulation.Router
import com.midokura.midolman.state.ArpCacheEntry
import com.midokura.midolman.state.zkManagers.{RouteZkManager, RouterZkManager}
import com.midokura.midolman.state.zkManagers.RouterZkManager.RouterConfig
import com.midokura.midolman.util.JSONSerializer
import com.midokura.midonet.cluster.client.ArpCache
import com.midokura.packets.{IntIPv4, MAC}
import com.midokura.util.functors.Callback1


/* The ArpTable is called from the Coordinators' actors and dispatches
 * to the RouterManager's actor to send and schedule ARPs. */
trait ArpTable {
    def get(ip: IntIPv4, ec: ExecutionContext): Future[MAC]
    def set(ip: IntIPv4, mac: MAC)
}


class RouterManager(id: UUID, val mgr: RouterZkManager,
                    val routeMgr: RouteZkManager)
        extends DeviceManager(id) {
    private val rtableDirectory = mgr.getRoutingTableDirectory(id)
    private val serializer = new JSONSerializer()
    private var cfg: RouterConfig = null
    private var rTable: RoutingTable = null
    private val localPortToRouteIDs = mutable.Map[UUID, mutable.Set[UUID]]()
    private val idToRoute = mutable.Map[UUID, Route]()
    private val arpCache: ArpCache = null  //XXX
    private val arpTable = new ArpTableImpl
    private val ARP_STALE_MILLIS: Long = 1800 * 1000
    private val ARP_EXPIRATION_MILLIS: Long = 3600 * 1000
    private val arpWaiters = new mutable.HashMap[IntIPv4, 
                                                 mutable.Set[ActorRef]] with
                                 mutable.MultiMap[IntIPv4, ActorRef]
    // 1. take if from the client
    // 2. wrap it so that if the entry is not there you can arp and deliver the answer
    // 2.1 checktimeout
    // 3. add it to the table
    // When the Router does arpCacheWrapper.get is will block, synchronous call until it
    //gets an answer, check Await.result(self ? GetFlowCount(mac, port),   in bridge


    case object RefreshTableRoutes

    // Initialization:  Get the routing table data from the cluster.
    refreshTableRoutes()


    val tableRoutesCb: Runnable = new Runnable() {
        def run() {
            // CAREFUL: this is not run on this Actor's thread.
            self.tell(RefreshTableRoutes)
        }
    }

    case class RefreshLocalPortRoutes(val portId: UUID)

    def makePortRoutesCallback(portId: UUID): Runnable = {
        new Runnable() {
            def run() {
                // CAREFUL: this is not run on this Actor's thread.
                self.tell(RefreshLocalPortRoutes(portId))
            }
        }
    }

    private def refreshTableRoutes(): Unit = {
        // TODO(pino): make this non-blocking.
        val routes = rtableDirectory.getChildren("", tableRoutesCb)
        rTable = new RoutingTable()
        for (rt <- routes) {
            rTable.addRoute(
                serializer.bytesToObj(rt.getBytes(), classOf[Route]))
        }
        makeNewRouter()
    }

    private def refreshLocalPortRoutes(portId: UUID): Unit = {
        // Ignore this message if the port is no longer local.
        if (localPortToRouteIDs.contains(portId)) {
            val oldRouteIdSet = localPortToRouteIDs(portId)
            val newRouteIdSet = mutable.Set[UUID]()
            localPortToRouteIDs.put(portId, newRouteIdSet)
            for (rtID <- routeMgr.listPortRoutes(
                portId, makePortRoutesCallback(portId))) {
                newRouteIdSet.add(rtID)
                if (!oldRouteIdSet(rtID)) {
                    // It's a new route: write it to the shared routing table
                    val rt = routeMgr.get(rtID)
                    idToRoute.put(rtID, rt)
                    rtableDirectory.add(
                        "/" + new String(serializer.objToBytes(rt)),
                        null, CreateMode.EPHEMERAL)
                }
            }
            // Now process the removed routes
            for (rtID <- oldRouteIdSet) {
                if (!newRouteIdSet(rtID)) {
                    val rt = idToRoute.remove(rtID)
                    rtableDirectory.delete("/" +
                        new String(serializer.objToBytes(rt)))
                }
            }
        }
    }

    private def updatePortLocality(portId: UUID, local: Boolean) {
        // Ignore the message if we already agree with the locality.
        if (localPortToRouteIDs.contains(portId) != local) {
            if (local) {
                localPortToRouteIDs.put(portId, mutable.Set[UUID]())
                refreshLocalPortRoutes(portId)
            } else {
                localPortToRouteIDs.remove(portId) match {
                    case Some(routeIdSet) =>
                        for (rtID <- routeIdSet)
                            rtableDirectory.delete("/" + new String(
                                serializer.objToBytes(idToRoute(rtID))))
                    case None =>; // This should never happen?
                }
            }
        }
    }

    override def chainsUpdated = makeNewRouter

    private def makeNewRouter() = {
        if (chainsReady() && null != rTable)
            context.actorFor("..").tell(
                new Router(id, cfg, rTable, arpTable, inFilter, outFilter));
    }

    override def updateConfig() = {
        // TODO(pino): make this non-blocking.
        cfg = mgr.get(id, cb)
    }

    override def getInFilterID() = {
        cfg match {
            case null => null;
            case _ => cfg.inboundFilter
        }
    }

    override def getOutFilterID() = {
        cfg match {
            case null => null;
            case _ => cfg.outboundFilter
        }
    }

    private case class SetArpEntry(ip: IntIPv4, mac: MAC)
    private case class ArpForAddress(ip: IntIPv4)
    private case class WaitForArpEntry(ip: IntIPv4)

    override def receive() = super.receive orElse {
        case SetRouterPortLocal(_, portId, local) =>
            updatePortLocality(portId, local)
        case RefreshTableRoutes => refreshTableRoutes()
        case RefreshLocalPortRoutes(portId) => refreshLocalPortRoutes(portId)
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
    }

    private class ArpTableImpl extends ArpTable {
        def get(ip: IntIPv4, ec: ExecutionContext): Future[MAC] = {
            implicit val timeout = Timeout(1 minute)
            val promise = Promise[ArpCacheEntry]()(ec)
            val rv = Promise[MAC]()(ec)
            arpCache.get(ip, new Callback1[ArpCacheEntry] {
                def call(value: ArpCacheEntry) {
                    promise.complete(Right(value))
                }
            })
            val now = Platform.currentTime
            flow {
                val entry = promise()
                if (entry == null || entry.stale < now)
                    self ! ArpForAddress(ip)
                if (entry != null && entry.expiry >= now)
                    rv.complete(Right(entry.macAddr))
                else {
                    // There's no arpCache entry, or it's expired.
                    // Wait for the arpCache to become populated by an ARP reply
                    rv << self.ask(WaitForArpEntry(ip)).mapTo[MAC]
                }
            }(ec)
            return rv
        }

        def set(ip: IntIPv4, mac: MAC) {
            self ! SetArpEntry(ip, mac)
        }
    }
}
