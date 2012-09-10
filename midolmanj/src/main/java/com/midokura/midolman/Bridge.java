/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.management.JMException;

import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import com.midokura.midolman.state.zkManagers.PortZkManager;
import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.eventloop.Reactor;
import com.midokura.packets.ARP;
import com.midokura.packets.Ethernet;
import com.midokura.packets.IPv4;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midolman.rules.ChainEngine;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.*;
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.PortDirectory.LogicalBridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.vrn.ForwardInfo;
import com.midokura.midolman.vrn.ForwardingElement;
import com.midokura.midolman.vrn.VRNControllerIface;


public class Bridge implements ForwardingElement {

    private final Logger log = LoggerFactory.getLogger(Bridge.class);

    UUID bridgeId;
    // The "flood ID" is used to tag flooded flows so that they can be
    // invalidated independently of the bridge's other flows.
    UUID floodElementID;
    MacPortMap macPortMap;
    long mac_port_timeout = 40*1000;
    Reactor reactor;
    PortZkManager portMgr;
    private BridgeZkManager bridgeMgr;
    private Runnable logicalPortsWatcher;
    private HashSet<UUID> logicalPortIDs = new HashSet<UUID>();
    private Map<MAC, UUID> rtrMacToLogicalPortId = new HashMap<MAC, UUID>();
    private Map<IntIPv4, MAC> rtrIpToMac = new HashMap<IntIPv4, MAC>();
    private Map<UUID, IntIPv4> brPortIdToRtrPortIp =
            new HashMap<UUID, IntIPv4>();
    // The delayed deletes for macPortMap.
    Map<MAC, PortFuture> delayedDeletes = new HashMap<MAC, PortFuture>();
    Map<MacPort, Integer> flowCount = new HashMap<MacPort, Integer>();
    private MacPortWatcher macToPortWatcher;
    private Set<UUID> localPorts = new HashSet<UUID>();
    private VRNControllerIface controller;
    private BridgeConfig myConfig;
    private ChainEngine chainEngine;
    private PortConfigCache portCache;

    public Bridge(UUID brId, Directory zkDir, String zkBasePath,
                  Reactor reactor, VRNControllerIface ctrl,
                  ChainEngine chainEngine, PortConfigCache portCache)
            throws StateAccessException {
        this.bridgeId = brId;
        // The "flood ID" can be locally generated - it's only used on this VM.
        this.floodElementID = bridgeIdToFloodId(bridgeId);
        this.reactor = reactor;
        this.controller = ctrl;
        this.chainEngine = chainEngine;
        this.portCache = portCache;
        portMgr = new PortZkManager(zkDir, zkBasePath);
        this.bridgeMgr = new BridgeZkManager(zkDir, zkBasePath);
        ZkPathManager pathMgr = new ZkPathManager(zkBasePath);
        try {
            macPortMap = new MacPortMap(zkDir.getSubDirectory(
                    pathMgr.getBridgeMacPortsPath(bridgeId)));
        } catch (KeeperException e) {
            throw new StateAccessException(e);
        }
        macToPortWatcher = new MacPortWatcher();
        macPortMap.addWatcher(macToPortWatcher);
        macPortMap.start();
        // TODO(pino): how should clear() handle this field?
        logicalPortsWatcher = new Runnable() {
            public void run() {
                updateLogicalPorts();
            }
        };
        updateLogicalPorts();
        // Get the Bridge's configuration and watch it for changes.
        myConfig = bridgeMgr.get(bridgeId,
                new Runnable() {
                    public void run() {
                        try {
                            BridgeConfig config =
                                    bridgeMgr.get(bridgeId, this);
                            if (!myConfig.equals(config)) {
                                myConfig = config;
                                log.debug("Bridge {} has a new config {} - " +
                                          "invalidated its flow matches.",
                                          bridgeId, myConfig);
                                controller.invalidateFlowsByElement(bridgeId);
                            }
                        } catch (StateAccessException e) {
                            log.error("Failed to update bridge config", e);
                        }
                    }
                });
    }

    public static UUID bridgeIdToFloodId(UUID bridgeId) {
        // Purposely reverse least/most significant bits and add 1.
        return new UUID(bridgeId.getLeastSignificantBits() + 1,
                bridgeId.getMostSignificantBits()+1);
    }

    @Override
    public String toString() {
        return "Bridge{" + "bridgeId=" + bridgeId + '}';
    }

