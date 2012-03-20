/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.management.JMException;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.ForwardingElement.Action;
import com.midokura.midolman.ForwardingElement.ForwardInfo;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.ReplicatedRoutingTable;
import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.layer3.Router;
import com.midokura.midolman.layer4.NatLeaseManager;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.rules.RuleEngine;
import com.midokura.midolman.state.*;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.CacheWithPrefix;
import com.midokura.midolman.util.Callback;
import com.midokura.midolman.util.Net;

public class VRNCoordinator implements ForwardingElement {

    private static final Logger log = LoggerFactory.getLogger(VRNCoordinator.class);
    private static final int MAX_HOPS = 10;

    protected UUID netId;
    private ChainZkManager chainZkMgr;
    private RuleZkManager ruleZkMgr;
    private PortZkManager portMgr;
    private RouterZkManager routerMgr;
    private Reactor reactor;
    private Cache cache;
    private Map<UUID, ForwardingElement> forwardingElements;
    private Map<UUID, ForwardingElement> feByPortId;
    // These watchers are interested in routing table and rule changes.
    private Set<Callback<UUID>> watchers;
    // This watches all routing and table changes and then notifies the others.
    private Callback<UUID> routerWatcher;
    // TODO(pino): use Guava's CacheBuilder here.
    private Map<UUID, PortConfig> portIdToConfig;

