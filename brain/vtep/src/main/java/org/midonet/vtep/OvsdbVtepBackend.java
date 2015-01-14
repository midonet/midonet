/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.vtep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import scala.concurrent.duration.Duration;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.Delete;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import rx.Observable;
import rx.Observer;
import rx.Subscriber;

import org.midonet.packets.IPv4Addr;
import org.midonet.vtep.model.LogicalSwitch;
import org.midonet.vtep.model.MacEntry;
import org.midonet.vtep.model.McastMacEntry;
import org.midonet.vtep.model.McastMacEntryUpdate;
import org.midonet.vtep.model.PhysicalLocator;
import org.midonet.vtep.model.PhysicalSwitch;
import org.midonet.vtep.model.UcastMacEntry;
import org.midonet.vtep.model.UcastMacEntryUpdate;
import org.midonet.vtep.schema.LogicalSwitchTable;
import org.midonet.vtep.schema.MacsTable;
import org.midonet.vtep.schema.McastMacsTable;
import org.midonet.vtep.schema.PhysicalLocatorSetTable;
import org.midonet.vtep.schema.PhysicalLocatorTable;
import org.midonet.vtep.schema.PhysicalSwitchTable;
import org.midonet.vtep.schema.Table;
import org.midonet.vtep.schema.UcastMacsTable;

import static org.midonet.vtep.OvsdbUtil.delete;
import static org.midonet.vtep.OvsdbUtil.endPointFromOvsdbClient;
import static org.midonet.vtep.OvsdbUtil.getDbSchema;
import static org.midonet.vtep.OvsdbUtil.getTblSchema;
import static org.midonet.vtep.OvsdbUtil.modify;
import static org.midonet.vtep.OvsdbUtil.newMonitor;
import static org.midonet.vtep.OvsdbUtil.newMonitorRequest;
import static org.midonet.vtep.OvsdbUtil.query;

/**
 * A class to perform basic operations with an Ovsdb-based VTEP
 */
public class OvsdbVtepBackend {

    static private final String DB_HARDWARE_VTEP = "hardware_vtep";
    static private final String TB_LOGICAL_SWITCH = "Logical_Switch";
    static private final String TB_PHYSICAL_SWITCH = "Physical_Switch";
    static private final String TB_UCAST_MACS_LOCAL = "Ucast_Macs_Local";
    static private final String TB_UCAST_MACS_REMOTE = "Ucast_Macs_Remote";
    static private final String TB_MCAST_MACS_LOCAL = "Mcast_Macs_Local";
    static private final String TB_MCAST_MACS_REMOTE = "Mcast_Macs_Remote";
    static private final String TB_PHYSICAL_LOCATOR_SET = "Physical_Locator_Set";
    static private final String TB_PHYSICAL_LOCATOR = "Physical_Locator";

    private final OvsdbClient client;
    private final VtepEndPoint endPoint;

    // Database and table schemas
    public final LogicalSwitchTable logicalSwitchTable;
    public final PhysicalSwitchTable physicalSwitchTable;
    public final PhysicalLocatorSetTable physicalLocatorSetTable;
    public final PhysicalLocatorTable physicalLocatorTable;
    public final UcastMacsTable ucastMacsLocalTable;
    public final UcastMacsTable ucastMacsRemoteTable;
    public final McastMacsTable mcastMacsLocalTable;
    public final McastMacsTable mcastMacsRemoteTable;

    public final PhysicalSwitch physicalSwitch;

