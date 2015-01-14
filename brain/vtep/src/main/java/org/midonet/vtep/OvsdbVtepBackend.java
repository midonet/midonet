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
import org.midonet.vtep.model.MacEntryUpdate;
import org.midonet.vtep.model.McastMacEntry;
import org.midonet.vtep.model.McastMacEntryUpdate;
import org.midonet.vtep.model.PhysicalLocator;
import org.midonet.vtep.model.PhysicalSwitch;
import org.midonet.vtep.model.UcastMacEntry;
import org.midonet.vtep.model.UcastMacEntryUpdate;
import org.midonet.vtep.schema.LogicalSwitchTable;
import org.midonet.vtep.schema.MacsTable;
import org.midonet.vtep.schema.McastMacsLocalTable;
import org.midonet.vtep.schema.McastMacsRemoteTable;
import org.midonet.vtep.schema.McastMacsTable;
import org.midonet.vtep.schema.PhysicalLocatorSetTable;
import org.midonet.vtep.schema.PhysicalLocatorTable;
import org.midonet.vtep.schema.PhysicalSwitchTable;
import org.midonet.vtep.schema.Table;
import org.midonet.vtep.schema.UcastMacsLocalTable;
import org.midonet.vtep.schema.UcastMacsRemoteTable;
import org.midonet.vtep.schema.UcastMacsTable;

import static org.midonet.vtep.OvsdbUtil.delete;
import static org.midonet.vtep.OvsdbUtil.endPointFromOvsdbClient;
import static org.midonet.vtep.OvsdbUtil.getDbSchema;
import static org.midonet.vtep.OvsdbUtil.modify;
import static org.midonet.vtep.OvsdbUtil.newMonitor;
import static org.midonet.vtep.OvsdbUtil.newMonitorRequest;
import static org.midonet.vtep.OvsdbUtil.query;

/**
 * A class to perform basic operations with an Ovsdb-based VTEP
 */
public class OvsdbVtepBackend {

    static private final String DB_HARDWARE_VTEP = "hardware_vtep";

    private final OvsdbClient client;
    private final VtepEndPoint endPoint;

    public class VtepRetrievalException extends VtepException {
        public VtepRetrievalException(String what) {
            super(endPoint, "cannot retrieve data from Vtep tables: " + what);
        }
    }

    // Database and table schemas
    public final LogicalSwitchTable lsTable;
    public final PhysicalSwitchTable psTable;
    public final PhysicalLocatorSetTable plSetTable;
    public final PhysicalLocatorTable plTable;
    public final UcastMacsTable uMacsLocalTable;
    public final UcastMacsTable uMacsRemoteTable;
    public final McastMacsTable mMacsLocalTable;
    public final McastMacsTable mMacsRemoteTable;

    public final PhysicalSwitch pSwitch;

