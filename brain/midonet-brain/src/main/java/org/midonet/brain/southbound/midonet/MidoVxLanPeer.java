/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.midonet;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.Maps;
import com.google.inject.Inject;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.services.vxgw.VxLanPeer;
import org.midonet.brain.services.vxgw.VxLanPeerSyncException;
import org.midonet.brain.southbound.vtep.VtepConstants;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.midolman.state.ReplicatedMap;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

/**
 * This class allows both watching for changes in mac-port of a set of bridges
 * through rx.Observable streams, and applying modifications.
 */
public class MidoVxLanPeer implements VxLanPeer {

    private final static Logger log =
        LoggerFactory.getLogger(MidoVxLanPeer.class);

    /* For each bridge, the individual backend MacPortMap, the corresponding
     * observable subject, and the port for the VxLan */
    private final Map<UUID, LogicalSwitchContext> macPortTables;

    /* Bus for all mac-port updates from all observed bridges */
    private final Subject<MacLocation, MacLocation> allUpdates;

    /* The individual MacPort map in a single bridge, the
     * observable subject that serves its stream of updates, and the port id
     * corresponding to the VxLanPort, so we can identify notifications
     * related to our own updates.
     */
    private static class LogicalSwitchContext {
        final UUID vxLanPortId;
        final MacPortMap macPortMap;
        public LogicalSwitchContext(UUID vxLanPortId,
                                    MacPortMap macPortMap) {
            this.vxLanPortId = vxLanPortId;
            this.macPortMap = macPortMap;
        }
    }

    /* Interface to the Midonet configuration backend store */
    private final DataClient dataClient;

    private boolean started = false;

    @Inject
    public MidoVxLanPeer(DataClient dataClient) {
        this.macPortTables = Maps.newHashMap();
        this.dataClient = dataClient;
        this.allUpdates = PublishSubject.create();
    }

    /*
     * For reference: macLocation is used to indicate changes to bindings
     * between mac addresses and ports (new bindings, updates and removals).
     * In case of removal, the vxlanTunnelEndpoint is null; for creations
     * and updates, the vxlanTunnelEndpoint is the vtep management ip (if
     * the macLocation comes from a vtep) or the tunnel ip of the host
     * holding the port (if the macLocation corresponds to a virtual bridge).
     * Updating the macPortMap replicated table causes notifications to be
     * sent to all subscribers (and, in particular, to the per-bridge watcher)
     */
    @Override
    public void apply(MacLocation macLocation) {
        UUID bridgeId = VtepConstants.logicalSwitchNameToBridgeId(
            macLocation.logicalSwitchName);
        LogicalSwitchContext ctx = macPortTables.get(bridgeId);
        if (ctx == null) {
            log.warn("Ignoring update for unknown bridge {}", bridgeId);
            return;
        }
        MacPortMap macPortMap = ctx.macPortMap;
        UUID portId = ctx.vxLanPortId;
        if (macLocation.vxlanTunnelEndpoint == null) {
            try {
                log.debug("Remove mac: {}", macLocation.mac);
                macPortMap.removeIfOwnerAndValue(macLocation.mac, portId);
            } catch (InterruptedException| KeeperException e) {
                log.warn("Error removing mac binding: {}",
                         new Object[]{macLocation, e});
                throw new VxLanPeerSyncException(
                    String.format("Cannot apply mac %s removal from port %s",
                                  macLocation.mac, portId),
                    macLocation, e
                );
            }
        } else {
            // The MAC binding is either created or updated
            UUID existing = macPortMap.get(macLocation.mac); // from local cache
            if (existing == null || !existing.equals(portId)) {
                log.debug("Apply mac-port mapping: {}", macLocation);
                macPortMap.put(macLocation.mac, portId);
            } else {
                log.debug("Skip redundant apply for: {}", macLocation);
            }
        }
    }

    @Override
    public Observable<MacLocation> observableUpdates() {
        return allUpdates.asObservable();
    }

    /**
     * Starts monitoring all given bridge ids and prepares the Observable that
     * can be subscribed to in order to listen for updates.
     */
    public synchronized void watch(Collection<UUID> bridgeIds) {
        if (this.started) {
            log.warn("Broker is already started");
            return;
        }
        this.started = true;
        for (final UUID bridgeId : bridgeIds) {
            log.info("Configure watcher for bridge {}", bridgeId);
            watch(bridgeId);
        }
    }

