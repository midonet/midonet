package com.midokura.midolman;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;

public class L3DevicePort {

    public interface Listener {
        void configChanged(UUID portId, MaterializedRouterPortConfig old,
                MaterializedRouterPortConfig current);

        void routesChanged(UUID portId, Collection<Route> removed,
                Collection<Route> added);
    }

    private PortDirectory portDir;
    private short portNum;
    private UUID portId;
    private byte[] mac;
    private PortWatcher portWatcher;
    private RoutesWatcher routesWatcher;
    private MaterializedRouterPortConfig portCfg;
    private Set<Listener> listeners;

    public L3DevicePort(PortDirectory portDir, UUID portId)
            throws Exception {
        this.portDir = portDir;
        this.portId = portId;
        this.portWatcher = new PortWatcher();
        this.routesWatcher = new RoutesWatcher();
        updatePortConfig();
        updateRoutes();
        listeners = new HashSet<Listener>();
    }

    private class PortWatcher implements Runnable {
        public void run() {
            try {
                updatePortConfig();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (KeeperException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private void updatePortConfig() throws Exception {
        PortConfig cfg = portDir.getPortConfigNoRoutes(portId, portWatcher);
        if (!(cfg instanceof MaterializedRouterPortConfig))
            throw new Exception("L3DevicePort's virtual configuration isn't "
                    + "a MaterializedRouterPortConfig.");
        MaterializedRouterPortConfig oldCfg = portCfg;
        portCfg = MaterializedRouterPortConfig.class.cast(cfg);
        // Keep the old routes.
        portCfg.routes = oldCfg.routes;
        for (Listener listener : listeners)
            // TODO(pino): should we schedule this instead?
            listener.configChanged(portId, oldCfg, portCfg);
    }

    private class RoutesWatcher implements Runnable {
        public void run() {
            try {
                updateRoutes();
            } catch (KeeperException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private void updateRoutes() throws KeeperException, InterruptedException {
        Set<Route> routes = portDir.getRoutes(portId, routesWatcher);
        if(routes.equals(portCfg.routes))
            return;
        Set<Route> oldRoutes = portCfg.routes;
        portCfg.routes = routes;
        routes = new HashSet<Route>(portCfg.routes);
        routes.removeAll(oldRoutes);
        oldRoutes.removeAll(portCfg.routes);
        for (Listener listener : listeners)
            // TODO(pino): should we schedule this instead?
            listener.routesChanged(portId, oldRoutes, routes);
    }

    public UUID getId() {
        return portId;
    }

    public short getNum() {
        return portNum;
    }

    public byte[] getMacAddr() {
        return mac;
    }

    public void setMacAddr(byte[] mac) {
        this.mac = mac;
    }

    public void send(byte[] ethernetPacket) {

    }

    public MaterializedRouterPortConfig getVirtualConfig() {
        return portCfg;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