    public OvsdbVtepBackend(OvsdbClient client) throws Exception {
        this.client = client;
        this.endPoint = endPointFromOvsdbClient(client);
        try {
            DatabaseSchema dbs = getDbSchema(
                client, DB_HARDWARE_VTEP).result(Duration.Inf());

            this.lsTable = new LogicalSwitchTable(dbs);
            this.psTable = new PhysicalSwitchTable(dbs);
            this.plSetTable = new PhysicalLocatorSetTable(dbs);
            this.plTable = new PhysicalLocatorTable(dbs);
            this.uMacsLocalTable = new UcastMacsLocalTable(dbs);
            this.uMacsRemoteTable = new UcastMacsRemoteTable(dbs);
            this.mMacsLocalTable = new McastMacsLocalTable(dbs);
            this.mMacsRemoteTable = new McastMacsRemoteTable(dbs);

            this.pSwitch = retrievePhysicalSwitch(this.endPoint.mgmtIp());

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
                query(client, psTable, psTable.getManagementIpsMatcher(mgmtIp))
                    .result(Duration.Inf());
            if (rows.isEmpty())
                throw new VtepRetrievalException("physical switch: " + mgmtIp);
            return
                psTable.parsePhysicalSwitch(rows.iterator().next());
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
                query(client, lsTable, cond).result(Duration.Inf());
            Set<LogicalSwitch> lsSet = new HashSet<>();
            for (Row<GenericTableSchema> r: rows) {
                lsSet.add(lsTable.parseLogicalSwitch(r));
            }
            return lsSet;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Create a new logical switch
     */
    public LogicalSwitch createLogicalSwitch(LogicalSwitch ls)
        throws Exception {
        Insert<GenericTableSchema> op = lsTable.insert(ls);
        try {
            UUID newId = modify(client, lsTable, op).result(Duration.Inf());
            if (newId == null)
                throw new VtepException(endPoint,
                                        "cannot create logical switch: " + ls);
            Set<LogicalSwitch> lSwitch = retrieveLogicalSwitch(
                lsTable.getUuidMatcher(newId));
            if (lSwitch.isEmpty())
                throw new VtepRetrievalException("logical switch " + newId);
            return lSwitch.iterator().next();
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
                query(client, plTable, cond)
                    .result(Duration.Inf());
            Set<PhysicalLocator> locators = new HashSet<>();
            for (Row<GenericTableSchema> r : rows) {
                locators.add(plTable.parsePhysicalLocator(r));
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
        Insert<GenericTableSchema> op = plTable.insert(ip);
        try {
            UUID newId = modify(client, plTable, op).result(Duration.Inf());
            if (newId == null)
                throw new VtepException(endPoint,
                    "cannot create locator for tunnel endpoint ip: " + ip);
            Set<PhysicalLocator> locator = retrievePhysicalLocator(
                plTable.getUuidMatcher(newId));
            if (locator.isEmpty())
                throw new VtepRetrievalException("locator for ip " + ip);
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
                query(client, plSetTable, cond).result(Duration.Inf());
            Map<UUID, Set<UUID>> locatorSets = new HashMap<>();
            for (Row<GenericTableSchema> r : rows) {
                locatorSets.put(plSetTable.parseUuid(r),
                                plSetTable.parseLocators(r));
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
            plSetTable.insert(locatorId);
        try {
            UUID newId = modify(client, plSetTable, op).result(Duration.Inf());
            if (newId == null)
                throw new VtepException(
                    endPoint,
                    "cannot create locator set for locator: " + locatorId);
            Map<UUID, Set<UUID>> locatorSet = retrievePhysicalLocatorSet(
                plSetTable.getUuidMatcher(newId));
            if (locatorSet.isEmpty())
                throw new VtepRetrievalException(
                    "locator set for locator " + locatorId);
            return locatorSet;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Retrieve the mac entries from the mac tables
     */
    public Set<MacEntry> retrieveMacEntries(MacsTable table, Condition cond)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows =
                query(client, table, cond).result(Duration.Inf());
            Set<MacEntry> entrySet = new HashSet<>();
            for (Row<GenericTableSchema> r: rows) {
                entrySet.add(table.parseMacEntry(r));
            }
            return entrySet;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /**
     * Store a mac entry
     */
    public UUID storeMacEntry(MacsTable table, MacEntry entry)
        throws Exception {
        Insert<GenericTableSchema> op = table.insert(entry);
        try {
            UUID newId = modify(client, table, op).result(Duration.Inf());
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
            return delete(client, table, op).result(Duration.Inf());
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
     * Get an observable with mac table updates
     */
    private Observable<? extends MacEntryUpdate> macUpdates(
        final MacsTable table) {
        return Observable.create(
            new Observable.OnSubscribe<MacEntryUpdate>() {
                @Override
                public void call(
                    final Subscriber<? super MacEntryUpdate> subscriber) {

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
                                    MacEntry oldData = table.parseMacEntry(
                                        update.getOld(rowId));
                                    MacEntry newData = table.parseMacEntry(
                                        update.getNew(rowId));
                                    if (table.isUcast())
                                        subscriber.onNext(
                                            UcastMacEntryUpdate.apply(
                                                (UcastMacEntry)oldData,
                                                (UcastMacEntry)newData));
                                    else
                                        subscriber.onNext(
                                            McastMacEntryUpdate.apply(
                                                (McastMacEntry)oldData,
                                                (McastMacEntry)newData));
                                }
                            }
                        };

                    // Retrieve the current entries in the table to inject them
                    try {
                        if (table.isUcast()) {
                            for (MacEntry mac: retrieveMacEntries(table, null)){
                                subscriber.onNext(
                                    UcastMacEntryUpdate.apply(
                                        null, (UcastMacEntry)mac));
                            }
                        } else {
                            for (MacEntry mac: retrieveMacEntries(table, null)){
                                subscriber.onNext(
                                    McastMacEntryUpdate.apply(
                                        null, (McastMacEntry)mac));
                            }

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
        return (Observable<UcastMacEntryUpdate>)macUpdates(uMacsLocalTable);
    }

    /**
     * Get an observable with remote ucast macs table updates
     */
    public Observable<UcastMacEntryUpdate> remoteUcastMacUpdates() {
        return (Observable<UcastMacEntryUpdate>)macUpdates(uMacsRemoteTable);
    }

    /**
     * Get an observable with local ucast macs table updates
     */
    public Observable<McastMacEntryUpdate> localMcastMacUpdates() {
        return (Observable<McastMacEntryUpdate>)macUpdates(mMacsLocalTable);
    }

    /**
     * Get an observable with remote ucast macs table updates
     */
    public Observable<McastMacEntryUpdate> remoteMcastMacUpdates() {
        return (Observable<McastMacEntryUpdate>)macUpdates(mMacsRemoteTable);
    }
}
