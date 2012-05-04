/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.util.Collections;
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

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.rules.ChainProcessor;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalBridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ReplicatedMap;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.util.Cache;


public class Bridge implements ForwardingElement {

    private final Logger log = LoggerFactory.getLogger(Bridge.class);

    UUID bridgeId;
    MacPortMap macPortMap;
    long mac_port_timeout = 40*1000;
    Reactor reactor;
    PortZkManager portMgr;
    private Runnable logicalPortsWatcher;
    private Set<ZkNodeEntry<UUID, PortConfig>> logicalPorts =
            new HashSet<ZkNodeEntry<UUID, PortConfig>>();
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
    private BridgeZkManager.BridgeConfig myConfig;

    public Bridge(UUID bridgeId, Directory zkDir, String zkBasePath,
                  Reactor reactor, Cache cache, VRNControllerIface ctrl)
            throws StateAccessException {
        this.bridgeId = bridgeId;
        this.reactor = reactor;
        controller = ctrl;
        portMgr = new PortZkManager(zkDir, zkBasePath);
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
        myConfig = (new BridgeZkManager(zkDir, zkBasePath)).get(bridgeId).value;
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
        if (!(portMgr.get(fwdInfo.inPortId).value instanceof
                LogicalBridgePortConfig)) {
            log.debug("Bridge {} asking for flow removal notification for " +
                      "port {}", bridgeId, fwdInfo.inPortId);
            fwdInfo.addRemovalNotification(bridgeId);
        }
        fwdInfo.matchOut = fwdInfo.matchIn.clone();
        fwdInfo.outPortId = null;

        // Apply pre-bridging rules.
        RuleResult res = ChainProcessor.getChainProcessor().applyChain(
                myConfig.inboundFilter, fwdInfo.flowMatch, fwdInfo.matchIn,
                fwdInfo.inPortId, null, this.bridgeId);
        if (res.trackConnection)
            fwdInfo.addRemovalNotification(bridgeId);
        if (res.action.equals(RuleResult.Action.DROP) ||
                res.action.equals(RuleResult.Action.REJECT)) {
            // TODO: Should we send an ICMP !X for REJECT?  If so, with what
            // srcIP?
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
            UUID mappedPortUuid = macPortMap.get(srcDlAddress);
            if (!fwdInfo.inPortId.equals(mappedPortUuid)) {
                // The MAC changed port:  invalidate old flows before installing
                // a new flowmod for this MAC.
                invalidateFlowsFromMac(srcDlAddress);
                invalidateFlowsToMac(srcDlAddress);
            }
            // Learn the MAC source address.
            increaseMacPortFlowCount(srcDlAddress, fwdInfo.getInPortId());
            // We don't need flow removal notification for flows from FEs.
            fwdInfo.addRemovalNotification(bridgeId);
        }

        // Apply post-bridging rules.
        res = ChainProcessor.getChainProcessor().applyChain(
                myConfig.outboundFilter, fwdInfo.flowMatch, res.match,
                fwdInfo.inPortId, fwdInfo.outPortId, this.bridgeId);
        if (res.trackConnection)
            fwdInfo.addRemovalNotification(bridgeId);
        if (res.action.equals(RuleResult.Action.DROP) ||
                res.action.equals(RuleResult.Action.REJECT)) {
            // TODO: Should we send an ICMP !X for REJECT?  If so, with what
            // srcIP?
            fwdInfo.action = Action.DROP;
        }
        if (!res.action.equals(RuleResult.Action.ACCEPT)) {
            throw new RuntimeException("Post-bridging returned an action " +
                        "other than ACCEPT, DROP or REJECT.");
        }

        return;
    }

    @Override
    public void addPort(UUID portId) throws StateAccessException,
            KeeperException, InterruptedException, JMException {
        // TODO(pino): controller invals by local portNum - so no-op here?
        invalidateFlowsToPortUuid(portId);
        localPorts.add(portId);
        if (localPorts.size() == 1)
            controller.subscribePortSet(bridgeId);
        controller.addLocalPortToSet(bridgeId, portId);
    }

    @Override
    public void removePort(UUID portId) throws StateAccessException,
            KeeperException, InterruptedException, JMException {
        localPorts.remove(portId);
        controller.removeLocalPortFromSet(bridgeId, portId);
        if (localPorts.size() == 0)
            controller.unsubscribePortSet(bridgeId);
        // TODO(pino): controller invals by local portNum - so no-op here?
        // TODO(pino): we'll automatically get flow removal notifications.
        log.info("removePort - delete MAC-port entries for port {}", portId);
        List<MAC> macList = macPortMap.getByValue(portId);
        for (MAC mac : macList) {
            log.info("Removing mapping from MAC {} to port {}", mac, portId);
            flowCount.remove(new MacPort(mac, portId));
            invalidateFlowsFromMac(mac);
            invalidateFlowsToMac(mac);
            try {
                macPortMap.remove(mac);
            } catch (KeeperException e) {
                log.error("Caught ZooKeeper exception {}", e);
                // TODO: What should we do?
            } catch (InterruptedException e) {
                log.error("ZooKeeper operation interrupted: {}", e);
                // TODO: Is ignoring this OK, because we'll resynch with ZK
                // at the next ZK operation?
            }
        }
    }