    public OvsdbVtepBackend(OvsdbClient client) throws Exception {
        this.client = client;
        this.endPoint = endPointFromOvsdbClient(client);
        try {
            DatabaseSchema dbs = getDbSchema(
                client, DB_HARDWARE_VTEP).awaitResult(Duration.Inf());

            this.logicalSwitchTable = new LogicalSwitchTable(
                dbs, getTblSchema(dbs, TB_LOGICAL_SWITCH));
            this.physicalSwitchTable = new PhysicalSwitchTable(
                dbs, getTblSchema(dbs, TB_PHYSICAL_SWITCH));
            this.physicalLocatorSetTable = new PhysicalLocatorSetTable(
                dbs, getTblSchema(dbs, TB_PHYSICAL_LOCATOR_SET));
            this.physicalLocatorTable = new PhysicalLocatorTable(
                dbs, getTblSchema(dbs, TB_PHYSICAL_LOCATOR));
            this.ucastMacsLocalTable = new UcastMacsTable(
                dbs, getTblSchema(dbs, TB_UCAST_MACS_LOCAL));
            this.ucastMacsRemoteTable = new UcastMacsTable(
                dbs, getTblSchema(dbs, TB_UCAST_MACS_REMOTE));
            this.mcastMacsLocalTable = new McastMacsTable(
                dbs, getTblSchema(dbs, TB_MCAST_MACS_LOCAL));
            this.mcastMacsRemoteTable = new McastMacsTable(
                dbs, getTblSchema(dbs, TB_MCAST_MACS_REMOTE));

            this.physicalSwitch = retrievePhysicalSwitch(this.endPoint.mgmtIp());

        } catch(NoSuchElementException exc) {
            throw new VtepUnsupportedException(this.endPoint, exc);
        } catch(TimeoutException tOut) {
            throw new VtepException(this.endPoint, tOut);
        }
    }

