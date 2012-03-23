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
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

public class L3DevicePort {

    public interface Listener {
        void configChanged(UUID portId,
                PortDirectory.MaterializedRouterPortConfig old,
                PortDirectory.MaterializedRouterPortConfig current);

        void routesChanged(UUID portId, Collection<Route> removed,
                Collection<Route> added);
    }

    private PortZkManager portMgr;
    private RouteZkManager routeMgr;
    private UUID portId;
    private PortWatcher portWatcher;
    private RoutesWatcher routesWatcher;
    private PortDirectory.MaterializedRouterPortConfig portCfg;
    private Set<Listener> listeners;

    private final Logger log;

    public L3DevicePort(PortZkManager portMgr, RouteZkManager routeMgr,
            UUID portId) throws KeeperException, StateAccessException {

        log = LoggerFactory.getLogger(
                L3DevicePort.class.getCanonicalName() + '.' + portId);

        this.portMgr = portMgr;
        this.routeMgr = routeMgr;
        this.portId = portId;
        this.portWatcher = new PortWatcher();
        this.routesWatcher = new RoutesWatcher();
        listeners = new HashSet<Listener>();
        updatePortConfig();
        updateRoutes();
    }

    private class PortWatcher implements Runnable {
        public void run() {
            try {
                updatePortConfig();
            } catch (StateAccessException e) {
                // TODO Auto-generated catch block
                log.warn("PortWatcher.run", e);
            } catch (KeeperException e) {
                // TODO Auto-generated catch block
                log.warn("PortWatcher.run", e);
            }
        }
    }

    private void updatePortConfig()
            throws KeeperException, StateAccessException {
        ZkNodeEntry<UUID, PortConfig> entry = null;
        try {
            entry = portMgr.get(portId, portWatcher);
        } catch (NoStatePathException e) {
            // if we get a NoStatePathException it means the someone removed
            // the port completely
            return;
        }
        PortConfig cfg = entry.value;
        if (!(cfg instanceof PortDirectory.MaterializedRouterPortConfig))
            throw new RuntimeException("L3DevicePort's virtual configuration " +
                    "isn't a MaterializedRouterPortConfig.");
        PortDirectory.MaterializedRouterPortConfig oldCfg = portCfg;
        portCfg = PortDirectory.MaterializedRouterPortConfig.class.cast(cfg);
        // Keep the old routes.
        if (null != oldCfg)
            portCfg.setRoutes(oldCfg.getRoutes());
        for (Listener listener : listeners)
            // TODO(pino): should we schedule this instead?
            listener.configChanged(portId, oldCfg, portCfg);
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

    private void updateRoutes() throws StateAccessException,
            ZkStateSerializationException {
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
        if (routes.equals(portCfg.getRoutes()))
            return;
        Set<Route> oldRoutes = portCfg.getRoutes();
        if (oldRoutes == null)
            oldRoutes = new HashSet<Route>();
        portCfg.setRoutes(new HashSet<Route>(routes));
        routes.removeAll(oldRoutes);
        oldRoutes.removeAll(portCfg.getRoutes());
        for (Listener listener : listeners)
            // TODO(pino): should we schedule this instead?
            listener.routesChanged(portId, routes, oldRoutes);
    }

    public UUID getId() {
        return portId;
    }

    public MAC getMacAddr() {
        return portCfg.getHwAddr();
    }

    public PortDirectory.MaterializedRouterPortConfig getVirtualConfig() {
        return portCfg;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public String toString() {
        return "L3DevicePort [portId=" + portId + ", portCfg=" + portCfg + "]";
    }
}
