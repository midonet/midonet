// Copyright 2012 Midokura Inc.

package com.midokura.midolman.layer3;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.packets.MAC;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.PortConfigCache;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;

public class L3DevicePort {

    public interface Listener {
        void routesChanged(UUID portId, Collection<Route> added,
                Collection<Route> removed);
    }

    private PortConfigCache portCache;
    private RouteZkManager routeMgr;
    private UUID portId;
    private RoutesWatcher routesWatcher = new RoutesWatcher();
    private Set<Route> routes = new HashSet<Route>();
    private Set<Listener> listeners = new HashSet<Listener>();

    private final Logger log;

    public L3DevicePort(PortConfigCache portCache, RouteZkManager routeMgr,
            UUID portId) throws KeeperException, StateAccessException {

        log = LoggerFactory.getLogger(
                L3DevicePort.class.getCanonicalName() + '.' + portId);

        this.portCache = portCache;
        this.routeMgr = routeMgr;
        this.portId = portId;
        updateRoutes();
    }

    private class RoutesWatcher implements Runnable {
        public void run() {
            try {
                updateRoutes();
            } catch (Exception e) {
                log.warn("RoutesWatcher.run", e);
            }
        }
    }

    private void updateRoutes() throws StateAccessException {
        log.debug("updateRoutes");

        List<UUID> entries = Collections.emptyList();

        try {
            entries = routeMgr.listPortRoutes(portId, routesWatcher);
        } catch (NoStatePathException e) {
            // If we get a NoStatePathException it means the someone removed
            // the port routes. Purposely fall through and process the
            // removal of all the routes.
        }

        Set<Route> newRoutes = new HashSet<Route>();
        for (UUID entry : entries)
            newRoutes.add(routeMgr.get(entry));
        if (newRoutes.equals(routes))
            return;
        Set<Route> removedRoutes = new HashSet<Route>(routes);
        removedRoutes.removeAll(newRoutes);
        Set<Route> addedRoutes = new HashSet<Route>(newRoutes);
        addedRoutes.removeAll(routes);
        routes = newRoutes;
        for (Listener listener : listeners)
            // TODO(pino): should we schedule this instead?
            listener.routesChanged(portId, addedRoutes, removedRoutes);
    }

    public UUID getId() {
        return portId;
    }

    public MAC getMacAddr() {
        return getVirtualConfig().getHwAddr();
    }

    public int getIPAddr() {
        return getVirtualConfig().portAddr;
    }

    public Set<Route> getRoutes() {
        return routes;
    }

    public PortDirectory.MaterializedRouterPortConfig getVirtualConfig() {
        return PortDirectory.MaterializedRouterPortConfig.class
                .cast(portCache.get(portId));
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public String toString() {
        return "L3DevicePort [portId=" + portId
                + ", portCfg=" + portCache.get(portId)
                + ", routes=" + routes.toString() + "]";
    }
}