    @Override
    public void process(ForwardInfo fwdInfo)
            throws StateAccessException, KeeperException {
        log.debug("Simulating packet traversing L2 bridge.");
        MAC srcDlAddress = new MAC(fwdInfo.matchIn.getDataLayerSource());
        MAC dstDlAddress = new MAC(fwdInfo.matchIn.getDataLayerDestination());

        // Drop the packet if its L2 source is a multicast address.
        if (Ethernet.isMcast(srcDlAddress)) {
            fwdInfo.action = Action.DROP;
            log.info("multicast src MAC, dropping packet");
            return;
        }
        fwdInfo.action = Action.FORWARD;
        // If the ingress port is materialized, ask for removal notification.
        if (!(portCache.get(fwdInfo.inPortId) instanceof
                LogicalBridgePortConfig)) {
            log.debug("Bridge {} asking for flow removal notification for " +
                      "port {}", bridgeId, fwdInfo.inPortId);
            fwdInfo.addRemovalNotification(bridgeId);
        }
        fwdInfo.matchOut = fwdInfo.matchIn.clone();
        fwdInfo.outPortId = null;

        // Apply pre-bridging rules.
        RuleResult res = chainEngine.applyChain(
                myConfig.inboundFilter, fwdInfo, fwdInfo.matchIn,
                this.bridgeId, false);
        if (res.trackConnection)
            fwdInfo.addRemovalNotification(bridgeId);
        if (res.action.equals(RuleResult.Action.DROP) ||
                res.action.equals(RuleResult.Action.REJECT)) {
            // TODO: Send an ICMP !X for REJECT? If so, with what srcIP?
            fwdInfo.action = Action.DROP;
            return;
        }
        if (!res.action.equals(RuleResult.Action.ACCEPT)) {
            throw new RuntimeException("Pre-bridging returned an action " +
                        "other than ACCEPT, DROP or REJECT.");
        }

        // Is the destination a multicast address?
        if (Ethernet.isMcast(dstDlAddress)) {
            // If it's an IPv4 ARP, is it for a router's IP?
            if (Ethernet.isBroadcast(dstDlAddress)
                    && fwdInfo.matchIn.getDataLayerType() == ARP.ETHERTYPE
                    && ((ARP)fwdInfo.pktIn.getPayload()).getProtocolType()
                            == IPv4.ETHERTYPE
                    && rtrIpToMac.containsKey(new IntIPv4(
                            fwdInfo.matchIn.getNetworkDestination()))) {
                MAC rtrMAC = rtrIpToMac.get(new IntIPv4(
                        fwdInfo.matchIn.getNetworkDestination()));
                fwdInfo.outPortId = rtrMacToLogicalPortId.get(rtrMAC);
            } else { // Not an ARP request for a router's port's address
                // Flood to materialized ports only. Routers drop non-ARP
                // packets with multicast destination. Use the Bridge's own ID
                // as the PortSet ID.
                log.info("flooding packet to {} to port set {}",
                        dstDlAddress, bridgeId);
                fwdInfo.outPortId = bridgeId;
            }
        } else { // It's a unicast.
            // Is the MAC associated with a materialized port?
            fwdInfo.outPortId = macPortMap.get(dstDlAddress);
            // Otherwise, is the MAC associated with a logical port?
            if (null == fwdInfo.outPortId)
                fwdInfo.outPortId = rtrMacToLogicalPortId.get(dstDlAddress);
            // Otherwise, flood the packet.
            if (null == fwdInfo.outPortId)
                fwdInfo.outPortId = bridgeId;
        }
        // Do mac-address learning if the source is not another FE.
        if (!rtrMacToLogicalPortId.containsKey(srcDlAddress)) {
            // Learn the MAC source address.
            increaseMacPortFlowCount(srcDlAddress, fwdInfo.getInPortId());
            // We need flow removal notification so that the number of flows
            // can be used as a reference count on the mac-port mapping.
            fwdInfo.addRemovalNotification(bridgeId);
        }

        // Apply post-bridging rules.
        res = chainEngine.applyChain(myConfig.outboundFilter, fwdInfo,
                res.match, this.bridgeId, false);
        if (res.trackConnection)
            fwdInfo.addRemovalNotification(bridgeId);
        if (res.action.equals(RuleResult.Action.DROP) ||
                res.action.equals(RuleResult.Action.REJECT)) {
            // TODO: Send an ICMP !X for REJECT?  If so, with what srcIP?
            fwdInfo.action = Action.DROP;
        }
        if (!res.action.equals(RuleResult.Action.ACCEPT)) {
            throw new RuntimeException("Post-bridging returned an action " +
                        "other than ACCEPT, DROP or REJECT.");
        }

        // If the packet is being forwarded and flooded, then add the "flood ID"
        // to the list of traversed elements. This allows us to invalidate only
        // flooded flows (instead of all the bridge's flows) when we learn
        // a new mac-port mapping.
        if (fwdInfo.action == Action.FORWARD &&
                bridgeId.equals(fwdInfo.outPortId))
            fwdInfo.addTraversedElementID(floodElementID);

        return;
    }