    @Override
    public UUID getId() {
        return bridgeId;
    }

    @Override
    public void freeFlowResources(OFMatch match, UUID inPortId) {
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
            expireMacPortEntry(flowcountKey.mac, inPortId, false);
        }
    }

    @Override
    public void destroy() {
        // TODO(pino): do we really need to do flow invalidation?
        // TODO: if the FE is being destroyed, it should have no flows left?
        // TODO: alternatively, everything is being shut down...
        log.info("destroy");

        // Entries in macPortMap we own are either live with flows, in which
        // case they're in flowCount; or are expiring, in which case they're
        // in delayedDeletes.
        for (MacPort macPort : flowCount.keySet()) {
            log.info("clear: Deleting MAC-Port entry {} :: {}", macPort.mac,
                    macPort.port);
            expireMacPortEntry(macPort.mac, macPort.port, true);
        }
        flowCount.clear();
        for (Map.Entry<MAC, PortFuture> entry : delayedDeletes.entrySet()) {
            log.info("clear: Deleting MAC-Port entry {} :: {}", entry.getKey(),
                    entry.getValue().port);
            expireMacPortEntry(entry.getKey(), entry.getValue().port, true);
            entry.getValue().future.cancel(false);
        }
        delayedDeletes.clear();

        macPortMap.removeWatcher(macToPortWatcher);
        macPortMap.stop();
        // .stop() includes a .clear() of the underlying map, so we don't
        // need to clear out the entries here.

        // Clear all flows.
        MidoMatch match = new MidoMatch();
        // TODO(pino): controller needs to expose ability for FEs to invalidate?
        //controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);
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
        public void processChange(MAC key, UUID old_uuid, UUID new_uuid) {
            /* Update callback for the MacPortMap */

            /* If the new port is local, the flow updates have already been
             * applied, and we return immediately. */
            if (portIsLocal(new_uuid)) {
                // TODO(pino): agree with devs about having method name in log.
                log.info("MacPortWatcher.processChange: port {} is " +
                         "local, returning without taking action", new_uuid);
                return;
            }
            log.info("MacPortWatcher.processChange: mac {} changed "
                      + "from port {} to port {}", new Object[] {
                      key, old_uuid, new_uuid});

            /* If the MAC's old port was local, we need to invalidate its
             * flows. */
            if (portIsLocal(old_uuid)) {
                log.debug("MacPortWatcher.processChange: Old port " +
                          "was local.  Invalidating its flows.");
                invalidateFlowsFromMac(key);
            }

            invalidateFlowsToMac(key);
        }
    }

    private void updateLogicalPorts() {
        Set<ZkNodeEntry<UUID, PortConfig>> oldPorts = logicalPorts;
        logicalPorts = new HashSet<ZkNodeEntry<UUID, PortConfig>>();
        try {
            logicalPorts.addAll(portMgr.listBridgeLogicalPorts(
                    bridgeId, logicalPortsWatcher));
        } catch (StateAccessException e) {
            // TODO(pino): if we get a NoPathException give up.
            // TODO(pino): what about other excepetion types?
            return;
        }
        Set<ZkNodeEntry<UUID, PortConfig>> diff =
                new HashSet<ZkNodeEntry<UUID, PortConfig>>();
        // First find the ports that have been removed.
        diff.addAll(oldPorts);
        diff.removeAll(logicalPorts);
        for (ZkNodeEntry<UUID, PortConfig> entry : diff){
            IntIPv4 rtrPortIp = brPortIdToRtrPortIp.remove(entry.key);
            MAC rtrPortMAC = rtrIpToMac.remove(rtrPortIp);
            rtrMacToLogicalPortId.remove(rtrPortMAC);
            log.debug("updateLogicalPorts: removed bridge port {} " +
                    "connected to router port with MAC:{} and IP:{}",
                    new Object[]{entry.key, rtrPortMAC, rtrPortIp});

        }
        // Now find all the newly added ports
        diff.clear();
        diff.addAll(logicalPorts);
        diff.removeAll(oldPorts);
        for (ZkNodeEntry<UUID, PortConfig> entry : diff) {
            // Find the peer of the new logical port.
            LogicalBridgePortConfig lbcfg =
                    LogicalBridgePortConfig.class.cast(entry.value);
            // TODO(pino): cache the port configs.
            ZkNodeEntry<UUID, PortConfig> peerCfg;
            LogicalRouterPortConfig lrcfg;
            try {
                peerCfg = portMgr.get(lbcfg.peerId());
                lrcfg = LogicalRouterPortConfig.class.cast(peerCfg.value);
            } catch (StateAccessException e) {
                e.printStackTrace();
                // TODO(pino): how do we handle this exception?
                return;
            }
            // 'Learn' that the router's mac is reachable via the bridge port.
            rtrMacToLogicalPortId.put(lrcfg.getHwAddr(), entry.key);
            // Add the router port's IP and MAC to the permanent ARP map.
            IntIPv4 rtrPortIp = new IntIPv4(lrcfg.portAddr);
            rtrIpToMac.put(rtrPortIp, lrcfg.getHwAddr());
            // Need a way to clean up if the logical bridge port is deleted.
            brPortIdToRtrPortIp.put(entry.key, rtrPortIp);
            log.debug("updateLogicalPorts: added bridge port {} " +
                    "connected to router port with MAC:{} and IP:{}",
                    new Object[]{entry.key, lrcfg.getHwAddr(), rtrPortIp});
        }
    }

    private void expireMacPortEntry(final MAC mac, UUID port, boolean delete) {
        UUID currentPort = macPortMap.get(mac);
        if (currentPort == null) {
            log.debug("expireMacPortEntry: MAC->port mapping for MAC {} " +
                      "has already been removed", mac);
        } else if (currentPort.equals(port)) {
            if (delete) {
                log.info("expireMacPortEntry: deleting the mapping from " +
                          "MAC {} to port {}", mac, port);
                // Remove & cancel any delayedDeletes invalidated.
                PortFuture delayedDelete = delayedDeletes.remove(mac);
                if (delayedDelete != null)
                    delayedDelete.future.cancel(false);
                // The macPortMap watcher will invalidate flows associated
                // with this MAC, so we don't need to do it here.
                try {
                    macPortMap.remove(mac);
                } catch (KeeperException e) {
                    log.error("Delete of MAC {} threw ZooKeeper exception {}",
                              mac, e);
                    // TODO: What do we do about this?
                } catch (InterruptedException e) {
                    log.error("Delete of MAC {} interrupted: {}", mac, e);
                    // TODO: What do we do about this?
                }
            } else {
                log.info("expireMacPortEntry: setting the mapping from " +
                         "MAC {} to port {} to expire", mac, port);
                Future future = reactor.schedule(
                    new Runnable() {
                        public void run() {
                            try {
                                // TODO: Should we check that
                                // macPortMap.get(mac) still points to the port?
                                macPortMap.remove(mac);
                            } catch (KeeperException e) {
                                log.error("Delayed delete of MAC {} from " +
                                          "expireMacPortEntry threw ZooKeeper "+
                                          "exception {}", mac, e);
                                // TODO: What do we do about this?
                            } catch (InterruptedException e) {
                                log.error("Delayed delete of MAC {} from " +
                                          "expireMacPortEntry was " +
                                          "interrupted: {}", mac, e);
                                // TODO: What do we do about this?
                            }
                        }
                    }, mac_port_timeout, TimeUnit.MILLISECONDS);
                delayedDeletes.put(mac, new PortFuture(port, future));
            }
        } else {
            log.debug("expireMacPortEntry: MAC {} is now mapped to port {} " +
                      "not {}", new Object[] { mac, currentPort, port });
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

        log.info("Increased flow count for source MAC {} from port {} to {}",
                 new Object[] { macAddr, portUuid, count });
        if (!macPortMap.containsKey(macAddr) ||
                                !macPortMap.get(macAddr).equals(portUuid)) {
            try {
                macPortMap.put(macAddr, portUuid);
            } catch (KeeperException e) {
                log.error("ZooKeeper threw exception {}", e);
                // TODO: What should we do about this?
            } catch (InterruptedException e) {
                log.error("MacPortMap threw InterruptedException {}", e);
                // TODO: What should we do about this?
            }
        }
    }

    private void invalidateFlowsFromMac(MAC mac) {
        log.info("invalidating flows with dl_src {}", mac);
        MidoMatch match = new MidoMatch();
        match.setDataLayerSource(mac);
        // TODO(pino): need access to controller's invalidation.
        // TODO(pino): need to invalidate based on MAC and in/out port.
        //controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);
    }

    private void invalidateFlowsToMac(MAC mac) {
        log.info("invalidating flows with dl_dst {}", mac);
        MidoMatch match = new MidoMatch();
        match.setDataLayerDestination(mac);
        // TODO(pino): controller needs to expose ability for FEs to invalidate?
        //controllerStub.sendFlowModDelete(match, false, (short) 0, nonePort);
    }

    private boolean portIsLocal(UUID port) {
        return localPorts.contains(port);
    }

    private void invalidateFlowsToPortUuid(UUID port_uuid) {
        log.info("Invalidating flows to port ID {}", port_uuid);
        List<MAC> macs = macPortMap.getByValue(port_uuid);
        for (MAC mac : macs)
            invalidateFlowsToMac(mac);
    }
}
