/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import java.util.UUID
import collection.mutable
import collection.JavaConversions._

import org.apache.zookeeper.CreateMode

import com.midokura.midolman.state.zkManagers.{RouteZkManager, RouterZkManager}
import com.midokura.midolman.layer3.{Route, RoutingTable}
import com.midokura.midolman.util.JSONSerializer
import com.midokura.midolman.state.zkManagers.RouterZkManager.RouterConfig
import com.midokura.midolman.simulation.Router

class RouterManager(id: UUID, val mgr: RouterZkManager,
                    val routeMgr: RouteZkManager)
    extends DeviceManager(id) {
    private val rtableDirectory = mgr.getRoutingTableDirectory(id)
    private val serializer = new JSONSerializer()

    private var cfg: RouterConfig = null
    private var rTable: RoutingTable = null
    private val localPortToRouteIDs = mutable.Map[UUID, mutable.Set[UUID]]()
    private val idToRoute = mutable.Map[UUID, Route]()
    refreshTableRoutes()

    case object RefreshTableRoutes

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
                new Router(id, cfg, rTable, inFilter, outFilter));
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

    override def receive() = super.receive orElse {
        case SetRouterPortLocal(_, portId, local) =>
            updatePortLocality(portId, local)
        case RefreshTableRoutes => refreshTableRoutes()
        case RefreshLocalPortRoutes(portId) => refreshLocalPortRoutes(portId)
    }
}
