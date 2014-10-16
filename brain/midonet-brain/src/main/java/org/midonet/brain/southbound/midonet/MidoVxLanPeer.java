/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.brain.southbound.midonet;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.google.inject.Inject;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.services.vxgw.TunnelZoneState;
import org.midonet.brain.services.vxgw.VxLanPeer;
import org.midonet.brain.services.vxgw.VxLanPeerSyncException;
import org.midonet.brain.southbound.vtep.VtepConstants;
import org.midonet.brain.southbound.vtep.VtepMAC;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.Ip4ToMacReplicatedMap;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName;
import static org.midonet.midolman.state.ReplicatedMap.Watcher;

/**
 * This class allows both watching for changes in mac-port of a set of bridges
 * through rx.Observable streams, and applying modifications.
 */
public class MidoVxLanPeer implements VxLanPeer {

    static private final int WATCHER_MAX_REINSTALL_RETRIES = 5;

    private final static Logger log =
        LoggerFactory.getLogger(MidoVxLanPeer.class);

    // A table from a bridge UUID to the bridge's logical switch context
    private final ConcurrentMap<UUID, LogicalSwitchContext> lsContexts;

    // Aggregate Observable of all mac-port updates from all observed bridges
    private final Subject<MacLocation, MacLocation> allUpdates;

    /* The logical switch monitoring context: its distributed MacPortMap table,
     * the Ip2Mac table, the vxlan port id and the vxlan port removal watch */
    private static class LogicalSwitchContext {
        final UUID vxLanPortId;
        final MacPortMap macPortMap;
        final Ip4ToMacReplicatedMap ipMacMap;
        final VxLanPortRemovalWatcher ripper;
        final Watcher<MAC, UUID> macPortForwarder;
        final Watcher<IPv4Addr, MAC> ipMacForwarder;

        /**
         * Create the Logical switch context.
         *
         * @param vxLanPortId is the id of the vxlan port.
         * @param macPortMap is the replicated map containing the bridge's
         *                   table between MAC addresses and the port where
         *                   they have been detected.
         * @param ipMacMap is the replicated map containing the bridge's
         *                 arp table (ip-mac mappings). This is mainly used
         *                 to set watchers, as the table is manipulated via
         *                 DataClient methods.
         * @param ripper is a zookeeper watcher responsible for removing the
         *               logical switch context and stopping any associated
         *               services when the vxlan port is removed.
         * @param macPortForwarder is a MacPortMap (ReplicatedMap) watcher
         *                         responsible for detecting Mac-port updates
         *                         and forwarding them to subscribers by
         *                         emitting them via an observable.
         * @param ipMacForwarder is an Ip4ToMac (ReplicatedMap) watcher
         *                       responsible for detecting ip-mac updates
         *                       and forwarding them to subscribers by
         *                       emitting them via an observable.
         */
        public LogicalSwitchContext(UUID vxLanPortId,
                                    MacPortMap macPortMap,
                                    Ip4ToMacReplicatedMap ipMacMap,
                                    VxLanPortRemovalWatcher ripper,
                                    Watcher<MAC, UUID>
                                        macPortForwarder,
                                    Watcher<IPv4Addr, MAC>
                                        ipMacForwarder)
        {
            this.vxLanPortId = vxLanPortId;
            this.macPortMap = macPortMap;
            this.ipMacMap = ipMacMap;
            this.ripper = ripper;
            this.macPortForwarder = macPortForwarder;
            this.ipMacForwarder = ipMacForwarder;
        }
    }