    /**
     * Wires an rx.Subject to Mac table change on the given bridge.
     */
    private void watch(final UUID bridgeId) {
        MacPortMap macTable;
        UUID vxLanPortId;
        try {
            macTable = dataClient.bridgeGetMacTable(bridgeId,
                                                    Bridge.UNTAGGED_VLAN_ID,
                                                    false);
            vxLanPortId = dataClient.bridgesGet(bridgeId).getVxLanPortId();

        } catch (SerializationException | StateAccessException e) {
            log.error("Error retrieving mac table/vxlan port for bridge " +
                      bridgeId + ", watcher won't be set up", e);
            return;
        }
        /* A bridge may have lost its vxlan port; if so, it won't be added */
        /* TODO: vxlan port modifications beyond this port will be monitored */
        if (vxLanPortId == null) {
            log.error("Tried to watch bridge " + bridgeId +
                      " without vxlan port, watcher won't be set up");
            return;
        }

        macPortTables.put(bridgeId,
                          new LogicalSwitchContext(vxLanPortId, macTable));

        ReplicatedMap.Watcher<MAC, UUID> watcher =
            makeWatcher(bridgeId, vxLanPortId);
        macTable.addWatcher(watcher);
        macTable.start();
    }

    /**
     * Returns a watcher that connects the given subject to the ReplicatedMap
     * callback hook.
     */
    private ReplicatedMap.Watcher<MAC, UUID> makeWatcher(
            final UUID bridgeId, final UUID vxLanPortId) {
        return new ReplicatedMap.Watcher<MAC, UUID>() {
            /* Converts the change into a MacLocation, and publish */
            public void processChange(MAC mac, UUID oldPort, UUID newPort) {
                log.debug("Change on bridge {}: MAC {} moves from {} to {}",
                          new Object[]{bridgeId, mac, oldPort, newPort});
                /*
                 * If the new port (on update) or old port (on removal) is our
                 * own vxLanPort, then this means that the MAC belongs to the
                 * VTEP, and this update was generated when we applied the
                 * VTEP's updates to our own MAC table. We don't need to send
                 * the VTEP information about its own MAC table.
                 */
                UUID port = newPort == null? oldPort: newPort;
                if (!vxLanPortId.equals(port)) {
                    /* change does not come from this bridge */
                    try {
                    IPv4Addr vxTunnelIp = (newPort == null) ? null
                        : dataClient.vxlanTunnelEndpointFor(newPort);
                        allUpdates.onNext(
                            new MacLocation(
                                mac,
                                VtepConstants.bridgeIdToLogicalSwitchName(
                                    bridgeId),
                                vxTunnelIp
                            )
                        );
                    } catch (SerializationException | StateAccessException e) {
                        log.error("Failed to get vxlan tunnel endpoint for " +
                                  "bridge port {}", new Object[]{newPort, e});
                    }
                } else {
                    log.debug("Ignoring self-change on bridge {}: MAC {} " +
                              "moves from {} to {}",
                              new Object[]{bridgeId, mac, oldPort, newPort});
                }
            }
        };
    }

    /**
     * Clean up the local mac table copies.
     */
    public synchronized void stop() {
        if (!this.started) {
            log.warn("Broker already stopped");
            return;
        }
        for (Map.Entry<UUID, LogicalSwitchContext> e :
            this.macPortTables.entrySet()) {
            UUID bridgeId = e.getKey();
            log.info("Closing mac-port table monitor {}", bridgeId);
            LogicalSwitchContext logicalSwitchContext = e.getValue();
            logicalSwitchContext.macPortMap.stop();
        }
        this.allUpdates.onCompleted();
        this.macPortTables.clear();
        this.started = false;
    }

    /**
     * Returns IDs of all the Mido bridges. Primarily for unit testing.
     * @return A set of all the Mido bridges.
     */
    Set<UUID> getMacTableOwnerIds() {
        return this.macPortTables.keySet();
    }

    /**
     * Get the port currently mapped to the given MAC. Mainly for unit tests.
     *
     * @param bridgeId A bridge ID.
     * @param mac A MAC address.
     * @return True if the entry exists, and false otherwise.
     */
    UUID getPort(UUID bridgeId, MAC mac) {
        LogicalSwitchContext logicalSwitchContext = macPortTables.get(bridgeId);
        return (logicalSwitchContext == null) ? null
                                     : logicalSwitchContext.macPortMap.get(mac);
    }
}