    public VRNCoordinator(UUID netId, PortZkManager portMgr,
            RouterZkManager routerMgr, ChainZkManager chainMgr,
            RuleZkManager ruleMgr, Reactor reactor, Cache cache) {
        this.netId = netId;
        this.portMgr = portMgr;
        this.routerMgr = routerMgr;
        this.chainZkMgr = chainMgr;
        this.ruleZkMgr = ruleMgr;
        this.reactor = reactor;
        this.cache = cache;
        this.forwardingElements = new HashMap<UUID, ForwardingElement>();
        this.feByPortId = new HashMap<UUID, ForwardingElement>();
        this.watchers = new HashSet<Callback<UUID>>();
        routerWatcher = new Callback<UUID>() {
            public void call(UUID routerId) {
                notifyWatchers(routerId);
            }
        };
        // TODO(pino): use Guava's CacheBuilder here.
        portIdToConfig = new HashMap<UUID, PortConfig>();
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

    public PortConfig getPortConfig(UUID portId) throws
            ZkStateSerializationException, StateAccessException {
        PortConfig pcfg = portIdToConfig.get(portId);
        if (null == pcfg)
            pcfg = refreshPortConfig(portId, null);
        return pcfg;
    }

    private PortConfig refreshPortConfig(UUID portId, PortWatcher watcher)
            throws ZkStateSerializationException, StateAccessException {
        log.debug("refreshPortConfig for {} watcher", portId.toString(), watcher);

        if (null == watcher) {
            watcher = new PortWatcher(portId);
        }

        ZkNodeEntry<UUID, PortConfig> entry = portMgr.get(portId, watcher);
        PortConfig cfg = entry.value;
        portIdToConfig.put(portId, cfg);
        return cfg;
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

    protected ForwardingElement getForwardingElement(UUID deviceId)
            throws StateAccessException {
        ForwardingElement fe = forwardingElements.get(deviceId);
        if (null != fe)
            return fe;
        log.debug("Creating new forwarding element instance for {}", deviceId);
        // XXX: Create router or bridge.
        Cache cache = new CacheWithPrefix(this.cache, deviceId.toString());
        NatMapping natMap = new NatLeaseManager(routerMgr, deviceId, cache,
                reactor);
        RuleEngine ruleEngine = new RuleEngine(chainZkMgr, ruleZkMgr, deviceId,
                natMap);
        ruleEngine.addWatcher(routerWatcher);
        ReplicatedRoutingTable table = new ReplicatedRoutingTable(deviceId,
                routerMgr.getRoutingTableDirectory(deviceId),
                CreateMode.EPHEMERAL);
        table.addWatcher(routerWatcher);
        ArpTable arpTable = new ArpTable(routerMgr.getArpTableDirectory(deviceId));
        arpTable.start();
        fe = new Router(deviceId, ruleEngine, table, arpTable, reactor);
        forwardingElements.put(deviceId, fe);
        return fe;
    }

    public ForwardingElement getForwardingElementByPort(UUID portId)
            throws StateAccessException {
        ForwardingElement fe = feByPortId.get(portId);
        if (null != fe)
            return fe;
        PortConfig cfg = getPortConfig(portId);
        // TODO(pino): throw an exception if the config isn't found.
        fe = getForwardingElement(cfg.device_id);
        feByPortId.put(cfg.device_id, fe);
        return fe;
    }


    @Override
    public UUID getId() {
        return null;
    }

    @Override
    public void freeFlowResources(OFMatch match) {
    }

    @Override
    public void addPort(UUID portId) throws
            ZkStateSerializationException, StateAccessException,
            KeeperException, InterruptedException, JMException {
        log.debug("addPort: {}", portId);
        L3DevicePort port = null;  // XXX: Go from portId to port

        UUID deviceId = port.getVirtualConfig().device_id;
        ForwardingElement fe = getForwardingElement(deviceId);
        fe.addPort(portId);
        feByPortId.put(portId, fe);
    }

    // This should only be called for materialized ports, not logical ports.
    @Override
    public void removePort(UUID portId) throws
            ZkStateSerializationException, StateAccessException,
            KeeperException, InterruptedException, JMException {
        log.debug("removePort: {}", portId);
        L3DevicePort port = null;  // XXX: Go from portId to port

        ForwardingElement fe = getForwardingElement(port.getVirtualConfig().device_id);
        fe.removePort(portId);
        feByPortId.remove(portId);
        // TODO(pino): we should clean up any router that isn't a value in the
        // routersByPortId map.
    }

    @Override
    public void process(ForwardInfo fwdInfo) throws StateAccessException {
        log.debug("process: fwdInfo {} traversedRouters {}", fwdInfo,
                  fwdInfo.notifyFEs);

        fwdInfo.notifyFEs.clear();
        processOneFE(fwdInfo);
    }

    protected void processOneFE(ForwardInfo fwdInfo) throws StateAccessException {
        ForwardingElement fe = getForwardingElementByPort(fwdInfo.inPortId);
        if (null == fe)
            throw new RuntimeException("Packet arrived on a port that hasn't "
                    + "been added to the network instance (yet?).");

        fwdInfo.notifyFEs.add(fe.getId());
        fwdInfo.depth++;
        if (fwdInfo.depth > MAX_HOPS) {
            // If we got here, we traversed MAX_HOPS routers without reaching a
            // materialized port.
            log.warn("More than {} FEs traversed; probably a loop; " +
                     "giving up.", MAX_HOPS);
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }
        fe.process(fwdInfo);
        if (fwdInfo.action != Action.PAUSED)
            handleProcessResult(fwdInfo);
    }

    protected void handleProcessResult(ForwardInfo fwdInfo)
            throws StateAccessException {
        if (fwdInfo.action.equals(Action.FORWARD)) {
            // Get the port's configuration to see if it's logical.
            PortConfig cfg = getPortConfig(fwdInfo.outPortId);
            if (null == cfg) {
                // Either the config wasn't found or it's not a router port.
                log.error("Packet forwarded to a portId that either "
                        + "has null config or not forwarding element type.");
                // TODO(pino): throw exception instead?
                fwdInfo.action = Action.BLACKHOLE;
                return;
            }
            if (cfg instanceof PortDirectory.LogicalRouterPortConfig) { //XXX
                PortDirectory.LogicalRouterPortConfig lcfg =
                    PortDirectory.LogicalRouterPortConfig.class.cast(cfg);
                ForwardingElement fe = getForwardingElementByPort(lcfg.peer_uuid);
                log.debug("Packet exited router on logical port to "
                        + "router {}", fe.getId().toString());
                if (fwdInfo.notifyFEs.contains(fe.getId())) {
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

                // fwd_action was OUTPUT, and port type is logical.  Continue
                // the simulation.
                processOneFE(fwdInfo);
                return;
            }
        }
        // If we got here, return fwd_action to the caller. One of
        // these holds:
        // 1) the action is OUTPUT and the port type is not logical OR
        // 2) the action is not OUTPUT
        return;
    }
}
