/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.midonet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.services.vxgw.VxLanPeer;
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

    /* Backend MacPortMap and the corresponding Subject for observed bridges */
    private Map<UUID, MapAndSubject> macPortTables;

    /* Aggregate Observable of all mac-port updates from all observed bridges */
    private Observable<MacLocation> allUpdates;

    /* A pair of an individual MacPort map in a single bridge, and the
     * rx.Observable that serves its stream of updates.
     */
    private static class MapAndSubject {
        final MacPortMap macPortMap;
        final Subject<MacLocation, MacLocation> subject;
        public MapAndSubject(MacPortMap macPortMap,
                             Subject<MacLocation,
                                 MacLocation> subject) {
            this.macPortMap = macPortMap;
            this.subject = subject;
        }
    }

    /* Interface to the Midonet configuration backend store */
    private final DataClient dataClient;

    private boolean started = false;

    @Inject
    public MidoVxLanPeer(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void apply(MacLocation macLocation) {
        log.info("Received update from peer: {}", macLocation);
    }

    @Override
    public Observable<MacLocation> observableUpdates() {
        return allUpdates;
    }

    public boolean isStarted() {
        return this.started;
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

        List<Observable<MacLocation>> observables =
            new ArrayList<>(bridgeIds.size());

        this.macPortTables = new HashMap<>(bridgeIds.size());
        for (final UUID bridgeId : bridgeIds) {
            Observable<MacLocation> o = addSubject(bridgeId);
            if (o != null) {
                observables.add(o);
            }
        }

        this.allUpdates = Observable.merge(observables);
    }

    /**
     * Wires an rx.Subject to Mac table change on the given bridge.
     *
     * @return an rx.Observable with a stream of updates on the MAC table.
     */
    private Observable<MacLocation> addSubject(final UUID bridgeId) {

        MacPortMap macTable;
        try {
            macTable = dataClient.bridgeGetMacTable(bridgeId,
                                                    Bridge.UNTAGGED_VLAN_ID,
                                                    false);
        } catch (StateAccessException ste) {
            log.error("Error retrieving mac table for bridge " + bridgeId +
                      ", watcher won't be set up", ste);
            return null;
        }

        Subject<MacLocation, MacLocation> subj = PublishSubject.create();
        macPortTables.put(bridgeId, new MapAndSubject(macTable, subj));

        ReplicatedMap.Watcher<MAC, UUID> watcher = makeWatcher(bridgeId, subj);
        macTable.addWatcher(watcher);
        macTable.start();

        return subj.asObservable();
    }

    /**
     * Returns a watcher that connects the given subject to the ReplicatedMap
     * callback hook.
     */
    private ReplicatedMap.Watcher<MAC, UUID> makeWatcher(
            final UUID bridgeId, final Subject<MacLocation, MacLocation> subj) {
        return new ReplicatedMap.Watcher<MAC, UUID>() {
            /* Converts the change into a MacLocation, and publish */
            public void processChange(MAC mac, UUID oldPort, UUID newPort) {
                log.debug("Change on bridge {}: MAC {} moves from {} to {}",
                          new Object[]{bridgeId, mac, oldPort, newPort});
                try {
                    IPv4Addr vxTunnelIp = (newPort == null) ? null
                        : dataClient.vxlanTunnelEndpointFor(newPort);
                    subj.onNext(
                        new MacLocation(
                            mac,
                            VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                            vxTunnelIp)
                    );
                } catch (SerializationException | StateAccessException e) {
                    log.error("Failed to get vxlan tunnel endpoint for " +
                              "bridge port {}", newPort, e);
                }
            }
        };
    }

    /**
     * Clean up the local mac table copies, complete observables.
     */
    public synchronized void stop() {
        if (this.started == false) {
            return;
        }
        for (Map.Entry<UUID, MapAndSubject> e : this.macPortTables.entrySet()) {
            UUID bridgeId = e.getKey();
            log.info("Closing mac-port table monitor {}", bridgeId);
            MapAndSubject mapAndSubject = e.getValue();
            mapAndSubject.macPortMap.stop();
            mapAndSubject.subject.onCompleted();
        }
        this.macPortTables = null;
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
        MapAndSubject mapAndSubject = macPortTables.get(bridgeId);
        return (mapAndSubject == null) ? null
                                       : mapAndSubject.macPortMap.get(mac);
    }
}
