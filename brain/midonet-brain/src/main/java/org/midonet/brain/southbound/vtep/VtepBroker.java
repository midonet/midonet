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
package org.midonet.brain.southbound.vtep;

import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Strings;
import com.google.inject.Inject;

import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.OvsDBSet;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.functions.Func1;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.packets.IPv4Addr;

import static org.opendaylight.ovsdb.lib.message.TableUpdate.Row;

/**
 * This class exposes a hardware VTEP as a vxlan gateway peer.
 */
public class VtepBroker {

    private final static Logger log = LoggerFactory.getLogger(VtepBroker.class);

    private final VtepDataClient vtepDataClient;

    /* This is an intermediary Subject that subscribes on the Observable
     * provided by the VTEP client, and republishes all its updates. It also
     * allows us to inject updates on certain occasions (e.g.: advertiseMacs)
     */
    private Subject<MacLocation, MacLocation>
        macLocationStream = PublishSubject.create();

    /**
     * Converts a single TableUpdate.Row from a Ucast_Macs_Local into an
     * Observable that emits MacLocation instances that correspond to the
     * updates to the row. The vxlan tunnel endpoint IP provided in the
     * MacLoations are extracted from the currently connected VTEP.
     */
    private final Func1<Row<Ucast_Macs_Local>,
                        Observable<MacLocation>>
        toMacLocation = new Func1<Row<Ucast_Macs_Local>,
                                  Observable<MacLocation>>() {
            @Override
            public Observable<MacLocation>
            call(Row<Ucast_Macs_Local> row) {
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
     */
    Func1<TableUpdates, Observable<? extends MacLocation>>
        translateTableUpdates = new Func1<TableUpdates,
                                       Observable<? extends MacLocation>>() {
        @Override
        public Observable<? extends MacLocation> call(TableUpdates ups) {
            if (vtepDataClient.getTunnelIp() == null) {
                log.warn("No VXLAN tunnel end-point, cannot process updates");
                return Observable.<MacLocation>empty();
            }
            TableUpdate<Ucast_Macs_Local> u = ups.getUcast_Macs_LocalUpdate();
            if (u == null) {
                return Observable.<MacLocation>empty();
            }
            return Observable.from(u.getRows())
                             .concatMap(toMacLocation) // may throw
                             .filter(filterNulls)
                             .onErrorResumeNext(errorHandler);
        }
    };

    @Inject
    public VtepBroker(final VtepDataClient client) {
        this.vtepDataClient = client;
        this.vtepDataClient
            .updatesObservable()
            .concatMap(translateTableUpdates)     // keeps order, filters nulls
            .subscribe(macLocationStream);        // dump into our Subject
    }

    public Observable<MacLocation> observableUpdates() {
        return this.macLocationStream.asObservable();
    }

    /**
     * Converts a Row update notification from the OVSDB client to a stream of
     * MacLocation updates that can be applied to a VxGW Peer. A change
     * in a given row is interpreted as follows:
     *
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
     *   null. In the new row, only fields that changed would be populated. An
     *   update would happen for several reasons:
     *   - The MAC changes: ignored, because MN doesn't update the mac
     *     so this means an operator wrongly manipulated the VTEP's database.
     *   - The IP changes: only relevant for ARP supression. In this case we
     *     have to add the mac's ip to the MacLocation and will have to update
     *     it accordingly.
     *   - The logical switch changes: again, MN will never trigger this
     *     change so it will be ignored.
     *   - The locator changed: this refers to the local tunnel IP,
     *     which being local should remain the same.
     *
     *   @return a cold Observable containing the corresponding MacLocation
     *           instances, empty if the logical switch doesn't exist (e.g.
     *           because it is deleted during the call)
     */
    private Observable<MacLocation> toMacLocation(Row<Ucast_Macs_Local> r) {

        VtepMAC vMac = VtepMAC.fromString(RowParser.mac(r));
        UUID lsId = RowParser.logicalSwitch(r);

        LogicalSwitch ls;
        try {
            ls = (lsId == null) ? null : vtepDataClient.getLogicalSwitch(lsId);
        } catch (VtepNotConnectedException e) {
            log.warn("Skip MAC {}, VTEP {} not connected", vMac, e.vtep);
            return Observable.empty();
        }

        if (ls == null) {
            log.warn("Skip MAC {}, logical switch {} not found ", vMac, lsId);
            return Observable.empty();
        }

        // The VXLAN tunnel endpoint: null if deleted, or the VTEP tunnel IP
        IPv4Addr endpoint = r.getNew() == null ? null :
                            vtepDataClient.getTunnelIp();

        IPv4Addr oldMacIp = RowParser.ip(r.getOld());
        IPv4Addr newMacIp = RowParser.ip(r.getNew());
        List<MacLocation> mlList= new ArrayList<>();
        if (oldMacIp != null && newMacIp != null &&
            !oldMacIp.equals(newMacIp)) {
            // We're on an update. Lets remove the old entry and set the new
            // one. This MacLocation indicates that the mac and ip have no
            // endpoint. This will be intepreted on the other side a a removal
            // of just the IP (if the mac had been deleted, the newRow would've
            // bee null). A ML with null ip and null endpoint would be
            // interpreted as a removal of the mac itself.
            mlList.add(new MacLocation(vMac, oldMacIp, ls.name, null));
        }
        // Below covers both deletions and additions of *_Mac_Local rows.
        IPv4Addr newerIp = (newMacIp == null) ? oldMacIp : newMacIp;
        mlList.add(new MacLocation(vMac, newerIp, ls.name, endpoint));
        log.debug("VTEP update translates to: {}", mlList);
        return Observable.from(mlList);
    }

    /**
     * Some utility methods to parse OVSDB Row updates.
     */
    public static class RowParser {
        // Methods below extract individual fields, watching for nulls.
        public static UUID logicalSwitch(Ucast_Macs_Local row) {
            if (row == null) {
                return null;
            }
            OvsDBSet<UUID> ls = row.getLogical_switch();
            return (ls == null || ls.isEmpty()) ? null : ls.iterator().next();
        }
        public static String mac(Ucast_Macs_Local row) {
            return (row == null || row.getMac() == null) ? null : row.getMac();
        }
        public static IPv4Addr ip(Ucast_Macs_Local row) {
            String sIp = (row == null) ? null : row.getIpaddr();
            return (sIp == null || sIp.isEmpty()) ? null : IPv4Addr.apply(sIp);
        }

        // Methods below extract the best value for the field, we try on the old
        // row (covering deletions and updates), then on the new (covering adds)
        private static UUID logicalSwitch(Row<Ucast_Macs_Local> r) {
            UUID curr = logicalSwitch(r.getOld());
            return (curr == null) ? logicalSwitch(r.getNew()) : curr;
        }
        private static String mac(Row<Ucast_Macs_Local> r) {
            String curr = mac(r.getOld());
            return (curr == null) ? mac(r.getNew()) : curr;
        }
    }
}
