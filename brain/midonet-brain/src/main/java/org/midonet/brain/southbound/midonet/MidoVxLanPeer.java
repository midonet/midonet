/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.midonet;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;

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
import org.midonet.midolman.state.Directory;
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

    static private final int WATCHER_MAX_REINSTALL_RETRIES = 5;

    private final static Logger log =
        LoggerFactory.getLogger(MidoVxLanPeer.class);

    /* A table from a bridge UUID to the bridge's logical switch context */
    private final ConcurrentMap<UUID, LogicalSwitchContext> lsContexts;

    /* Aggregate Observable of all mac-port updates from all observed bridges */
    private final Subject<MacLocation, MacLocation> allUpdates;

    /* The logical switch monitoring context: its distributed MacPortMap table,
     * the vxlan port id and the vxlan port removal watch */
    private static class LogicalSwitchContext {
        final UUID vxLanPortId;
        final MacPortMap macPortMap;
        final VxLanPortRemovalWatcher ripper;
        final ReplicatedMap.Watcher<MAC, UUID> forwarder;

        /**
         * Create the Logical switch context.
         *
         * @param vxLanPortId is the id of the vxlan port.
         * @param macPortMap is the replicated map containing the bridge's
         *                   table between MAC addresses and the port where
         *                   they have been detected.
         * @param ripper is a zookeeper watcher responsible for removing the
         *               logical switch context and stopping any associated
         *               services when the vxlan port is removed.
         * @param forwarder is a MacPortMap (ReplicatedMap) watcher responsible
         *                  for detecting Mac-port updates and forwarding them
         *                  to subscribers by emitting them via an observable.
         */
        public LogicalSwitchContext(UUID vxLanPortId,
                                    MacPortMap macPortMap,
                                    VxLanPortRemovalWatcher ripper,
                                    ReplicatedMap.Watcher<MAC, UUID> forwarder)
        {
            this.vxLanPortId = vxLanPortId;
            this.macPortMap = macPortMap;
            this.ripper = ripper;
            this.forwarder = forwarder;
        }
    }

    /* The callback to execute when a bridge has its vxlan port removed */
    /* TODO: there should probably be a generic, zookeeper-independent
     * watcher interface, and maybe this could be extracted to another file...
     */
    private static class VxLanPortRemovalWatcher
        extends Directory.DefaultTypedWatcher {
        private final MidoVxLanPeer peer;
        private final UUID bridgeId;
        private final UUID vxLanPortId;

        public VxLanPortRemovalWatcher(MidoVxLanPeer peer,
                                       UUID bridgeId,
                                       UUID vxLanPortId) {
            this.peer = peer;
            this.bridgeId = bridgeId;
            this.vxLanPortId = vxLanPortId;
        }

        @Override
        public void pathDeleted(String path) {
            peer.forgetBridge(bridgeId);
        }

        /* Default action: re-install the watcher if the action is not
         * a vxlan port removal.
         */
        @Override
        public void run() {
            peer.setVxLanPortMonitor(vxLanPortId, this);
        }
    }

    /* Interface to the Midonet configuration backend store */
    private final DataClient dataClient;

    /* The MidoVxLanPeer is started on creation, then new bridges can be
     * added for watching at any time
     */
    private boolean started = true;

    @Inject
    public MidoVxLanPeer(DataClient dataClient) {
        this.dataClient = dataClient;
        this.lsContexts = new ConcurrentHashMap<>();
        this.allUpdates = PublishSubject.create();
    }

    /**
     * Register the monitor for a bridge's vxlan port.
     *
     * NOTE: in case of exception, the method retries several times to
     * install the watcher; this should help avoiding the effects of
     * temporary failures.
     *
     * @param ripper is the monitor code.
     * @return true if the monitor was successfully set, false otherwise.
     */
    private boolean setVxLanPortMonitor(UUID vxLanPortId,
                                        VxLanPortRemovalWatcher ripper) {
        int retries = WATCHER_MAX_REINSTALL_RETRIES;
        while (retries-- > 0) {
            try {
                if (dataClient.portWatch(vxLanPortId, ripper))
                    return true;
                log.warn("watcher not set: port {} does not exist anymore",
                         vxLanPortId);
                return false;
            } catch (StateAccessException | SerializationException e) {
                log.warn("failed to set watcher for port {}",
                         new Object[]{vxLanPortId, e});
            }
        }
        log.error("cannot install watcher for port {}", vxLanPortId);
        return false;
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
        LogicalSwitchContext ctx = lsContexts.get(bridgeId);
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
     * Start monitoring a bridge, binding it to the Observable that can be
     * subscribed to in order to listen for updates.
     * @param bridgeId is the bridge to be added to the watch list.
     * @return true if the bridge was added monitoring or false if it was
     * discarded (e.g. due to not having a vxlan port or other errors)
     */
    public synchronized boolean watch(UUID bridgeId) {
        if (!started) {
            log.error("Adding bridge {} to a stopped MidoVxLanPeer",
                      bridgeId);
            throw new IllegalStateException(String.format(
                "Adding bridge %s to a stopped MidoVxLanPeer", bridgeId));
        }
        LogicalSwitchContext ctx = lsContexts.get(bridgeId);
        if (ctx != null) {
            log.warn("Bridge is already being monitored: {}", bridgeId);
            return false;
        }

        ctx = createContext(bridgeId);
        if (ctx == null)
            return false;

        lsContexts.put(bridgeId, ctx);
        if (!setVxLanPortMonitor(ctx.vxLanPortId, ctx.ripper)) {
            /* vxlanport not available: rollback */
            forgetBridge(bridgeId);
            return false;
        }

        return true;
    }


    /**
     * Creates the context for the logical switch, including
     * the mac port updates and vxlan port removal watchers.
     *
     * @return a logical switch context, or null if something went wrong.
     */
    private LogicalSwitchContext createContext(final UUID bridgeId) {
        MacPortMap macTable;
        Bridge bridge;
        try {
            macTable = dataClient.bridgeGetMacTable(bridgeId,
                                                    Bridge.UNTAGGED_VLAN_ID,
                                                    false);
            bridge = dataClient.bridgesGet(bridgeId);
        } catch (SerializationException | StateAccessException e) {
            log.error("Error retrieving bridge " + bridgeId + " state" +
                      ", watcher won't be set up", e);
            return null;
        }
        if (bridge == null) {
            /* A bridge may have disappeared due to a race condition: skip it */
            log.warn("No such bridge " + bridgeId);
            return null;
        }
        UUID vxLanPortId = bridge.getVxLanPortId();
        if (vxLanPortId == null) {
            /* A bridge may have its vxlan port removed; if so, skip it */
            log.warn("Tried to watch bridge " + bridgeId +
                     " without vxlan port, watcher won't be set up");
            return null;
        }

        /* Create and activate the mac tables watcher, which may start pushing
         * values to the subject *after* the initial ones set just above
         */
        ReplicatedMap.Watcher<MAC, UUID> watcher =
            makeWatcher(bridgeId, vxLanPortId);
        macTable.addWatcher(watcher);
        macTable.start();

        /* Create the wxlan port removal monitor.
         * To avoid race conditions, it must be activated once the context
         * has been added to the macPortTables.
         */
        VxLanPortRemovalWatcher ripper =
            new VxLanPortRemovalWatcher(this, bridgeId, vxLanPortId);

        return new LogicalSwitchContext(vxLanPortId, macTable, ripper, watcher);
    }

    /**
     * Stop watching a bridge
     * @param bridgeId is the bridge to be removed from the watch list.
     */
    private void forgetBridge(UUID bridgeId) {
        log.debug("Removing bridge " + bridgeId + " from MidoVxLanPeer",
                  bridgeId);
        LogicalSwitchContext ctx = lsContexts.remove(bridgeId);
        if (ctx == null) {
            log.warn("Bridge " + bridgeId + " was not being monitored");
            return;
        }
        /* NOTE: removing the vxlan port watchers requires triggering them and
         * not resetting them - which occurs naturally when the vxlan port
         * disappears. This should have happened already (otherwise
         * we should not be 'forgetting' the bridge.
         */
        /* NOTE: it is not necessary to remove entries from
         * ctx.macPortMap: they will expire by themselves.
         */
        /* TODO: the code apparently works if we do not remove the watch.
         * This is because the dataClient usually returns a new instance of
         * macPortMap if the bridge is ever reused... The old instance is
         * stopped with the old watch in place. If the dataClient returned
         * the same instance (which may happen some day), both the old and
         * new watchers would be active again, and we would be sending
         * notifications to both (old and new) vxlanpeer observables.
         */
        ctx.macPortMap.removeWatcher(ctx.forwarder);
        ctx.macPortMap.stop();
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
     * NOTE: once the 'stop' method is called, the rx.Observable with mac-port
     * updates is 'Complete'; it is assumed that a 'stopped' MidoVxLanPeer
     * cannot be re-used.
     */
    public synchronized void stop() {
        if (!this.started) {
            log.warn("Broker already stopped");
            return;
        }
        this.started = false;
        for (Map.Entry<UUID, LogicalSwitchContext> e :
            this.lsContexts.entrySet()) {
            UUID bridgeId = e.getKey();
            log.info("Closing mac-port table monitor {}", bridgeId);
            forgetBridge(bridgeId);
        }
        this.allUpdates.onCompleted();
        this.lsContexts.clear();
        this.started = false;
    }

    /**
     * Returns IDs of all the Mido bridges. Primarily for unit testing.
     * @return A set of all the Mido bridges.
     */
    public Set<UUID> getMacTableOwnerIds() {
        return this.lsContexts.keySet();
    }

    /**
     * Get the port currently mapped to the given MAC. Mainly for unit tests.
     *
     * @param bridgeId A bridge ID.
     * @param mac A MAC address.
     * @return True if the entry exists, and false otherwise.
     */
    UUID getPort(UUID bridgeId, MAC mac) {
        LogicalSwitchContext logicalSwitchContext = lsContexts.get(bridgeId);
        return (logicalSwitchContext == null) ? null
                                     : logicalSwitchContext.macPortMap.get(mac);
    }
}
