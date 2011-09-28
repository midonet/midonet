package com.midokura.midolman;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
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

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

public class L3DevicePort {

    public interface Listener {
        void configChanged(UUID portId,
                PortConfig.MaterializedRouterPortConfig old,
                PortConfig.MaterializedRouterPortConfig current);

        void routesChanged(UUID portId, Collection<Route> removed,
                Collection<Route> added);
    }

    private PortZkManager portMgr;
    private RouteZkManager routeMgr;
    private short portNum;
    private UUID portId;
    private MAC mac;
    private ControllerStub stub;
    private PortWatcher portWatcher;
    private RoutesWatcher routesWatcher;
    private PortConfig.MaterializedRouterPortConfig portCfg;
    private Set<Listener> listeners;
    
    private final Logger log;

    public L3DevicePort(PortZkManager portMgr, RouteZkManager routeMgr,
            UUID portId, short portNum, MAC mac, ControllerStub stub)
            throws Exception {
        
        log = LoggerFactory.getLogger(L3DevicePort.class.getCanonicalName() + '.' + portId);
        
        this.portMgr = portMgr;
        this.routeMgr = routeMgr;
        this.portId = portId;
        this.portNum = portNum;
        this.mac = mac;
        this.stub = stub;
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
            } catch (IOException e) {
                // TODO Auto-generated catch block
                log.warn("PortWatcher.run", e);
            } catch (ClassNotFoundException e) {
                // TODO Auto-generated catch block
                log.warn("PortWatcher.run", e);
            } catch (KeeperException e) {
                // TODO Auto-generated catch block
                log.warn("PortWatcher.run", e);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                log.warn("PortWatcher.run", e);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                log.warn("PortWatcher.run", e);
            }
        }
    }

    private void updatePortConfig() throws Exception {
        ZkNodeEntry<UUID, PortConfig> entry = portMgr.get(portId,
                portWatcher);
        PortConfig cfg = entry.value;
        if (!(cfg instanceof PortConfig.MaterializedRouterPortConfig))
            throw new Exception("L3DevicePort's virtual configuration isn't "
                    + "a MaterializedRouterPortConfig.");
        PortConfig.MaterializedRouterPortConfig oldCfg = portCfg;
        portCfg = PortConfig.MaterializedRouterPortConfig.class.cast(cfg);
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
                // TODO Auto-generated catch block
                log.warn("RoutesWatcher.run", e);
            }
        }
    }

    private void updateRoutes() throws StateAccessException,
            ZkStateSerializationException {
        log.debug("updaetRoutes");
        
        List<ZkNodeEntry<UUID, Route>> entries = routeMgr.listPortRoutes(
                portId, routesWatcher);
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

    public short getNum() {
        return portNum;
    }

    public MAC getMacAddr() {
        return mac;
    }

    public void send(byte[] pktData) {
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(portNum, (short) 0));
        stub.sendPacketOut(ControllerStub.UNBUFFERED_ID, OFPort.OFPP_CONTROLLER
                .getValue(), actions, pktData);
    }

    public PortConfig.MaterializedRouterPortConfig getVirtualConfig() {
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
        return "L3DevicePort [portNum=" + portNum + ", portId=" + portId + ", mac=" + mac
                + ", portCfg=" + portCfg + "]";
    }
}