    /**
     * Retrieve the physical switch information for the management ip.
     * Failure to retrieve this information prevents the Vtep object from
     * being created.
     */
    public PhysicalSwitch retrievePhysicalSwitch(IPv4Addr mgmtIp)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows =
                query(client, physicalSwitchTable,
                      physicalSwitchTable.getManagementIpsMatcher(mgmtIp))
                    .awaitResult(Duration.Inf());
            if (rows.isEmpty())
                throw new VtepException(
                    endPoint, "cannot retrieve vtep's physical switch data");
            return
                physicalSwitchTable.parsePhysicalSwitch(rows.iterator().next());
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Retrieve information about logical switches directly from the VTEP
     * The returned set may be empty.
     */
    public Set<LogicalSwitch> retrieveLogicalSwitch(Condition cond)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows =
                query(client, logicalSwitchTable, cond)
                    .awaitResult(Duration.Inf());
            Set<LogicalSwitch> lsSet = new HashSet<>();
            for (Row<GenericTableSchema> r: rows) {
                lsSet.add(logicalSwitchTable.parseLogicalSwitch(r));
            }
            return lsSet;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Retrieve the physical locator information directly from the VTEP.
     * The returned set may be empty.
     */
    public Set<PhysicalLocator> retrievePhysicalLocator(Condition cond)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows =
                query(client, physicalLocatorTable, cond)
                    .awaitResult(Duration.Inf());
            Set<PhysicalLocator> locators = new HashSet<>();
            for (Row<GenericTableSchema> r : rows) {
                locators.add(physicalLocatorTable.parsePhysicalLocator(r));
            }
            return locators;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Create a new physical locator with the given IP.
     */
    public PhysicalLocator createPhysicalLocator(IPv4Addr ip)
        throws Exception {
        Insert<GenericTableSchema> op = physicalLocatorTable.insert(ip);
        try {
            UUID newId = modify(client, physicalLocatorTable, op)
                .awaitResult(Duration.Inf());
            if (newId == null)
                throw new VtepException(endPoint,
                    "cannot create locator for tunnel endpoint ip: " + ip);
            Set<PhysicalLocator> locator = retrievePhysicalLocator(
                physicalLocatorTable.getUuidMatcher(newId));
            if (locator.isEmpty())
                throw new VtepException(endPoint,
                    "cannot retrieve locator for tunnel endpoint ip: " + ip);
            return locator.iterator().next();
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Retrieve the list of physical locator sets that match the given
     * condition.
     */
    public Map<UUID, Set<UUID>> retrievePhysicalLocatorSet(Condition cond)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows =
                query(client, physicalLocatorSetTable, cond)
                    .awaitResult(Duration.Inf());
            Map<UUID, Set<UUID>> locatorSets = new HashMap<>();
            for (Row<GenericTableSchema> r : rows) {
                locatorSets.put(physicalLocatorSetTable.parseUuid(r),
                                physicalLocatorSetTable.parseLocators(r));
            }
            return locatorSets;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Create a new physical locator with the given locator id.
     */
    public Map<UUID, Set<UUID>> createPhysicalLocatorSet(UUID locatorId)
        throws Exception {
        Insert<GenericTableSchema> op =
            physicalLocatorSetTable.insert(locatorId);
        try {
            UUID newId = modify(client, physicalLocatorSetTable, op)
                .awaitResult(Duration.Inf());
            if (newId == null)
                throw new VtepException(
                    endPoint,
                    "cannot create locator set for locator: " + locatorId);
            Map<UUID, Set<UUID>> locatorSet = retrievePhysicalLocatorSet(
                physicalLocatorSetTable.getUuidMatcher(newId));
            if (locatorSet.isEmpty())
                throw new VtepException(
                    endPoint,
                    "cannot retrieve locator set for locator: " + locatorId);
            return locatorSet;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Retrieve the mac entries from the local or remote unicast mac table
     */
    public Set<UcastMacEntry> retrieveUcastMacEntries(UcastMacsTable table,
                                                      Condition cond)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows =
                query(client, table, cond).awaitResult(Duration.Inf());
            Set<UcastMacEntry> entrySet = new HashSet<>();
            for (Row<GenericTableSchema> r: rows) {
                entrySet.add(table.parseUcastMacEntry(r));
            }
            return entrySet;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Store an unicast mac entry
     */
    public UUID storeUcastMacEntry(UcastMacsTable table, UcastMacEntry entry)
        throws Exception {
        Insert<GenericTableSchema> op = table.insert(entry);
        try {
            UUID newId = modify(client, table, op).awaitResult(Duration.Inf());
            if (newId == null)
                throw new VtepException(endPoint,
                                        "cannot store entry: " + entry);
            return newId;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Store a multicast mac entry
     */
    public UUID storeMcastMacEntry(McastMacsTable table, McastMacEntry entry)
        throws Exception {
        Insert<GenericTableSchema> op = table.insert(entry);
        try {
            UUID newId = modify(client, table, op).awaitResult(Duration.Inf());
            if (newId == null)
                throw new VtepException(endPoint,
                                        "cannot store entry: " + entry);
            return newId;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Remove an unicast/multicast mac entry
     */
    public Integer deleteMacEntry(MacsTable table, MacEntry entry)
        throws Exception {
        Delete<GenericTableSchema> op = table.delete(entry);
        try {
            return delete(client, table, op).awaitResult(Duration.Inf());
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Retrieve the mac entries from the local or remote multicast mac table
     */
    public Set<McastMacEntry> retrieveMcastMacEntries(McastMacsTable table,
                                                      Condition cond)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows =
                query(client, table, cond).awaitResult(Duration.Inf());
            Set<McastMacEntry> entrySet = new HashSet<>();
            for (Row<GenericTableSchema> r: rows) {
                entrySet.add(table.parseMcastMacEntry(r));
            }
            return entrySet;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Setup table monitoring
     */
    private void monitorTableUpdates(Table table,
        Observer<TableUpdate<GenericTableSchema>> updates) {
        List<MonitorRequest<GenericTableSchema>> reqList = new ArrayList<>();
        reqList.add(newMonitorRequest(table.getSchema(),
                                      table.getColumnSchemas()));
        client.monitor(table.getDbSchema(), reqList,
                       newMonitor(table.getSchema(), updates));
    }

    /**
     * Get an observable with ucast mac table updates
     */
    private Observable<UcastMacEntryUpdate> ucastMacUpdates(
        final UcastMacsTable table) {
        return Observable.create(
            new Observable.OnSubscribe<UcastMacEntryUpdate>() {
                @Override
                public void call(
                    final Subscriber<? super UcastMacEntryUpdate> subscriber) {

                    // Create and observer transforming generic table updates
                    // into ucast mac table updates
                    Observer<TableUpdate<GenericTableSchema>> forwarder =
                        new Observer<TableUpdate<GenericTableSchema>>() {
                            @Override public void onCompleted() {
                                subscriber.onCompleted();
                            }
                            @Override public void onError(Throwable e) {
                                subscriber.onError(e);
                            }
                            @Override public void onNext(
                                TableUpdate<GenericTableSchema> update) {
                                for (UUID rowId : update.getRows().keySet()) {
                                    UcastMacEntry
                                        oldData =
                                        table.parseUcastMacEntry(
                                            update.getOld(rowId));
                                    UcastMacEntry
                                        newData =
                                        table.parseUcastMacEntry(
                                            update.getNew(rowId));
                                    subscriber.onNext(
                                        UcastMacEntryUpdate
                                            .apply(oldData, newData));
                                }
                            }
                        };

                    // Retrieve the current entries in the table to inject them
                    try {
                        for (UcastMacEntry mac :
                            retrieveUcastMacEntries(table, null)) {
                            subscriber.onNext(
                                UcastMacEntryUpdate.apply(null, mac));
                        }
                    } catch (Exception exc) {
                        subscriber.onError(exc);
                        return;
                    }
                    // Start monitoring changes
                    monitorTableUpdates(table, forwarder);
                }
            });
    }

    /**
     * Get an observable with local ucast macs table updates
     */
    public Observable<UcastMacEntryUpdate> localUcastMacUpdates() {
        return ucastMacUpdates(ucastMacsLocalTable);
    }

    /**
     * Get an observable with remote ucast macs table updates
     */
    public Observable<UcastMacEntryUpdate> remoteUcastMacUpdates() {
        return ucastMacUpdates(ucastMacsRemoteTable);
    }

    /**
     * Get an observable with mcast mac table updates
     */
    private Observable<McastMacEntryUpdate> mcastMacUpdates(
        final McastMacsTable table) {
        return Observable.create(
            new Observable.OnSubscribe<McastMacEntryUpdate>() {
                @Override
                public void call(
                    final Subscriber<? super McastMacEntryUpdate> subscriber) {

                    // Create and observer transforming generic table updates
                    // into ucast mac table updates
                    Observer<TableUpdate<GenericTableSchema>> forwarder =
                        new Observer<TableUpdate<GenericTableSchema>>() {
                            @Override public void onCompleted() {
                                subscriber.onCompleted();
                            }
                            @Override public void onError(Throwable e) {
                                subscriber.onError(e);
                            }
                            @Override public void onNext(
                                TableUpdate<GenericTableSchema> update) {
                                for (UUID rowId : update.getRows().keySet()) {
                                    McastMacEntry
                                        oldData =
                                        table.parseMcastMacEntry(
                                            update.getOld(rowId));
                                    McastMacEntry
                                        newData =
                                        table.parseMcastMacEntry(
                                            update.getNew(rowId));
                                    subscriber.onNext(
                                        McastMacEntryUpdate
                                            .apply(oldData, newData));
                                }
                            }
                        };

                    // Retrieve the current entries in the table to inject them
                    try {
                        for (McastMacEntry mac :
                            retrieveMcastMacEntries(table, null)) {
                            subscriber.onNext(
                                McastMacEntryUpdate.apply(null, mac));
                        }
                    } catch (Exception exc) {
                        subscriber.onError(exc);
                        return;
                    }
                    // Start monitoring changes
                    monitorTableUpdates(table, forwarder);
                }
            });
    }

    /**
     * Get an observable with local ucast macs table updates
     */
    public Observable<McastMacEntryUpdate> localMcastMacUpdates() {
        return mcastMacUpdates(mcastMacsLocalTable);
    }

    /**
     * Get an observable with remote ucast macs table updates
     */
    public Observable<McastMacEntryUpdate> remoteMcastMacUpdates() {
        return mcastMacUpdates(mcastMacsRemoteTable);
    }
}