    @Override
    public void addPort(UUID portId) throws StateAccessException,
            KeeperException, InterruptedException, JMException {
        // No invalidation needed: controller invalidates on Port-Loc changes.
        localPorts.add(portId);
        if (localPorts.size() == 1)
            controller.subscribePortSet(bridgeId);
        controller.addLocalPortToSet(bridgeId, portId);
    }

    @Override
    public void removePort(UUID portId) throws StateAccessException {
        // No invalidation needed: controller invalidates on Port-Loc changes.
        localPorts.remove(portId);
        controller.removeLocalPortFromSet(bridgeId, portId);
        if (localPorts.size() == 0)
            controller.unsubscribePortSet(bridgeId);
        log.info("removePort - expire MAC-port entries for port {}", portId);
        List<MAC> macList = macPortMap.getByValue(portId);
        for (MAC mac : macList) {
            log.info("Removing mapping from MAC {} to port {}", mac, portId);
            flowCount.remove(new MacPort(mac, portId));
            // Only schedule the mac-port mapping for expiration. We want it
            // to be remembered for a short time in case the port is migrating.
            expireMacPortEntry(mac, portId);
        }
    }

    @Override
    public UUID getId() {
        return bridgeId;
    }

    @Override
    public void freeFlowResources(OFMatch match, UUID inPortId) {
        // NOTE: resources used by the bridge's filters are managed by a
        // a NatMapping that is handled by the singleton ChainEngine.

        // Note: we only subscribe to removal notification for flows that arrive
        // via materialized ports. This makes it possible to use the flow
        // match's own source hardware address to reference count learned macs.
        log.debug("freeFlowResources: {} {}", match, inPortId);
        if (inPortId == null) {
            log.warn("freeFlowResources port has no UUID");
            return;
        }

        // Decrement ref count
        MacPort flowcountKey = new MacPort(new MAC(match.getDataLayerSource()),
                inPortId);
        Integer count = flowCount.get(flowcountKey);
        if (count == null) {
            log.info("freeFlowResources MAC {} port#{} id:{} but no flowCount",
                    new Object[] { flowcountKey.mac, match.getInputPort(),
                            inPortId });
        } else if (count > 1) {
            flowCount.put(flowcountKey, new Integer(count-1));
            log.info("freeFlowResources decreased flow count for srcMac {} " +
                    "from port#{} id:{} to {}", new Object[] { flowcountKey.mac,
                    match.getInputPort(), inPortId, count-1 });
        } else {
            log.info("freeFlowResources last flow for srcMac {} on port #{} " +
                    "id:{} removed at {}", new Object[] { flowcountKey.mac,
                    match.getInputPort(), inPortId, new Date() });
            flowCount.remove(flowcountKey);
            expireMacPortEntry(flowcountKey.mac, inPortId);
        }
    }

    @Override
    public void destroy() {
        log.info("destroy");
        controller.invalidateFlowsByElement(bridgeId);
        // TODO(pino): the bridge should completely tear down its state and
        // TODO: throw exceptions if it's called again. Old callbacks should
        // TODO: unregistered or result in no-ops.

        for (PortFuture pF : delayedDeletes.values())
            pF.future.cancel(false);
        delayedDeletes.clear();
        for (MacPort mp : flowCount.keySet()) {
            try {
                macPortMap.removeIfOwner(mp.mac);
            } catch (KeeperException e) {
                log.error("Error while destroying", e);
            } catch (InterruptedException e) {
                log.error("Error while destroying", e);
            }
        }
        flowCount.clear();
        macPortMap.removeWatcher(macToPortWatcher);
        macPortMap.stop();
        // .stop() includes a .clear() of the underlying map, so we don't
        // need to clear out the entries here.
    }

