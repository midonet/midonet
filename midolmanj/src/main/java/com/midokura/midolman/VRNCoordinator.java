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

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.Router;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.ChainProcessor;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.LogicalPortConfig;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortSetMap;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.Callback1;

public class VRNCoordinator implements ForwardingElement {

    private static final Logger log =
                LoggerFactory.getLogger(VRNCoordinator.class);
    private static final int MAX_HOPS = 10;

    private PortZkManager portMgr;
    private Reactor reactor;
    private Map<UUID, ForwardingElement> forwardingElements;
    private Map<UUID, ForwardingElement> feByPortId;
    // These watchers are interested in routing table and rule changes.
    private Set<Callback1<UUID>> watchers;
    // TODO(pino): use Guava's CacheBuilder here.
    // TODO(pino): the port configurations have to be watched for changes.
    private Map<UUID, PortConfig> portIdToConfig;
    private Directory zkDir;
    private String zkBasePath;
    private VRNControllerIface ctrl;
    private PortSetMap portSetMap;
    private ChainProcessor chainProcessor;

    public VRNCoordinator(Directory zkDir, String zkBasePath, Reactor reactor,
                          Cache cache, VRNControllerIface ctrl,
                          PortSetMap portSetMap, ChainProcessor chainProcessor)
            throws StateAccessException {
        this.zkDir = zkDir;
        this.zkBasePath = zkBasePath;
        this.portMgr = new PortZkManager(zkDir, zkBasePath);
        this.reactor = reactor;
        this.ctrl = ctrl;
        this.portSetMap = portSetMap;
        this.forwardingElements = new HashMap<UUID, ForwardingElement>();
        this.feByPortId = new HashMap<UUID, ForwardingElement>();
        this.watchers = new HashSet<Callback1<UUID>>();
        this.chainProcessor = chainProcessor;
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
    }

    public PortConfig getPortConfig(UUID portId) throws
            ZkStateSerializationException, StateAccessException {
        PortConfig pcfg = portIdToConfig.get(portId);
        if (null == pcfg)
            pcfg = refreshPortConfig(portId, null);
        return pcfg;
    }

    private PortConfig refreshPortConfig(UUID portId, PortWatcher watcher)
            throws ZkStateSerializationException, StateAccessException {
        log.debug("refreshPortConfig for {} watcher", portId.toString(),
                  watcher);

        if (null == watcher) {
            watcher = new PortWatcher(portId);
        }

        ZkNodeEntry<UUID, PortConfig> entry = portMgr.get(portId, watcher);
        PortConfig cfg = entry.value;
        portIdToConfig.put(portId, cfg);
        return cfg;
    }

    public void addWatcher(Callback1<UUID> watcher) {
        watchers.add(watcher);
    }

    protected enum FEType { Router, Bridge, DontConstruct }

    protected ForwardingElement getForwardingElement(UUID deviceId,
            FEType feType) throws StateAccessException, KeeperException {
        // TODO: Track down why we're throwing a KeeperException, as these
        // should never escape StateAccessException.
        ForwardingElement fe = forwardingElements.get(deviceId);
        if (null != fe)
            return fe;
        log.debug("Getting forwarding element instance for {}", deviceId);
        switch (feType) {
          case Router:
            fe = new Router(
                    deviceId, zkDir, zkBasePath, reactor, ctrl, chainProcessor);
            break;
          case Bridge:
            fe = new Bridge(
                    deviceId, zkDir, zkBasePath, reactor, ctrl, chainProcessor);
            break;
          case DontConstruct:
            fe = null;
            break;
          default:
            log.error("getForwardingElement: Unknown FEType {}", feType);
            fe = null;
            break;
        }
        if (fe != null)
            forwardingElements.put(deviceId, fe);
        return fe;
    }

    protected FEType feTypeOfPort(PortConfig cfg) {
        if (cfg instanceof PortDirectory.RouterPortConfig)
            return FEType.Router;
        else if (cfg instanceof PortDirectory.BridgePortConfig)
            return FEType.Bridge;
        else {
            log.error("feTypeOfPort: Unknown PortConfig type for {}", cfg);
            return FEType.DontConstruct;
        }
    }

    public ForwardingElement getForwardingElementByPort(UUID portId)
            throws StateAccessException, KeeperException {
        ForwardingElement fe = feByPortId.get(portId);
        if (null != fe)
            return fe;
        PortConfig cfg = getPortConfig(portId);
        // TODO(pino): throw an exception if the config isn't found.
        FEType feType = feTypeOfPort(cfg);
        fe = getForwardingElement(cfg.device_id, feType);
        feByPortId.put(portId, fe);
        return fe;
    }


    @Override
    public UUID getId() {
        return null;
    }

    @Override
    public void freeFlowResources(OFMatch match, UUID inPortId) {
    }

    @Override
    public void destroy() {
        for (ForwardingElement fe : forwardingElements.values())
            fe.destroy();
    }

    private PortConfig getPortConfigByUUID(UUID id)
            throws StateAccessException {
        ZkNodeEntry<UUID, PortConfig> entry = portMgr.get(id);
        return entry.value;
    }

    @Override
    public void addPort(UUID portId) throws StateAccessException,
            KeeperException, InterruptedException, JMException {
        log.debug("addPort: {}", portId);
        PortConfig portCfg = getPortConfigByUUID(portId);
        UUID deviceId = portCfg.device_id;
        FEType feType = feTypeOfPort(portCfg);
        ForwardingElement fe = getForwardingElement(deviceId, feType);
        fe.addPort(portId);
        feByPortId.put(portId, fe);
    }

    // This should only be called for materialized ports, not logical ports.
    @Override
    public void removePort(UUID portId) throws StateAccessException {
        log.debug("removePort: {}", portId);
        ForwardingElement fe = feByPortId.remove(portId);
        if (null != fe)
            fe.removePort(portId);
    }

