package com.midokura.midolman.layer3;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.Router.Action;
import com.midokura.midolman.layer3.Router.ForwardInfo;
import com.midokura.midolman.layer4.NatLeaseManager;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.rules.RuleEngine;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.util.Callback;

public class Network {

    private static final Logger log = LoggerFactory.getLogger(Network.class);
    private static final int MAX_HOPS = 10;

    protected UUID netId;
    private RouterDirectory routerDir;
    private PortDirectory portDir;
    private Reactor reactor;
    private Map<UUID, Router> routers;
    private Map<UUID, Router> routersByPortId;
    // These watchers are interested in routing table and rule changes.
    private Set<Callback<UUID>> watchers;
    // This watches all routing and table changes and then notifies the others.
    private Callback<UUID> routerWatcher;
    // TODO(pino): use Guava's CacheBuilder here.
    private Map<UUID, RouterPortConfig> portIdToConfig;

    public Network(UUID netId, RouterDirectory routerDir,
            PortDirectory portDir, Reactor reactor) {
        this.netId = netId;
        this.routerDir = routerDir;
        this.portDir = portDir;
        this.reactor = reactor;
        this.routers = new HashMap<UUID, Router>();
        this.routersByPortId = new HashMap<UUID, Router>();
        this.watchers = new HashSet<Callback<UUID>>();
        routerWatcher = new Callback<UUID>() {
            public void call(UUID routerId) {
                notifyWatchers(routerId);
            }
        };
        // TODO(pino): use Guava's CacheBuilder here.
        portIdToConfig = new HashMap<UUID, RouterPortConfig>();
    }

    // This maintains consistency of the cached port configs w.r.t ZK.
    private class PortWatcher implements Runnable {
        UUID portId;

        PortWatcher(UUID portId) {
            this.portId = portId;
        }

        @Override
        public void run() {
            // Don't get the new config if the portId's entry has expired.
            if (portIdToConfig.containsKey(portId)) {
                try {
                    refreshPortConfig(portId, this);
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
                }
            }
        }
    };

    public RouterPortConfig getPortConfig(UUID portId) throws IOException,
            ClassNotFoundException, KeeperException, InterruptedException {
        RouterPortConfig rcfg = portIdToConfig.get(portId);
        if (null == rcfg)
            rcfg = refreshPortConfig(portId, null);
        return rcfg;
    }

    private RouterPortConfig refreshPortConfig(UUID portId, PortWatcher watcher)
            throws IOException, ClassNotFoundException, KeeperException,
            InterruptedException {
        if (null == watcher)
            watcher = new PortWatcher(portId);
        PortConfig cfg = portDir.getPortConfigNoRoutes(portId, watcher);
        if (!(cfg instanceof RouterPortConfig))
            return null;
        RouterPortConfig rcfg = RouterPortConfig.class.cast(cfg);
        portIdToConfig.put(portId, rcfg);
        return rcfg;
    }

    public void addWatcher(Callback<UUID> watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Callback<UUID> watcher) {
        watchers.remove(watcher);
    }

    private void notifyWatchers(UUID routerId) {
        for (Callback<UUID> watcher : watchers)
            // TODO(pino): should this be scheduled instead of directly called?
            watcher.call(routerId);
    }

    protected Router getRouter(UUID routerId) throws KeeperException,
            InterruptedException, IOException, ClassNotFoundException {
        Router rtr = routers.get(routerId);
        if (null != rtr)
            return rtr;
        // TODO(pino): replace the following with a real implementation.
        NatMapping natMap = new NatLeaseManager();
        RuleEngine ruleEngine = new RuleEngine(routerDir, routerId, natMap);
        ruleEngine.addWatcher(routerWatcher);
        ReplicatedRoutingTable table = new ReplicatedRoutingTable(routerId,
                routerDir.getRoutingTableDirectory(routerId), 
                CreateMode.EPHEMERAL);
        table.addWatcher(routerWatcher);
        rtr = new Router(routerId, ruleEngine, table, portDir, reactor);
        routers.put(routerId, rtr);
        return rtr;
    }

    public Router getRouterByPort(UUID portId) throws IOException,
            ClassNotFoundException, KeeperException, InterruptedException {
        Router rtr = routersByPortId.get(portId);
        if (null != rtr)
            return rtr;
        RouterPortConfig cfg = getPortConfig(portId);
        // TODO(pino): throw an exception if the config isn't found.
        rtr = getRouter(cfg.device_id);
        routersByPortId.put(cfg.device_id, rtr);
        return rtr;
    }

    public void addPort(L3DevicePort port) throws KeeperException,
            InterruptedException, IOException, ClassNotFoundException {
        UUID routerId = port.getVirtualConfig().device_id;
        Router rtr = getRouter(routerId);
        rtr.addPort(port);
        routersByPortId.put(port.getId(), rtr);
    }

    // This should only be called for materialized ports, not logical ports.
    public void removePort(L3DevicePort port) throws KeeperException,
            InterruptedException, IOException, ClassNotFoundException {
        Router rtr = getRouter(port.getVirtualConfig().device_id);
        rtr.removePort(port);
        routersByPortId.remove(port.getId());
        // TODO(pino): we should clean up any router that isn't a value in the
        // routersByPortId map.
    }

    public void getMacForIp(UUID portId, int nwAddr, Callback<byte[]> cb) {
        Router rtr;
        try {
            rtr = getRouterByPort(portId);
            rtr.getMacForIp(portId, nwAddr, cb);
        } catch (KeeperException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void process(ForwardInfo fwdInfo, Collection<UUID> traversedRouters)
            throws IOException, ClassNotFoundException, KeeperException,
            InterruptedException {
        traversedRouters.clear();
        Router rtr = getRouterByPort(fwdInfo.inPortId);
        if (null == rtr)
            throw new RuntimeException("Packet arrived on a port that hasn't "
                    + "been added to the network yet.");

        for (int i = 0; i < MAX_HOPS; i++) {
            traversedRouters.add(rtr.routerId);
            rtr.process(fwdInfo);
            if (fwdInfo.action.equals(Action.FORWARD)) {
                // Get the port's configuration to see if it's logical.
                RouterPortConfig cfg = getPortConfig(fwdInfo.outPortId);
                if (null == cfg) {
                    // Either the config wasn't found or it's not a router port.
                    log.error("Packet forwarded to a portId that either "
                            + "has null config or not router type.");
                    // TODO(pino): throw exception instead?
                    fwdInfo.action = Action.BLACKHOLE;
                    return;
                }
                if (cfg instanceof LogicalRouterPortConfig) {
                    LogicalRouterPortConfig lcfg = LogicalRouterPortConfig.class
                            .cast(cfg);
                    rtr = getRouterByPort(lcfg.peer_uuid);
                    if (traversedRouters.contains(rtr)) {
                        log.warn("Detected a routing loop.");
                        fwdInfo.action = Action.BLACKHOLE;
                        return;
                    }
                    fwdInfo.matchIn = fwdInfo.matchOut;
                    fwdInfo.inPortId = lcfg.peer_uuid;
                    continue;
                }
            }
            // If we got here, return fwd_action to the caller. One of
            // these holds:
            // 1) the action is OUTPUT and the port type is not logical OR
            // 2) the action is not OUTPUT
            return;
        }
        // If we got here, we traversed MAX_HOPS routers without reaching a
        // materialized port.
        log.warn("Detected a routing loop.");
        fwdInfo.action = Action.BLACKHOLE;
        return;
    }

    public void undoRouterTransformation(Ethernet tunneledEthPkt) {
        // TODO Auto-generated method stub
        
    }
}