    static private class PortFuture {
        // Pair<Port, Future>
        public UUID port;
        public Future future;
        public PortFuture(UUID p, Future f) { port = p; future = f; }
    }

    static private class MacPort {
        // Pair<Mac, Port>
        public MAC mac;
        public UUID port;

        public MacPort(MAC addr, UUID uuid) {
            mac = addr;
            port = uuid;
        }

        public boolean equals(Object rhs) {
            if (this == rhs)
                return true;
            if (null == rhs)
                return false;
            if (!(rhs instanceof MacPort))
                return false;

            return mac.equals(((MacPort)rhs).mac) &&
                   port.equals(((MacPort)rhs).port);
        }

        public int hashCode() {
            return mac.hashCode() ^ port.hashCode();
        }
    }

    private class MacPortWatcher implements
            ReplicatedMap.Watcher<MAC, UUID> {
        public void processChange(MAC mac, UUID oldPortId, UUID newPortId) {
            // If we own the new port, we already know about the change.
            if (localPorts.contains(newPortId))
                return;
            // We want to invalidate any flows that would not idle out.
            // Flows from the mac and inPort==oldPortId, will idle out.
            // Flows to the mac would have outPort==oldPortId (or bridgeId in
            // the flood case) and need to be invalidated to be re-computed.
            if (null == oldPortId) {
                // This is not needed for correctness - any flows to this mac
                // would have been flooded and therefore would continue to reach
                // their destination. However, a unicast flow is more efficient.
                log.debug("Learned Mac-Port mapping: {} to {}. Invalidate " +
                          "flooded flows.", mac, newPortId);
                controller.invalidateFlowsByElement(floodElementID);

                /* TODO(pino): Invalidate by destination mac (more precise):
                 * We don't have to worry about flooded flows that are tunneled
                 * to this host: they'll idle out because the ingress controller
                 * will stop using the PortSetID as the tunnelID.
                 * Any other flooded flows directed to this mac ingressed
                 * locally, and on a materialized port on the same bridge;
                 * they can therefore be invalidated by destination MAC.
                 */
            } else {
                // TODO(pino): invalidate only flows that egress at oldPortId
                log.debug("Mac {} moved to new port. Invalidate flows to old " +
                        "port {}", mac, oldPortId);
                controller.invalidateFlowsByElement(oldPortId);
            }
        }
    }

    private void updateLogicalPorts() {
        HashSet<UUID> oldLogicalPortIDs = logicalPortIDs;
        try {
            Set<UUID> newLogicalPortIDs = portMgr.getBridgeLogicalPortIDs(
                    bridgeId, logicalPortsWatcher);
            logicalPortIDs = new HashSet<UUID>(newLogicalPortIDs);
        } catch (StateAccessException e) {
            log.error("Failed to retrieve the logical port IDs for bridge {}",
                      bridgeId);
            return;
        }
        // TODO(pino): should we invalidate all the bridge's flows if the
        // TODO:       the set of logical ports has changed?

        // First find the ports that have been removed.
        HashSet<UUID> diff = (HashSet<UUID>)oldLogicalPortIDs.clone();
        diff.removeAll(logicalPortIDs);
        for (UUID id : diff) {
            IntIPv4 rtrPortIp = brPortIdToRtrPortIp.remove(id);
            MAC rtrPortMAC = rtrIpToMac.remove(rtrPortIp);
            rtrMacToLogicalPortId.remove(rtrPortMAC);
            log.debug("updateLogicalPorts: removed bridge port {} " +
                      "connected to router port with MAC:{} and IP:{}",
                      new Object[]{id, rtrPortMAC, rtrPortIp});

        }
        // Now find all the newly added ports
        diff = (HashSet<UUID>)logicalPortIDs.clone();
        diff.removeAll(oldLogicalPortIDs);
        for (UUID id : diff) {
            // Find the peer of the new logical port.
            LogicalBridgePortConfig bridgePort =
                    portCache.get(id, LogicalBridgePortConfig.class);
            if (null == bridgePort) {
                log.error("Failed to find the logical bridge port's config {}",
                        id);
                continue;
            }
            // Ignore dangling ports.
            if (null == bridgePort.peerId()) {
                continue;
            }
            LogicalRouterPortConfig routerPort = portCache.get(
                    bridgePort.peerId(), LogicalRouterPortConfig.class);
            if (null == routerPort) {
                log.error("Failed to get the config for the bridge's peer {}",
                        bridgePort);
                continue;
            }
            // 'Learn' that the router's mac is reachable via the bridge port.
            rtrMacToLogicalPortId.put(routerPort.getHwAddr(), id);
            // Add the router port's IP and MAC to the permanent ARP map.
            IntIPv4 rtrPortIp = new IntIPv4(routerPort.portAddr);
            rtrIpToMac.put(rtrPortIp, routerPort.getHwAddr());
            // Need a way to clean up if the logical bridge port is deleted.
            brPortIdToRtrPortIp.put(id, rtrPortIp);
            log.debug("updateLogicalPorts: added bridge port {} " +
                    "connected to router port with MAC:{} and IP:{}",
                    new Object[]{id, routerPort.getHwAddr(), rtrPortIp});
        }
    }

