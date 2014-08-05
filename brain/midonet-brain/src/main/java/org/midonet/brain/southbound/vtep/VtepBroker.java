/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.google.inject.Inject;

import org.apache.commons.lang3.tuple.Pair;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.services.vxgw.VxLanPeer;
import org.midonet.brain.services.vxgw.VxLanPeerConsolidationException;
import org.midonet.brain.services.vxgw.VxLanPeerSyncException;
import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.packets.IPv4Addr;

import static org.midonet.brain.southbound.vtep.VtepConstants.logicalSwitchNameToBridgeId;

/**
 * This class exposes a hardware VTEP as a vxlan gateway peer.
 */
public class VtepBroker implements VxLanPeer {

    private final static Logger log = LoggerFactory.getLogger(VtepBroker.class);

    private final VtepDataClient vtepDataClient;

    private IPv4Addr vxlanTunnelEndPoint;

    /* This is an intermediary Subject that subscribes on the Observable
     * provided by the VTEP client, and republishes all its updates. It also
     * allows us to inject updates on certain occasions (e.g.: advertiseMacs)
     */
    private Subject<MacLocation, MacLocation>
        macLocationStream = PublishSubject.create();

    /**
     * Converts a single TableUpdate.Row from a Ucast_Macs_Local into a
     * MacLocation, using the vxlan tunnel endpoint IP extracted from the
     * currently connected VTEP.
     */
    private final Func1<TableUpdate.Row<Ucast_Macs_Local>, MacLocation>
        toMacLocation = new Func1<TableUpdate.Row<Ucast_Macs_Local>,
                                  MacLocation>() {
            @Override
            public MacLocation call(TableUpdate.Row<Ucast_Macs_Local> row) {
                return toMacLocation(row);
            }
    };

    /**
     * Filters out the null MacLocation elements for an observable.
     */
    private static final Func1<MacLocation, Boolean> filterNulls =
        new Func1<MacLocation, Boolean>() {
            @Override
            public Boolean call(MacLocation macLocation) {
                return macLocation != null;
            }
        };

    /**
     * Handles error translating to MacLocations
     */
    private static final Func1<Throwable, Observable<? extends MacLocation>>
        errorHandler = new Func1<Throwable,
                                 Observable<? extends MacLocation>>() {
        @Override
        public Observable<? extends MacLocation> call(Throwable e) {
            log.warn("Error translating MacLocation", e);
            return Observable.empty();
        }
    };

    /**
     * Converts a group of table updates to an Observable emitting each update
     * from the table, in order. If the vxlanTunnelEndpoint is not populated
     * yet, it'll just emit an empty observable since we can't figure out the
     * right vxlan tunnel IP.
     *
     * @return the stream of MacLocation objects resulting from translating
     * the updates to the Ucast_Macs_Local table.
     */
    private final Func1<TableUpdates, Observable<? extends MacLocation>>
        translateTableUpdates = new Func1<TableUpdates,
                                       Observable<? extends MacLocation>>() {
        @Override
        public Observable<? extends MacLocation> call(TableUpdates ups) {
            if (vxlanTunnelEndPoint == null) {
                log.warn("No vxlanTunnelEndpoint, can't process VTEP updates");
                return Observable.empty();
            }
            TableUpdate<Ucast_Macs_Local> u = ups.getUcast_Macs_LocalUpdate();
            if (u == null) {
                return Observable.empty();
            }
            return Observable.from(u.getRows())
                             .map(toMacLocation) // may throw
                             .filter(filterNulls)
                             .onErrorResumeNext(errorHandler);
        }
    };

    /**
     * Extracts the vxlan tunnel endpoint ip, which is captured from the
     * Physical_Switch row corresponding to this VTEP.
     */
    private final Action1<TableUpdates> extractVxlanTunnelEndpoint =
        new Action1<TableUpdates>() {
            @Override
            public void call(TableUpdates ups) {
                if (vxlanTunnelEndPoint != null) {
                    return;
                }
                TableUpdate<Physical_Switch> up = ups.getPhysicalSwitchUpdate();
                if (up == null) {
                    return;
                }
                String mgmtIp = vtepDataClient.getManagementIp().toString();
                for (TableUpdate.Row<Physical_Switch> row : up.getRows()) {
                    // Find our physical switch row, and fetch the tunnel IP
                    if (row.getNew().getManagement_ips().contains(mgmtIp)) {
                        vxlanTunnelEndPoint =
                            IPv4Addr.fromString(row.getNew()
                                                    .getTunnel_ips()
                                                    .iterator().next());
                        log.info("Discovered vtep's tunnel IP: {}",
                                 vxlanTunnelEndPoint);
                        break;
                    }
                }
            }
        };

    @Inject
    public VtepBroker(final VtepDataClient client) {
        this.vtepDataClient = client;
        this.vtepDataClient
            .observableUpdates()
            .doOnNext(extractVxlanTunnelEndpoint) // extract VTEP's tunnel IP
            .concatMap(translateTableUpdates)     // preserve order
            .subscribe(macLocationStream);        // dump into our Subject
    }

