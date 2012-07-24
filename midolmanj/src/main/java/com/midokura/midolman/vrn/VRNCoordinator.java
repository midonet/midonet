/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.vrn;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.management.JMException;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.Bridge;
import com.midokura.util.eventloop.Reactor;
import com.midokura.midolman.layer3.Router;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.ChainProcessor;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.*;
import com.midokura.midolman.util.Callback1;

public class VRNCoordinator implements ForwardingElement {

    private static final Logger log =
                LoggerFactory.getLogger(VRNCoordinator.class);
    private static final int MAX_HOPS = 10;

    private PortZkManager portMgr;
    private Reactor reactor;
    private Map<UUID, ForwardingElement> forwardingElements;
    private Map<UUID, ForwardingElement> feByPortId;
    private Directory zkDir;
    private String zkBasePath;
    private VRNControllerIface controller;
    private PortSetMap portSetMap;
    private ChainProcessor chainProcessor;
    private PortConfigCache portCache;

    public VRNCoordinator(Directory zkDir, String zkBasePath, Reactor reactor,
            VRNControllerIface ctrl, PortSetMap portSetMap,
            ChainProcessor chainProcessor, PortConfigCache portCache)
            throws StateAccessException {
        this.zkDir = zkDir;
        this.zkBasePath = zkBasePath;
        this.portMgr = new PortZkManager(zkDir, zkBasePath);
        this.reactor = reactor;
        this.controller = ctrl;
        this.portSetMap = portSetMap;
        this.forwardingElements = new HashMap<UUID, ForwardingElement>();
        this.feByPortId = new HashMap<UUID, ForwardingElement>();
        this.chainProcessor = chainProcessor;
        this.portCache = portCache;
        portCache.addWatcher(new Callback1<UUID>() {
            @Override
            public void call(UUID portId) {
                log.debug("Port {} config changed; invalidate flows.", portId);
                controller.invalidateFlowsByElement(portId);
            }
        });
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
                    deviceId, zkDir, zkBasePath, reactor, controller,
                    chainProcessor, portCache);
            break;
          case Bridge:
            fe = new Bridge(
                    deviceId, zkDir, zkBasePath, reactor, controller,
                    chainProcessor, portCache);
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
        PortConfig cfg = portCache.get(portId);
        if (null == cfg) {
            log.error("Failed to get the port configuration for {}", portId);
            return null;
        }
        FEType feType = feTypeOfPort(cfg);
        fe = getForwardingElement(cfg.device_id, feType);
        if (null == fe) {
            log.error("Failed to create a forwarding element of type {} " +
                    "for device {}", feType, cfg.device_id);
        }
        feByPortId.put(portId, fe);
        return fe;
    }


    @Override
    public UUID getId() {
        return null;
    }

    /**
     * Notify all the subscribers that the flow has been removed so that they
     * may free resources. Subscribers may be port, bridge or router UUIDs.
     *
     * @param match The flow match that has been removed.
     * @param subscribers The elements that requested notification.
     * @param inPortID The UUID of the ingress port of the removed flow.
     */
    public void freeFlowResources(
            OFMatch match, Collection<UUID> subscribers, UUID inPortID) {
        for (UUID id : subscribers) {
            // Whether the subscriber is a port, bridge or router, the
            // chainProcessor handles freeing NAT resources.
            // TODO(pino): why doesn't the chainProcessor need the inPortID?
            chainProcessor.freeFlowResources(match, id);
            // If the subscriber is a bridge or router, call its freeResources.
            ForwardingElement fe = forwardingElements.get(id);
            if (null != fe)
                fe.freeFlowResources(match, inPortID);
        }
    }

    @Override
    public void freeFlowResources(OFMatch match, UUID inPortId) {
        // This should never be called.
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroy() {
        for (ForwardingElement fe : forwardingElements.values())
            fe.destroy();
    }

    private PortConfig getPortConfigByUUID(UUID id)
            throws StateAccessException {
        return portMgr.get(id);
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
        PortConfig config = portCache.get(fwdInfo.inPortId);
        if (null != config.portGroupIDs)
            fwdInfo.portGroups.addAll(config.portGroupIDs);
        processOneFE(fwdInfo);
    }

    protected void processOneFE(ForwardInfo fwdInfo)
            throws StateAccessException, KeeperException {
        ForwardingElement fe = getForwardingElementByPort(fwdInfo.inPortId);
        if (null == fe) {
            log.error("Failed to process a packet - failed to get the " +
                    "ForwardingElement for the ingress port: {}", fwdInfo);
            fwdInfo.action = Action.DROP;
            return;
        }
        if (fwdInfo.depth >= MAX_HOPS) {
            // We traversed MAX_HOPS routers without reaching an edge port.
            log.warn("Traversed {} FEs without reaching an edge port; " +
                     "probably a loop - giving up.", MAX_HOPS);
            fwdInfo.action = Action.DROP;
            return;
        }
        fwdInfo.depth++;
        // Keep track of the traversed Forwarding Elements to prevent loops.
        fwdInfo.addTraversedFE(fe.getId());
        // Track the traversed elements (ports or FEs) to aid invalidation.
        fwdInfo.addTraversedElementID(fwdInfo.inPortId);

        applyPortFilter(fwdInfo.inPortId, portCache.get(fwdInfo.inPortId),
                fwdInfo, true);
        if (fwdInfo.action != Action.FORWARD)
            return;

        fwdInfo.addTraversedElementID(fe.getId());
        fe.process(fwdInfo);
        if (fwdInfo.action != Action.PAUSED)
            handleProcessResult(fwdInfo);
    }

    protected void handleProcessResult(ForwardInfo fwdInfo)
            throws StateAccessException, KeeperException {
        log.debug("Process the FE's response {}", fwdInfo);
        if (fwdInfo.action.equals(Action.FORWARD)) {
            log.debug("The FE is forwarding the packet.");
            // If the outPort is a PortSet, the simulation is finished.
            if (portSetMap.containsKey(fwdInfo.outPortId)) {
                log.debug("FE output to port set {}", fwdInfo.outPortId);
                return;
            }
            // Track the traversed elements (ports or FEs) to aid invalidation.
            fwdInfo.addTraversedElementID(fwdInfo.outPortId);
            PortConfig cfg = portCache.get(fwdInfo.outPortId) ;
            if (null == cfg) {
                // Either the config wasn't found or it's not a router port.
                log.error("Packet forwarded to a portId that either "
                        + "has null config or not forwarding element type.");
                // TODO(pino): throw exception instead?
                fwdInfo.action = Action.DROP;
                return;
            }
            applyPortFilter(fwdInfo.outPortId, cfg, fwdInfo, false);
            if (fwdInfo.action != Action.FORWARD)
                return;
            // If the port is logical, the simulation continues.
            if (cfg instanceof LogicalPortConfig) {
                LogicalPortConfig lcfg = (LogicalPortConfig) cfg;
                if (null == lcfg.peerId()) {
                    // Router/Bridge classes should have already handled the
                    // unlinked logical port properly, so something went
                    // terribly wrong if we reach this part of the code.
                    throw new RuntimeException(
                            "Packet forwarded to an unlinked port " +
                            fwdInfo.outPortId);
                }
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

    public void applyPortFilter(UUID portID, PortConfig portCfg,
                                ForwardInfo fwdInfo, boolean inbound)
            throws StateAccessException {
        fwdInfo.action = Action.FORWARD;
        // If inbound, use the before-FE-processing match.  If outbound, use
        // the after-FE-processing match.
        MidoMatch pktMatch = inbound ? fwdInfo.matchIn : fwdInfo.matchOut;
        // Ports themselves don't have ports for packets to be entering/exiting,
        // so set inputPort and outputPort to null.
        UUID filterID = inbound ?
                portCfg.inboundFilter : portCfg.outboundFilter;
        log.debug("Applying {} filter on port {} - chain ID {}", new Object[] {
                inbound ? "inbound" : "outbound", portID, filterID});
        RuleResult result = chainProcessor.applyChain(
                filterID, fwdInfo, pktMatch, portID, true);
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
