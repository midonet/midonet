/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ReplicatedMap;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;


public class BridgeFE implements ForwardingElement {

    private final Logger log = LoggerFactory.getLogger(BridgeFE.class);
    public static final int DROP_SECONDS = 5;

    UUID bridgeId;
    MacPortMap macPortMap;
    long mac_port_timeout;
    Reactor reactor;
    PortZkManager portMgr;
    private Runnable logicalPortsWatcher;

    @Override
    public void process(ForwardInfo fwdInfo)
            throws StateAccessException, KeeperException {
        MAC srcDlAddress = new MAC(fwdInfo.matchIn.getDataLayerSource());
        MAC dstDlAddress = new MAC(fwdInfo.matchIn.getDataLayerDestination());
        // TODO(pino): detect whether the dst MAC is another forwarding element.
        UUID outPort = macPortMap.get(dstDlAddress);
        log.info(outPort == null ? "no port found for dst MAC"
                : "dst MAC is on port " + outPort.toString());

        boolean srcAddressIsMcast = Ethernet.isMcast(srcDlAddress);
        if (srcAddressIsMcast) {
            fwdInfo.action = Action.DROP;
            fwdInfo.dropTimeSeconds = DROP_SECONDS;
            log.info("multicast src MAC, dropping packet");
            return;
        } else {
            // Flood if the dst MAC is multi/broadcast or if unlearned unicast.
            // M/bcast addresses are never learned so the outport is null.
            // Otherwise send to a single port.
            fwdInfo.action = Action.FORWARD;
            if (outPort == null) {
                // Use the Bridge's own ID as the PortSet ID.
                log.info("flooding packet to {} to port set {}",
                        dstDlAddress, bridgeId);
                // TODO(pino): how/when is the bridge's PortSet set up?
                fwdInfo.outPortId = bridgeId;
            } else {
                log.info("forward packet to {} to port {}",
                        dstDlAddress, outPort);
                fwdInfo.outPortId = outPort;
            }
        }
        // Now do mac-address learning.
        // TODO(pino): detect whether the source is another forwarding element.
        UUID mappedPortUuid = macPortMap.get(srcDlAddress);
        if (!fwdInfo.inPortId.equals(mappedPortUuid)) {
            // The MAC changed port:  invalidate old flows before installing
            // a new flowmod for this MAC.
            invalidateFlowsFromMac(srcDlAddress);
            invalidateFlowsToMac(srcDlAddress);
        }

        // Learn the MAC source address.
        // TODO(pino): no removal notification for flows from other FEs.
        increaseMacPortFlowCount(srcDlAddress, fwdInfo.getInPortId());
        fwdInfo.notifyFEs.add(bridgeId);
        return;
    }

    @Override
    public void addPort(UUID portId) throws StateAccessException,
            KeeperException, InterruptedException, JMException {
        // TODO(pino): controller invals by local portNum - so no-op here?
        invalidateFlowsToPortUuid(portId);
    }

    @Override
    public void removePort(UUID portId) throws StateAccessException,
            KeeperException, InterruptedException, JMException {
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
        return null;  //To change body of implemented methods use File | Settings | File Templates.
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

    static private class PortFuture {
        // Pair<Port, Future>
        public UUID port;
        public Future future;
        public PortFuture(UUID p, Future f) { port = p; future = f; }
    }
    // The delayed deletes for macPortMap.
    HashMap<MAC, PortFuture> delayedDeletes;

    HashMap<MacPort, Integer> flowCount;

    MacPortWatcher macToPortWatcher;

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
            if (port_is_local(new_uuid)) {
                log.info("MacPortWatcher.processChange: port {} is " +
                         "local, returning without taking action", new_uuid);
                return;
            }
            log.info("MacPortWatcher.processChange: mac {} changed "
                      + "from port {} to port {}", new Object[] {
                      key, old_uuid, new_uuid});

            /* If the MAC's old port was local, we need to invalidate its
             * flows. */
            if (port_is_local(old_uuid)) {
                log.debug("MacPortWatcher.processChange: Old port " +
                          "was local.  Invalidating its flows.");
                invalidateFlowsFromMac(key);
            }

            invalidateFlowsToMac(key);
        }
    }

    public BridgeFE(MacPortMap mac_port_map, long macPortTimeoutMillis,
                    Reactor reactor) {
        macPortMap = mac_port_map;
        mac_port_timeout = macPortTimeoutMillis;
        delayedDeletes = new HashMap<MAC, PortFuture>();
        flowCount = new HashMap<MacPort, Integer>();
        macToPortWatcher = new MacPortWatcher();
        macPortMap.addWatcher(macToPortWatcher);
        this.portMgr = new PortZkManager(null, null);
        this.reactor = reactor;
        this.logicalPortsWatcher = new Runnable() {
            public void run() {
                updateLogicalPorts();
            }
        };
        updateLogicalPorts();
    }

    private void updateLogicalPorts() {
        List<ZkNodeEntry<UUID, PortConfig>> ports;
        try {
            ports = portMgr.listBridgeLogicalPorts(
                    bridgeId, logicalPortsWatcher);
        } catch (StateAccessException e) {
            // TODO(pino): if we get a NoPathException give up.
            // TODO(pino): what about other excepetion types?
            return;
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

    private boolean port_is_local(UUID port) {
        // TODO(pino): check a local map of IDs reported by addPort
        return false;
    }

    private void invalidateFlowsToPortUuid(UUID port_uuid) {
        log.info("Invalidating flows to port ID {}", port_uuid);
        List<MAC> macs = macPortMap.getByValue(port_uuid);
        for (MAC mac : macs)
            invalidateFlowsToMac(mac);
    }

    @Override
    public void destroy() {
        // TODO(pino): do we really need to do flow invalidation?
        // TODO: if the FE is being destroyed, it should have no flows left?
        // TODO: alternatively, everything is being shut down...
        log.info("clear");

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
}