    @Override
    public void process(ForwardInfo fwdInfo)
            throws StateAccessException, KeeperException {
        log.debug("process: fwdInfo {}", fwdInfo);
        PortConfig config = getPortConfig(fwdInfo.inPortId);
        if (null != config.portGroupIDs)
            fwdInfo.portGroups.addAll(config.portGroupIDs);
        processOneFE(fwdInfo);
    }

    protected void processOneFE(ForwardInfo fwdInfo)
            throws StateAccessException, KeeperException {
        ForwardingElement fe = getForwardingElementByPort(fwdInfo.inPortId);
        if (null == fe)
            throw new RuntimeException("Packet arrived on a port that hasn't "
                    + "been added to the network instance (yet?).");

        if (fwdInfo.depth >= MAX_HOPS) {
            // We traversed MAX_HOPS routers without reaching an edge port.
            log.warn("Traversed {} FEs without reaching an edge port; " +
                     "probably a loop - giving up.", MAX_HOPS);
            fwdInfo.action = Action.DROP;
            return;
        }
        fwdInfo.depth++;
        fwdInfo.addTraversedFE(fe.getId());

        applyPortFilter(getPortConfig(fwdInfo.inPortId), fwdInfo, true);
        if (fwdInfo.action != Action.FORWARD)
            return;
        fe.process(fwdInfo);
        if (fwdInfo.action != Action.PAUSED)
            handleProcessResult(fwdInfo);
    }

    protected void handleProcessResult(ForwardInfo fwdInfo)
            throws StateAccessException, KeeperException {
        log.debug("Process the FE's response {}", fwdInfo);
        if (fwdInfo.action.equals(Action.FORWARD)) {
            log.debug("The FE is forwarding the packet from a port.");
            // If the outPort is a PortSet, the simulation is finished.
            if (portSetMap.containsKey(fwdInfo.outPortId)) {
                // TODO(pino): implement Firewall simulation for FLOOD.
                log.debug("FE output to port set {}", fwdInfo.outPortId);
                return;
            }
            PortConfig cfg = getPortConfig(fwdInfo.outPortId);
            if (null == cfg) {
                // Either the config wasn't found or it's not a router port.
                log.error("Packet forwarded to a portId that either "
                        + "has null config or not forwarding element type.");
                // TODO(pino): throw exception instead?
                fwdInfo.action = Action.DROP;
                return;
            }
            applyPortFilter(cfg, fwdInfo, false);
            if (fwdInfo.action != Action.FORWARD)
                return;
            // If the port is logical, the simulation continues.
            if (cfg instanceof LogicalPortConfig) {
                LogicalPortConfig lcfg = (LogicalPortConfig) cfg;
                ForwardingElement fe = getForwardingElementByPort(lcfg.peerId());
                log.debug("Packet exited FE on logical port to FE {}", fe);
                int timesTraversed = fwdInfo.getTimesTraversed(fe.getId());
                if (timesTraversed > 2) {
                    log.warn("Detected a loop. FE {} saw the packet {} " +
                             "times: {}", new Object[] { fe.getId(),
                             timesTraversed, fwdInfo.flowMatch });
                    fwdInfo.action = Action.DROP;
                    return;
                }
                fwdInfo.matchIn = fwdInfo.matchOut;
                fwdInfo.matchOut = null;
                fwdInfo.inPortId = lcfg.peerId();
                fwdInfo.outPortId = null;
                fwdInfo.action = null;

                // fwd_action was OUTPUT, and port type is logical.  Continue
                // the simulation.
                processOneFE(fwdInfo);
                return;
            }
            log.debug("Packet exited FE on materialized port or set.");
        }
        // If we got here, return fwd_action to the caller.  One of these holds:
        // 1) the action is OUTPUT and the port type is not logical, OR
        // 2) the action is not OUTPUT
    }

    public void applyPortFilter(
            PortConfig portCfg, ForwardInfo fwdInfo, boolean inbound)
            throws StateAccessException {
        fwdInfo.action = Action.FORWARD;
        // If inbound, use the before-FE-processing match.  If outbound, use
        // the after-FE-processing match.
        MidoMatch pktMatch = inbound ? fwdInfo.matchIn : fwdInfo.matchOut;
        // Ports themselves don't have ports for packets to be entering/exiting,
        // so set inputPort and outputPort to null.
        RuleResult result = chainProcessor.applyChain(
                inbound ? portCfg.inboundFilter : portCfg.outboundFilter,
                fwdInfo.flowMatch, pktMatch, null, null,
                inbound ? fwdInfo.inPortId : fwdInfo.outPortId,
                fwdInfo.portGroups);
        // TODO(pino): add the code that handles the removal notification
        if (result.trackConnection)
            fwdInfo.addRemovalNotification(
                    inbound ? fwdInfo.inPortId : fwdInfo.outPortId);
        if (result.action.equals(RuleResult.Action.DROP)) {
            fwdInfo.action = Action.DROP;
            return;
        }
        if (result.action.equals(RuleResult.Action.REJECT)) {
            // TODO(pino): should we send ICMPs from the port's filter/firewall?
            //sendICMPforLocalPkt(fwdInfo, UNREACH_CODE.UNREACH_FILTER_PROHIB);
            fwdInfo.action = Action.DROP;
            return;
        }
        if (!result.action.equals(RuleResult.Action.ACCEPT))
            throw new RuntimeException("Port's filter returned an action other "
                    + "than ACCEPT, DROP or REJECT.");
        if (!pktMatch.equals(result.match)) {
            if (inbound)
                fwdInfo.matchIn = result.match;
            else
                fwdInfo.matchOut = result.match;
        }
    }
}
