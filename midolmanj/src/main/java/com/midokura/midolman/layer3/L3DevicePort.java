// Copyright 2012 Midokura Inc.

package com.midokura.midolman.layer3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortConfigCache;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

public class L3DevicePort {

    public interface Listener {
        void routesChanged(UUID portId, Collection<Route> added,
                Collection<Route> removed);
    }

    private PortConfigCache portCache;
    private RouteZkManager routeMgr;
    private UUID portId;
    private RoutesWatcher routesWatcher;
    private Set<Route> portRoutes = new HashSet<Route>();
    private Set<Listener> listeners;

    private final Logger log;

    public L3DevicePort(PortConfigCache portCache, RouteZkManager routeMgr,
            UUID portId) throws KeeperException, StateAccessException {

        log = LoggerFactory.getLogger(
                L3DevicePort.class.getCanonicalName() + '.' + portId);

        this.portCache = portCache;
        this.routeMgr = routeMgr;
        this.portId = portId;
        this.routesWatcher = new RoutesWatcher();
        listeners = new HashSet<Listener>();
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

        List<ZkNodeEntry<UUID, Route>> entries = Collections.emptyList();

        try {
            entries = routeMgr.listPortRoutes(portId, routesWatcher);
        } catch (NoStatePathException e) {
            // if we get a NoStatePathException it means the someone removed
            // the port routes
        }

        Set<Route> routes = new HashSet<Route>();
        for (ZkNodeEntry<UUID, Route> entry : entries)
            routes.add(entry.value);
        if (routes.equals(portRoutes))
            return;
        Set<Route> oldRoutes = portRoutes;
        if (oldRoutes == null)
            oldRoutes = new HashSet<Route>();
        portRoutes = new HashSet<Route>(routes);
        routes.removeAll(oldRoutes);
        oldRoutes.removeAll(portRoutes);
        for (Listener listener : listeners)
            // TODO(pino): should we schedule this instead?
            listener.routesChanged(portId, routes, oldRoutes);
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
        return portRoutes;
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
                + ", routes=" + portRoutes.toString() + "]";
    }
}