    private void expireMacPortEntry(final MAC mac, final UUID port) {
        UUID currentPort = macPortMap.get(mac);
        if (currentPort == null) {
            log.debug("MAC->port mapping for MAC {} has already been removed",
                    mac);
            return;
        } else if (currentPort.equals(port)) {
            Future<?> future = reactor.schedule(
                new Runnable() {
                    public void run() {
                        try {
                            if (port.equals(macPortMap.get(mac))) {
                                macPortMap.removeIfOwner(mac);
                                log.debug("Un-mapped {} from {}", mac, port);
                            }
                        } catch (Exception e) {
                            log.error("Failed to unmap {} from port {}: {}",
                                    new Object[] {mac, port, e});
                        }
                    }
                }, mac_port_timeout, TimeUnit.MILLISECONDS);
            delayedDeletes.put(mac, new PortFuture(port, future));
            log.debug("Will un-map {} from port {} in {} milliseconds.",
                    new Object[] {mac, port, mac_port_timeout});
        } else {
            log.debug("No need to unmap MAC {} from port {} - it's mapped " +
                      "to {}", new Object[] { mac, port, currentPort });
        }
    }

    private void increaseMacPortFlowCount(MAC macAddr, UUID portUuid) {
        MacPort flowcountKey = new MacPort(macAddr, portUuid);
        Integer count = flowCount.get(flowcountKey);
        if (count == null) {
            count = 1;
            // Remove any delayed delete.
            PortFuture delayedDelete = delayedDeletes.remove(macAddr);
            if (delayedDelete != null)
                delayedDelete.future.cancel(false);
            flowCount.put(flowcountKey, count);
        } else {
            flowCount.put(flowcountKey, new Integer(count+1));
        }

        log.debug("Increased flow count for source MAC {} from port {} to {}",
                  new Object[] { macAddr, portUuid, count });
        boolean writeMacPortMap = false;
        if (macPortMap.containsKey(macAddr)) {
            // Since mac was mapped to another port, flows to the mac would
            // not have been flooded, only directed to that port. So we can
            // invalidate by the port ID.
            UUID oldPortUuid = macPortMap.get(macAddr);
            if (!oldPortUuid.equals(portUuid)) {
                log.debug("Mac {} moved to new port. Invalidate flows to old " +
                          "port {}", macAddr, oldPortUuid);
                controller.invalidateFlowsByElement(oldPortUuid);
                writeMacPortMap = true;
            }
        } else {
            // The mac was not mapped to any port. So the flows to this mac
            // may have been flooded. We have to invalidate all flooded flows.
            log.debug("Learned a new Mac-Port mapping: {} to {} - invalidate " +
                    "flooded flow matches.", macAddr, portUuid);
            controller.invalidateFlowsByElement(floodElementID);
            writeMacPortMap = true;
        }
        // We need to write to the MacPortMap if the mac wasn't mapped or the
        // mapping needs to change, or if we're not the owner of the map entry.
        if (writeMacPortMap || !macPortMap.isKeyOwner(macAddr)) {
            macPortMap.put(macAddr, portUuid);
        }
    }

    // TODO(pino): re-implement this? More efficient invalidation.
    /*
    private void invalidateFlowsToMac(MAC mac) {
        log.info("invalidating flows with dl_dst {}", mac);
        MidoMatch match = new MidoMatch();
        match.setDataLayerDestination(mac);
        // TODO(pino): controller needs to expose ability for FEs to invalidate?
        //controllerStub.sendFlowModDelete(match, false, (short) 0, nonePort);
    }*/

    /*
     * Used only for testing
     */
    BridgeConfig getBridgeConfig() {
        return myConfig;
    }
}
