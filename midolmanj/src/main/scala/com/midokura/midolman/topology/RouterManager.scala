/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import collection.mutable
import collection.JavaConversions._
import java.util.UUID

import org.apache.zookeeper.CreateMode

import com.midokura.midolman.layer3.{Route, RoutingTable}
import com.midokura.midolman.simulation.Router
import com.midokura.midolman.state.zkManagers.{RouteZkManager, RouterZkManager}
import com.midokura.midolman.state.zkManagers.RouterZkManager.RouterConfig
import com.midokura.midolman.util.JSONSerializer
import com.midokura.midonet.cluster.client.ArpCache


/* The MacFlowCount is called from the Coordinators' actors and dispatches
 * to the BridgeManager's actor to get/modify the flow counts.  */
trait ArpTable {
    def get(ip: IntIPv4): MAC
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
            }
            else {
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

    def getArpEntry(ip: IntIPv4): MAC = {       
        //XXX: get the ArpCacheEntry from the ArpCache
        //XXX: If stale, send and ARP and schedule retries & expiration.
        //XXX: Return value if not expired.
    }

    override def receive() = super.receive orElse {
        case SetRouterPortLocal(_, portId, local) =>
            updatePortLocality(portId, local)
        case RefreshTableRoutes => refreshTableRoutes()
        case RefreshLocalPortRoutes(portId) => refreshLocalPortRoutes(portId)
        case SetArpEntry(ip, mac) =>
            // XXX: Add/replace the IP's ArpCacheEntry in the ArpCache.
            // XXX: Remove any scheduled ARP retries.
            // XXX: Reschedule the ARP expiration.
        case GetArpEntry(ip) => sender ! getArpEntry(ip)
    }

    private class ArpTableImpl extends ArpTable {
        def get(ip: IntIPv4) = {
            implicit val timeout = Timeout(1 minute)
            // TODO(jlm): When the Await suspends, which execution context
            // is suspended?  Do we need to pass in the Coordinator's EC
            // to avoid suspending a JVM thread?
            Await.result(self ? GetArpEntry(ip),
                         timeout.duration).asInstanceOf[MAC]
        }

        def set(ip: IntIPv4, mac: MAC) {
            self ! SetArpEntry(ip, mac)
        }
    }
}