    @Override
    public void apply(MacLocation ml) {
        if (ml == null) {
            log.warn("Ignoring null MAC-port update");
            return;
        }

        if (!ml.mac.isIEEE802()) {
            log.warn("Ignoring unknown-dst update {}", ml);
            return;
        }

        if (ml.vxlanTunnelEndpoint == null) {
            this.applyDelete(ml);
        } else {
            this.applyAddition(ml);
        }
    }

    /**
     * Triggers an advertisement of all the known Ucast_Mac_Local entries, which
     * will generate updates for each entry currently present in the table.
     *
     * This operation could make sense in the VxLanPeer interface at some point,
     * but it's not needed in the Mido peer so keeping it here for now.
     */
    public void advertiseMacs() {
        if (vxlanTunnelEndPoint == null) {
            log.warn("Can't advertise MACs: VTEP's tunnel IP still unknown");
            return;
        }
        log.info("Advertising MACs from VTEP at tunnel IP: {}",
                 vxlanTunnelEndPoint);
        List<UcastMac> macs = vtepDataClient.listUcastMacsLocal();
        for (UcastMac ucastMac : macs) {
            LogicalSwitch ls =
                vtepDataClient.getLogicalSwitch(ucastMac.logicalSwitch);
            if (ls == null) {
                log.warn("Unknown logical switch {}", ucastMac.logicalSwitch);
                continue;
            }
            VtepMAC mac = VtepMAC.fromString(ucastMac.mac);
            macLocationStream.onNext(
                new MacLocation(mac, ls.name, vxlanTunnelEndPoint)
            );
        }
    }

    /**
     * Applies an update in a MacLocation
     *
     * @param ml the object representing the new location of the MAC.
     */
    private void applyAddition(MacLocation ml) {
        log.debug("Adding UCAST remote MAC to the VTEP: " + ml);
        Status st = vtepDataClient.addUcastMacRemote(ml.logicalSwitchName,
                                                     ml.mac.IEEE802(),
                                                     ml.vxlanTunnelEndpoint);
        if (!st.isSuccess()) {
            if (st.getCode().equals(StatusCode.CONFLICT)) {
                log.info("Conflict writing {}, not expected", ml);
            } else {
                throw new VxLanPeerSyncException(
                    String.format("VTEP replied: %s, %s",
                                  st.getCode(), st.getDescription()), ml);
            }
        }
    }

    /**
     * Applies a deletion of a MAC.
     */
    private void applyDelete(MacLocation ml) {
        log.debug("Removing UCAST remote MAC from the VTEP: " + ml);
        Status st = vtepDataClient.delUcastMacRemote(ml.logicalSwitchName,
                                                     ml.mac.IEEE802());
        if (!st.isSuccess()) {
            if (st.getCode().equals(StatusCode.NOTFOUND)) {
                log.debug("Trying to delete entry but not present {}", ml);
            } else {
                throw new VxLanPeerSyncException(
                    String.format("VTEP replied: %s, %s",
                                  st.getCode(), st.getDescription()), ml
                );
            }
        }
    }

    @Override
    public Observable<MacLocation> observableUpdates() {
        return this.macLocationStream.asObservable();
    }

    /**
     * Ensures that the Logical Switch defined in the VtepBinding exists in
     * the given VTEP. Note that we only create new logical switches from the
     * VxLanGwService which guarantees that a single node will be doing writes
     * to the Logical_Switch table. This protects us against races. We expect
     * that if we try to delete/create a VTEP, we don't need to take into
     * account any races, and shout if we detect one.
     */
    public UUID ensureLogicalSwitchExists(String lsName, int vni) {
        assert(lsName != null);
        LogicalSwitch ls = vtepDataClient.getLogicalSwitch(lsName);
        if (ls != null) {
            if (vni == ls.tunnelKey) {
                log.debug("Logical switch {} with VNI {} exists", lsName, vni);
            } else {
                log.info("Logical switch {} has wrong VNI {}, delete", ls, vni);
                vtepDataClient.deleteLogicalSwitch(lsName);
            }
        }

        UUID lsUuid = null;
        if (ls == null) { // we will try to add it ourselves
            StatusWithUuid st = vtepDataClient.addLogicalSwitch(lsName, vni);
            if (st.isSuccess()) {
                lsUuid = st.getUuid();
                log.info("Logical switch {} created, uuid: {}", lsName, lsUuid);
            } else if (st.getCode().equals(StatusCode.CONFLICT)) {
                ls = vtepDataClient.getLogicalSwitch(lsName);
                if (ls != null && ls.tunnelKey.equals(vni)) {
                    log.warn("Tried to create logical switch {} and got "
                             + "unexpected conflict. State looks correct, but "
                             + "the conflict was unexpected, are there several"
                             + "writers to the VTEP?", lsName);
                } else if (ls != null) {
                    throw new VxLanPeerConsolidationException(
                        "Logical switch creation causes conflict, found row"
                        + "with different VNI than expected. Does the VTEP"
                        + "have several writers?", lsName
                    );
                } else {
                    throw new VxLanPeerConsolidationException(
                        "Logical switch creation causes conflict, but I can't"
                        + "find matching row. This is unexpected.", lsName
                    );
                }
                lsUuid = st.getUuid();
            }
        } else {
            lsUuid = ls.uuid;
        }

        return lsUuid;
    }

