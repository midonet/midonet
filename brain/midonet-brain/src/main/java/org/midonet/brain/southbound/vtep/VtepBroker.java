/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.ArrayList;
import java.util.List;

import com.google.inject.Inject;

import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.services.vxgw.VxLanPeer;
import org.midonet.brain.services.vxgw.VxLanPeerSyncException;
import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

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
                             .map(toMacLocation);
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
        List<MacLocation> macLocations = new ArrayList<>();
        for (UcastMac ucastMac : macs) {
            LogicalSwitch ls =
                vtepDataClient.getLogicalSwitch(ucastMac.logicalSwitch);
            if (ls == null) {
                log.warn("Unknown logical switch {}", ucastMac.logicalSwitch);
                continue;
            }
            MAC mac = MAC.fromString(ucastMac.mac);
            macLocationStream.onNext(
                new MacLocation(mac, ls.name, vxlanTunnelEndPoint));
        }
    }

    /**
     * Applies an update in a MacLocation
     *
     * @param ml the object representing the new location of the MAC.
     */
    private void applyAddition(MacLocation ml) {
        log.debug("Adding UCAST remote MAC to the VTEP: " + ml);
        Status st = vtepDataClient.addUcastMacRemote(
            ml.logicalSwitchName,
            ml.mac.toString(),
            ml.vxlanTunnelEndpoint.toString());
        if (!st.isSuccess()) {
            throw new VxLanPeerSyncException(
                String.format("VTEP replied: %s, %s",
                              st.getCode(), st.getDescription()), ml);
        }
    }

    /**
     * Applies a deletion of a MAC.
     */
    private void applyDelete(MacLocation ml) {
        log.debug("Removing UCAST remote MAC from the VTEP: " + ml);
        Status st = vtepDataClient.delUcastMacRemote(
            ml.mac.toString(),
            ml.logicalSwitchName);
        if (!st.isSuccess()) {
            throw new VxLanPeerSyncException(
                String.format("VTEP replied: %s, %s",
                              st.getCode(), st.getDescription()), ml);
        }
    }

    @Override
    public Observable<MacLocation> observableUpdates() {
        return this.macLocationStream.asObservable();
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
        return new MacLocation(MAC.fromString(sMac), ls.name, ip);
    }

}
