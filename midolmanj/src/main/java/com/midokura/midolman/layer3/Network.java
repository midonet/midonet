/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer3;

import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.management.JMException;
import javax.management.ObjectName;

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
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.rules.RuleEngine;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.CacheWithPrefix;
import com.midokura.midolman.util.Callback;
import com.midokura.midolman.util.Net;

public class Network {

    private static final Logger log = LoggerFactory.getLogger(Network.class);
    private static final int MAX_HOPS = 10;

    protected UUID netId;
    private ChainZkManager chainZkMgr;
    private RuleZkManager ruleZkMgr;
    private PortZkManager portMgr;
    private RouterZkManager routerMgr;
    private Reactor reactor;
    private Cache cache;
    private Map<UUID, Router> routers;
    private Map<UUID, Router> routersByPortId;
    // These watchers are interested in routing table and rule changes.
    private Set<Callback<UUID>> watchers;
    // This watches all routing and table changes and then notifies the others.
    private Callback<UUID> routerWatcher;
    // TODO(pino): use Guava's CacheBuilder here.
    private Map<UUID, PortDirectory.RouterPortConfig> portIdToConfig;

    public Network(UUID netId, PortZkManager portMgr,
            RouterZkManager routerMgr, ChainZkManager chainMgr,
            RuleZkManager ruleMgr, Reactor reactor, Cache cache) {
        this.netId = netId;
        this.portMgr = portMgr;
        this.routerMgr = routerMgr;
        this.chainZkMgr = chainMgr;
        this.ruleZkMgr = ruleMgr;
        this.reactor = reactor;
        this.cache = cache;
        this.routers = new HashMap<UUID, Router>();
        this.routersByPortId = new HashMap<UUID, Router>();
        this.watchers = new HashSet<Callback<UUID>>();
        routerWatcher = new Callback<UUID>() {
            public void call(UUID routerId) {
                notifyWatchers(routerId);
            }
        };
        // TODO(pino): use Guava's CacheBuilder here.
        portIdToConfig = new HashMap<UUID, PortDirectory.RouterPortConfig>();
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
                } catch (Exception e) {
                    log.warn("PortWatcher.log", e);
                }
            }
        }
    };

    public PortDirectory.RouterPortConfig getPortConfig(UUID portId) throws
            ZkStateSerializationException, StateAccessException {
        PortDirectory.RouterPortConfig rcfg = portIdToConfig.get(portId);
        if (null == rcfg)
            rcfg = refreshPortConfig(portId, null);
        return rcfg;
    }

    private PortDirectory.RouterPortConfig refreshPortConfig(UUID portId, PortWatcher watcher)
            throws ZkStateSerializationException, StateAccessException {
        log.debug("refreshPortConfig for {} watcher", portId.toString(), watcher);

        if (null == watcher) {
            watcher = new PortWatcher(portId);
        }

        ZkNodeEntry<UUID, PortConfig> entry = portMgr.get(portId, watcher);
        PortConfig cfg = entry.value;
        if (!(cfg instanceof PortDirectory.RouterPortConfig))
            return null;
        PortDirectory.RouterPortConfig rcfg = PortDirectory.RouterPortConfig.class.cast(cfg);
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

    protected Router getRouter(UUID routerId) throws
            ZkStateSerializationException, StateAccessException, JMException {
        Router rtr = routers.get(routerId);
        if (null != rtr)
            return rtr;
        log.debug("Creating new router instance for {}", routerId.toString());
        Cache cache = new CacheWithPrefix(this.cache, routerId.toString());
        NatMapping natMap = new NatLeaseManager(routerMgr, routerId, cache,
                reactor);
        RuleEngine ruleEngine = new RuleEngine(chainZkMgr, ruleZkMgr, routerId,
                natMap);
        ruleEngine.addWatcher(routerWatcher);
        ReplicatedRoutingTable table = new ReplicatedRoutingTable(routerId,
                routerMgr.getRoutingTableDirectory(routerId),
                CreateMode.EPHEMERAL);
        table.addWatcher(routerWatcher);
        rtr = new Router(routerId, ruleEngine, table, reactor);
        routers.put(routerId, rtr);
        ObjectName oname =
            new ObjectName("com.midokura.midolman.layer3:type=Router,name=" +
                           routerId);
        ManagementFactory.getPlatformMBeanServer().registerMBean(rtr, oname);
        return rtr;
    }

    public Router getRouterByPort(UUID portId) throws
            ZkStateSerializationException, StateAccessException, JMException {
        Router rtr = routersByPortId.get(portId);
        if (null != rtr)
            return rtr;
        PortDirectory.RouterPortConfig cfg = getPortConfig(portId);
        // TODO(pino): throw an exception if the config isn't found.
        rtr = getRouter(cfg.device_id);
        routersByPortId.put(cfg.device_id, rtr);
        return rtr;
    }

    public void addPort(L3DevicePort port) throws
            ZkStateSerializationException, StateAccessException,
            KeeperException, InterruptedException, JMException {
        log.debug("addPort: {}", port);

        UUID routerId = port.getVirtualConfig().device_id;
        Router rtr = getRouter(routerId);
        rtr.addPort(port);
        routersByPortId.put(port.getId(), rtr);
    }

    // This should only be called for materialized ports, not logical ports.
    public void removePort(L3DevicePort port) throws
            ZkStateSerializationException, StateAccessException,
            KeeperException, InterruptedException, JMException {
        log.debug("removePort: {}", port);

        Router rtr = getRouter(port.getVirtualConfig().device_id);
        rtr.removePort(port);
        routersByPortId.remove(port.getId());
        // TODO(pino): we should clean up any router that isn't a value in the
        // routersByPortId map.
    }

    public void getMacForIp(UUID portId, int nwAddr, Callback<MAC> cb)
            throws ZkStateSerializationException {
        log.debug("getMacForIp: port {} in {}", portId, Net.convertIntAddressToString(nwAddr));

        Router rtr;
        try {
            rtr = getRouterByPort(portId);
            rtr.getMacForIp(portId, nwAddr, cb);
        } catch (Exception e) {
            log.warn("getMacForIp", e);
        }
    }

    public void process(ForwardInfo fwdInfo, Collection<UUID> traversedRouters)
            throws ZkStateSerializationException, StateAccessException,
                   JMException {
        log.debug("process: fwdInfo {} traversedRouters {}", fwdInfo,
                  traversedRouters);

        traversedRouters.clear();
        Router rtr = getRouterByPort(fwdInfo.inPortId);
        if (null == rtr)
            throw new RuntimeException("Packet arrived on a port that hasn't "
                    + "been added to the network instance (yet?).");

        for (int i = 0; i < MAX_HOPS; i++) {
            traversedRouters.add(rtr.routerId);
            rtr.process(fwdInfo);
            if (fwdInfo.action.equals(Action.FORWARD)) {
                // Get the port's configuration to see if it's logical.
                PortDirectory.RouterPortConfig cfg = getPortConfig(fwdInfo.outPortId);
                if (null == cfg) {
                    // Either the config wasn't found or it's not a router port.
                    log.error("Packet forwarded to a portId that either "
                            + "has null config or not router type.");
                    // TODO(pino): throw exception instead?
                    fwdInfo.action = Action.BLACKHOLE;
                    return;
                }
                if (cfg instanceof PortDirectory.LogicalRouterPortConfig) {
                    PortDirectory.LogicalRouterPortConfig lcfg = 
                        PortDirectory.LogicalRouterPortConfig.class.cast(cfg);
                    rtr = getRouterByPort(lcfg.peer_uuid);
                    log.debug("Packet exited router on logical port to "
                            + "router {}", rtr.routerId.toString());
                    if (traversedRouters.contains(rtr.routerId)) {
                        log.warn("Detected a routing loop.");
                        fwdInfo.action = Action.BLACKHOLE;
                        return;
                    }
                    fwdInfo.matchIn = fwdInfo.matchOut;
                    fwdInfo.matchOut = null;
                    fwdInfo.inPortId = lcfg.peer_uuid;
                    fwdInfo.outPortId = null;
                    fwdInfo.action = null;
                    fwdInfo.nextHopNwAddr = Route.NO_GATEWAY;
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
        log.warn("More than {} routers traversed; probably a loop; giving up.",
                MAX_HOPS);
        fwdInfo.action = Action.BLACKHOLE;
        return;
    }

    public void undoRouterTransformation(Ethernet tunneledEthPkt) {
        log.debug("getMacForIp: tunneledEthPkt {}", tunneledEthPkt);
        // TODO Auto-generated method stub
    }
}