    /**
     * Will remove all the Midonet created logical switches from the VTEP that
     * that don't have a corresponding network id in the given list.
     */
    public void pruneUnwantedLogicalSwitches(
        Collection<java.util.UUID> wantedNetworks) {
        List<LogicalSwitch> lsList = vtepDataClient.listLogicalSwitches();
        for (LogicalSwitch ls : lsList) {
            java.util.UUID networkId = logicalSwitchNameToBridgeId(ls.name);
            if (networkId == null || wantedNetworks.contains(networkId)) {
                log.debug("Logical Switch {} kept in VTEP", ls);
                continue;
            }
            Status st = vtepDataClient.deleteLogicalSwitch(ls.name);
            if (st.isSuccess() || st.getCode().equals(StatusCode.NOTFOUND)) {
                log.info("Unused logical switch {} was removed from VTEP", ls);
            } else {
                log.warn("Can't remove unused logical switch {}: {}", ls, st);
            }
        }
    }

    /**
     * Ensures that all the PortVlanBindigs are configured properly for the
     * given logical switch, and only those.
     */
    public void renewBindings(UUID ls, Collection<VtepBinding> bindings) {
        Status st = vtepDataClient.clearBindings(ls);
        if (st.getCode() != StatusCode.SUCCESS) {
            throw new VxLanPeerConsolidationException(
                "Could not renew bindings for switch", ls.toString());
        }
        List<Pair<String, Integer>> pvPairs = new ArrayList<>(bindings.size());
        for (VtepBinding b : bindings) {
            pvPairs.add(Pair.of(b.getPortName(), (int) b.getVlanId()));
        }
        st = vtepDataClient.addBindings(ls, pvPairs);
        if (st.getCode() != StatusCode.SUCCESS) {
            throw new VxLanPeerConsolidationException(
                "Could not renew bindings for switch", ls.toString());
        }
    }

    /**
     * Converts a Row update notification from the OVSDB client to a single
     * MacLocation update that can be applied to a VxGW Peer. A change
     * in a given row is interpreted as follows:
     * - Addition: when r.getOld is null and r.getNew isn't. The MAC
     *   contained in r.getNew is now located at the ucast_local table
     *   of the monitored vtep. In this case, the resulting MacLocation
     *   will contain the new MAC, plus the vxlan tunnel endpoint IP of the
     *   VTEP being monitored by this VtepBroker.
     * - Deletion: when r.getOld is not null, and r.getNew is null. The MAC
     *   contained in the r.getOld ceased to be located at the VTEP
     *   we're now monitoring, so the resulting MacLocation will contain
     *   the MAC and a null vxlan tunnel endpoint IP.
     * - Update: when both the r.getOld and r.getNew values are not
     *   null. This would mean that the same row has mutated. This can
     *   happen for several reasons:
     *   - The mac changes: ignored, because MN doesn't update the mac
     *   so this means an operator wrongly manipulated the VTEP's database.
     *   - The ip changes: only relevant for ARP supression, which is
     *   currently not implemented. When this feature is added, we'll
     *   have to add the mac's ip to the MacLocation and will have to update
     *   it accordingly.
     *   - The logical switch changes: again, MN will never trigger this
     *   change so it will be ignored.
     *   - The locator changed: this refers to the local tunnel IP,
     *   which being local should remain the same.
     *
     *   @return the corresponding MacLocation instance, or null if the logical
     *   switch doesn't exist (e.g. bc. it is deleted during the call)
     */
    private MacLocation toMacLocation(TableUpdate.Row<Ucast_Macs_Local> r) {
        // The only thing we care about is the IP
        Ucast_Macs_Local val = (r.getOld() == null) ? r.getNew() : r.getOld();

        // MAC doesn't change, pick it up from whatever has a val
        String sMac = val.getMac();

        // Logical Switch id doesn't change, pick from wherever there is a val
        UUID lsId = val.getLogical_switch().iterator().next();

        // the vxlan tunnel endpoint: null if deleted, or the vtep's tunnel ip
        IPv4Addr ip = (r.getNew() == null) ? null : vxlanTunnelEndPoint;

        LogicalSwitch ls = vtepDataClient.getLogicalSwitch(lsId); // cached
        if (ls == null) {
            log.warn("Won't sync change for MAC {}, logical switch {} not " +
                     "present", sMac, lsId);
            return null;
        }
        return new MacLocation(VtepMAC.fromString(sMac), ls.name, ip);
    }

}