    /* The callback to execute when a bridge has its vxlan port removed.
     * TODO: there should probably be a generic, zookeeper-independent
     * watcher interface, and maybe this could be extracted to another file.. */
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
         * a vxlan port removal. */
        @Override
        public void run() {
            peer.setVxLanPortMonitor(vxLanPortId, this);
        }
    }

    // Interface to the Midonet configuration backend store.
    private final DataClient dataClient;

    /* The MidoVxLanPeer is started on creation, then new bridges can be
     * added for watching at any time */
    private boolean started = true;

    // The subscription to flooding proxy notifications.
    private Subscription floodingProxySubscription = null;

    // The current flooding proxy.
    private TunnelZoneState.HostStateConfig floodingProxy = null;

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
     * MacLocation events instruct us about a MAC-IP pair's pressence at a
     * new VxLAN Tunnel Endpoint (that is, a VTEP), for a port-vlan binding
     * that belongs to a given logical switch.
     */
    @Override
    public void apply(MacLocation macLocation) {

        if (macLocation == null) {
            return;
        }

        if (!macLocation.mac().isIEEE802()) {
            log.debug("Ignoring UNKNOWN-DST mcast wildcard");
            return;
        }

        UUID bridgeId = VtepConstants.logicalSwitchNameToBridgeId(
            macLocation.logicalSwitchName());

        LogicalSwitchContext ctx = lsContexts.get(bridgeId);
        if (ctx == null) {
            log.warn("Update for unknown network {}", macLocation);
        } else if (macLocation.vxlanTunnelEndpoint() == null) {
            log.debug("Removal: {}", macLocation);
            this.macRemoval(macLocation, ctx, bridgeId);
        } else {
            log.debug("Add/update: {}", macLocation);
            this.macUpdate(macLocation, ctx, bridgeId);
        }
    }

    /**
     * Removes a MAC address from the bridge attached to a logical switch.
     */
    private void macRemoval(MacLocation ml, LogicalSwitchContext ctx,
                            UUID bridgeId) {
        UUID portId = ctx.vxLanPortId;
        MacPortMap macPortMap = ctx.macPortMap;
        try {
            MAC mac = ml.mac().IEEE802();
            macPortMap.removeIfOwnerAndValue(mac, portId);
            for (IPv4Addr ip: dataClient.bridgeGetIp4ByMac(bridgeId, mac)) {
                dataClient.bridgeDeleteLearnedIp4Mac(bridgeId, ip, mac);
            }
        } catch (StateAccessException | InterruptedException |
                 KeeperException e) {
            throw new VxLanPeerSyncException("Removal fails", ml, e);
        }
    }

    /**
     * Sets a MAC address to the MAC-port table for a bridge attached to a
     * logical switch, both for creations and updates.
     */
    private void macUpdate(MacLocation ml, LogicalSwitchContext ctx,
                           UUID bridgeId) {
        MAC mac = ml.mac().IEEE802();
        MacPortMap macPortMap = ctx.macPortMap;
        UUID portId = ctx.vxLanPortId;

        UUID currMacPort = macPortMap.get(mac);
        if (currMacPort == null || !currMacPort.equals(portId)) {
            if (currMacPort != null) {
                // FIXME: workaround for flaky replicated map updates
                // The following code may suffer from potential race
                // conditions; see MN-2637
                try {
                    macPortMap.removeIfOwner(mac);
                } catch (InterruptedException | KeeperException e) {
                    log.warn("Error replacing mac binding: {}", ml, e);
                    throw new VxLanPeerSyncException("Replacement", ml, e);
                }
            }
            log.debug("Apply mac-port mapping: {}", ml);
            macPortMap.put(mac, portId);
        } else {
            log.debug("MacLocation known, skipping {}", ml);
        }

        // Fill ARP-supression table: we learn new locations, but we do not
        // remove old ones
        if (ml.ipAddr() != null) {
            try {
                dataClient.bridgeAddLearnedIp4Mac(bridgeId, ml.ipAddr(), mac);
            } catch (StateAccessException e) {
                log.error("Cannot update ip-mac table for {} {}",
                          ml.ipAddr(), ml.mac(), e);
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
     * discarded (e.g. due to not having a vxlan port or other causes)
     */
    public synchronized boolean watch(UUID bridgeId) {
        if (!started) {
            log.error("Adding bridge {} to a stopped MidoVxLanPeer", bridgeId);
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
     * Subscribes to the connectable observable for the VXLAN tunnel zone
     * for changes to the flooding proxy.
     * @param observable The connectable observable.
     */
    public synchronized void subscribeToFloodingProxy(
        Observable<TunnelZoneState.FloodingProxyEvent> observable) {
        // If already subscribed, do nothing.
        if (null != floodingProxySubscription)
            return;

        floodingProxySubscription = observable.subscribe(
            new Action1<TunnelZoneState.FloodingProxyEvent>() {
                @Override
                public void call(TunnelZoneState.FloodingProxyEvent event) {
                    switch (event.operation) {
                        case SET: onFloodingProxySet(event); break;
                        case CLEAR: onFloodingProxyClear(); break;
                    }
                }
            });
    }

    /**
     * Advertises the current flooding proxy for the specified bridge. This is
     * needed when a logical bridge is added to the VXLAN peer.
     * @param bridgeId The bridge identifier.
     */
    public void advertiseFloodingProxy(UUID bridgeId) {
        log.info("Advertise flooding proxy {}", floodingProxy);
        if (null != floodingProxy) {
            setFloodingProxy(bridgeId, floodingProxy.ipAddr);
        }
    }

    /**
     * Creates the context for the logical switch, including
     * the mac port updates and vxlan port removal watchers.
     *
     * @return a logical switch context, or null if something went wrong.
     */
    private LogicalSwitchContext createContext(final UUID bridgeId) {
        MacPortMap macTable;
        Ip4ToMacReplicatedMap arpTable;
        Bridge bridge;
        try {
            macTable = dataClient.bridgeGetMacTable(bridgeId,
                                                    Bridge.UNTAGGED_VLAN_ID,
                                                    false);
            arpTable = dataClient.bridgeGetArpTable(bridgeId);
            bridge = dataClient.bridgesGet(bridgeId);
        } catch (SerializationException | StateAccessException e) {
            log.error("Error retrieving bridge " + bridgeId + " state" +
                      ", watcher won't be set up", e);
            return null;
        }

        if (bridge == null) { // Probably a race condition
            return null;
        }

        UUID vxLanPortId = bridge.getVxLanPortId();
        if (vxLanPortId == null) { // Probably race again with a port removal
            log.warn("Network {} appears unbound to VTEP", bridgeId);
            return null;
        }

        /* Create and activate the mac tables watcher, which may start pushing
         * values to the subject *after* the initial ones set just above */
        Watcher<MAC, UUID> macPortWatcher = makeMacPortWatcher(bridgeId,
                                                               vxLanPortId);
        macTable.addWatcher(macPortWatcher);

        /* Create and activate the arp table watcher, which may start
         * pushing * values to the subject *after* the initial ones set just
         * above */
        Watcher<IPv4Addr, MAC> arpWatcher = makeArpTableWatcher(bridgeId,
                                                                vxLanPortId,
                                                                macTable);
        arpTable.addWatcher(arpWatcher);

        macTable.start();
        arpTable.start();

        /* Create the VXLAN port removal monitor.
         * To avoid race conditions, it must be activated once the context
         * has been added to the macPortTables. */
        VxLanPortRemovalWatcher ripper =
            new VxLanPortRemovalWatcher(this, bridgeId, vxLanPortId);

        return new LogicalSwitchContext(vxLanPortId, macTable, arpTable,
                                        ripper, macPortWatcher, arpWatcher);
    }

    /**
     * Stop watching a bridge
     * @param bridgeId is the bridge to be removed from the watch list.
     */
    private void forgetBridge(UUID bridgeId) {
        log.debug("Stop watching network {}", bridgeId);

        // If there is a flooding proxy, remove it for this logical switch.
        if (null != floodingProxy) {
            setFloodingProxy(bridgeId, null);
        }

        LogicalSwitchContext ctx = lsContexts.remove(bridgeId);
        if (ctx == null) {
            log.debug("Network {} was not being monitored", bridgeId);
            return;
        }

        /* NOTE: removing the vxlan port watchers requires triggering them and
         * not resetting them - which occurs naturally when the vxlan port
         * disappears. This should have happened already (otherwise we should
         * not be 'forgetting' the bridge.
         */
        /* NOTE: it is not necessary to remove entries from ctx.macPortMap:
         * they will expire by themselves.
         */
        /* TODO: the code apparently works if we do not remove the watch.
         * This is because the dataClient usually returns a new instance of
         * macPortMap if the bridge is ever reused... The old instance is
         * stopped with the old watch in place. If the dataClient returned
         * the same instance (which may happen some day), both the old and
         * new watchers would be active again, and we would be sending
         * notifications to both (old and new) vxlanpeer observables.
         */
        ctx.macPortMap.removeWatcher(ctx.macPortForwarder);
        ctx.macPortMap.stop();
        ctx.ipMacMap.removeWatcher(ctx.ipMacForwarder);
        ctx.ipMacMap.stop();
    }

    /**
     * Returns a watcher that connects the given subject to the ReplicatedMap
     * callback hook.
     */
    private Watcher<MAC, UUID> makeMacPortWatcher(
            final UUID bridgeId, final UUID vxLanPortId) {
        return new Watcher<MAC, UUID>() {
            /* If the change is from the bridge we care about, it converts the
             * change into a MacLocation, and publish. Otherwise, ignored */
            public void processChange(MAC mac, UUID oldPort, UUID newPort) {
                log.debug("Network {}: MAC {} moves from {} to {}",
                          bridgeId, mac, oldPort, newPort);
                UUID port = newPort == null? oldPort: newPort;
                if (vxLanPortId.equals(port)) { // Change from the same bridge
                    return;
                }
                String lsName = bridgeIdToLogicalSwitchName(bridgeId);
                VtepMAC vMac = VtepMAC.fromMac(mac);
                try {
                    if (oldPort != null) {
                        // Invalidate old entries in the VTEP for that MAC
                        allUpdates.onNext(
                            new MacLocation(vMac, null, lsName, null));
                    }
                    if (newPort != null) {
                        IPv4Addr vxTunnelIp =
                            dataClient.vxlanTunnelEndpointFor(newPort);

                        // make sure there is at least one entry with no ip
                        allUpdates.onNext(
                            new MacLocation(vMac, null, lsName, vxTunnelIp));

                        // set a mapping for each specific ip (usually only one)
                        Set<IPv4Addr> ipSet =
                            dataClient.bridgeGetIp4ByMac(bridgeId,
                                                         vMac.IEEE802());
                        for (IPv4Addr ip: ipSet) {
                            MacLocation ml = new MacLocation(vMac, ip, lsName,
                                                             vxTunnelIp);
                            allUpdates.onNext(ml);
                        }
                    }
                } catch (SerializationException | StateAccessException e) {
                    log.error("Failed to get vxlan tunnel endpoint for " +
                              "bridge port {}", new Object[]{newPort, e});
                }
            }
        };
    }

    /**
     * Sets a flooding proxy for the current tunnel zone.
     * @param event The flooding proxy event.
     */
    private synchronized void onFloodingProxySet(
        TunnelZoneState.FloodingProxyEvent event) {
        this.floodingProxy = event.hostConfig;
        for (UUID bridgeId : this.lsContexts.keySet()) {
            setFloodingProxy(bridgeId, event.hostConfig.ipAddr);
        }
    }

    /**
     * Clears the flooding proxy for the current tunnel zone.
     */
    private synchronized void onFloodingProxyClear() {
        if (null == floodingProxy)
            return;

        floodingProxy = null;

        for (UUID bridgeId : this.lsContexts.keySet()) {
            setFloodingProxy(bridgeId, null);
        }
    }

    /**
     * Sets the flooding proxy for a logical switch.
     * @param bridgeId The bridge identifier.
     * @param hostAddress The flooding proxy host address.
     */
    private void setFloodingProxy(UUID bridgeId, IPv4Addr hostAddress) {
        String lsName =
            VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);

        if (null != hostAddress) {
            log.info("Set the flooding proxy for logical switch {} to {}",
                     lsName, hostAddress);
        } else {
            log.info("Clear the flooding proxy for logical switch {}", lsName);
        }

        allUpdates.onNext(new MacLocation(
            VtepMAC.UNKNOWN_DST, null, lsName, hostAddress));
    }


    /**
     * Returns a watcher that connects the given subject to the ArpTable
     * callback hook.
     */
    private Watcher<IPv4Addr, MAC> makeArpTableWatcher(final UUID bridgeId,
                       final UUID vxLanPortId, final MacPortMap macPortMap) {
        return new Watcher<IPv4Addr, MAC>() {
            /* If the change is from the bridge we care about, it converts the
             * change into a MacLocation, and publish. Otherwise, ignored */
            public void processChange(IPv4Addr ip, MAC oldMac, MAC newMac) {
                log.debug("Change on bridge {}: IP {} moves from {} to {}",
                          bridgeId, ip, oldMac, newMac);

                String lsName = bridgeIdToLogicalSwitchName(bridgeId);

                // we should remove the old ip-mac && add the new entry
                if (oldMac != null) {
                    UUID portId = macPortMap.get(oldMac);
                    if (portId != null && !vxLanPortId.equals(portId)) {
                        allUpdates.onNext(
                            new MacLocation(VtepMAC.fromMac(oldMac), ip, lsName,
                                            null)
                        );
                    }
                }
                if (newMac != null) {
                    UUID portId = macPortMap.get(newMac);
                    if (portId != null && !vxLanPortId.equals(portId)) try {
                        IPv4Addr vxTunnelIp =
                            dataClient.vxlanTunnelEndpointFor(portId);
                        allUpdates.onNext(
                            new MacLocation(VtepMAC.fromMac(newMac), ip, lsName,
                                            vxTunnelIp)
                        );
                    } catch (StateAccessException | SerializationException e) {
                        log.error("Failed to get VXLAN tunnel endpoint for " +
                                  "bridge port {}", portId, e);
                    }
                }
            }
        };
    }

    /**
     * Clean up the local mac table copies.
     *
     * NOTE: oonce the 'stop' method is called, the rx.Observable with mac-port
     * updates is 'Complete'; it is assumed that a 'stopped' MidoVxLanPeer
     * cannot be re-used.
     */
    public synchronized void stop() {
        if (!this.started) {
            log.warn("Broker already stopped");
            return;
        }

        // Un-subscribe from the tunnel zone flooding proxy notifications.
        if (null != floodingProxySubscription &&
            !floodingProxySubscription.isUnsubscribed()) {
            floodingProxySubscription.unsubscribe();
        }
        floodingProxySubscription = null;

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
     * Tells whether the MidoVxLanPeer is already managing this bridge Id.
     */
    public boolean knowsBridgeId(UUID bridgeId) {
        return this.lsContexts.containsKey(bridgeId);
    }

    /**
     * Get the port currently mapped to the given MAC. Mainly for unit tests.
     *
     * @param bridgeId A bridge ID.
     * @param mac A MAC address.
     * @return true if the entry exists, and false otherwise.
     */
    UUID getPort(UUID bridgeId, MAC mac) {
        LogicalSwitchContext logicalSwitchContext = lsContexts.get(bridgeId);
        return (logicalSwitchContext == null) ? null
                                     : logicalSwitchContext.macPortMap.get(mac);
    }
}
