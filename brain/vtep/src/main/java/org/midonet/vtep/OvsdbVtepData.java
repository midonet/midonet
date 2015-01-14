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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observer;
import rx.functions.Func1;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.packets.IPv4Addr;
import org.midonet.vtep.model.LogicalSwitch;
import org.midonet.vtep.model.MacLocation;
import org.midonet.vtep.model.PhysicalLocator;
import org.midonet.vtep.model.PhysicalSwitch;
import org.midonet.vtep.model.UcastMacEntry;
import org.midonet.vtep.model.VtepMAC;
import org.midonet.vtep.schema.LogicalSwitchTable;
import org.midonet.vtep.schema.MacsTable;
import org.midonet.vtep.schema.PhysicalLocatorSetTable;
import org.midonet.vtep.schema.PhysicalLocatorTable;
import org.midonet.vtep.schema.PhysicalSwitchTable;
import org.midonet.vtep.schema.Table;
import org.midonet.vtep.schema.UcastMacsTable;
import org.midonet.vtep.util.Expectation;

import static org.midonet.vtep.util.OvsdbUtil.endPointFromOvsdbClient;
import static org.midonet.vtep.util.OvsdbUtil.getDbSchema;
import static org.midonet.vtep.util.OvsdbUtil.getTblSchema;
import static org.midonet.vtep.util.OvsdbUtil.newMonitor;
import static org.midonet.vtep.util.OvsdbUtil.newMonitorRequest;
import static org.midonet.vtep.util.OvsdbUtil.query;
import static org.midonet.vtep.util.OvsdbUtil.modify;
import scala.Option;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;


/**
 * A class to handle data exchanges with an Ovsdb-based VTEP
 */
public class OvsdbVtepData {

    static private final String DB_HARDWARE_VTEP = "hardware_vtep";
    static private final String TB_LOGICAL_SWITCH = "Logical_Switch";
    static private final String TB_PHYSICAL_SWITCH = "Physical_Switch";
    static private final String TB_UCAST_MACS_LOCAL = "Ucast_Macs_Local";
    static private final String TB_UCAST_MACS_REMOTE = "Ucast_Macs_Remote";
    static private final String TB_PHYSICAL_LOCATOR_SET = "Physical_Locator_Set";
    static private final String TB_PHYSICAL_LOCATOR = "Physical_Locator";
    static private final String TB_PHYSICAL_PORT = "Physical_Port";

    static private final Logger log =
        LoggerFactory.getLogger(OvsdbVtepData.class);

    static public Expectation<OvsdbVtepData> get(final OvsdbClient client,
                                                 final Executor executor) {
        final Expectation<OvsdbVtepData> result = new Expectation<>();
        final VtepEndPoint vtep = endPointFromOvsdbClient(client);

        Expectation<DatabaseSchema> dbReady =
            getDbSchema(client, DB_HARDWARE_VTEP);
        dbReady.onFailureForward(result, executor);
        dbReady.onSuccess(new Expectation.OnSuccess<DatabaseSchema>() {
            @Override public void call(DatabaseSchema dbs) {
                try {
                    LogicalSwitchTable logicalSwitchTable =
                        new LogicalSwitchTable(
                            dbs, getTblSchema(dbs, TB_LOGICAL_SWITCH));
                    PhysicalSwitchTable physicalSwitchTable =
                        new PhysicalSwitchTable(
                            dbs, getTblSchema(dbs, TB_PHYSICAL_SWITCH));
                    PhysicalLocatorSetTable physicalLocatorSetTable =
                        new PhysicalLocatorSetTable(
                            dbs, getTblSchema(dbs, TB_PHYSICAL_LOCATOR_SET));
                    PhysicalLocatorTable physicalLocatorTable =
                        new PhysicalLocatorTable(
                            dbs, getTblSchema(dbs, TB_PHYSICAL_LOCATOR));
                    UcastMacsTable uMacsLocalTable = new UcastMacsTable(
                        dbs, getTblSchema(dbs, TB_UCAST_MACS_LOCAL));
                    UcastMacsTable uMacsRemoteTable = new UcastMacsTable(
                        dbs, getTblSchema(dbs, TB_UCAST_MACS_REMOTE));

                    result.success(
                        new OvsdbVtepData(executor, client, vtep, dbs,
                                          logicalSwitchTable,
                                          physicalSwitchTable,
                                          physicalLocatorSetTable,
                                          physicalLocatorTable,
                                          uMacsLocalTable, uMacsRemoteTable));
                } catch(NoSuchElementException exc) {
                    result.failure(new VtepUnsupportedException(vtep, exc));
                } catch(Throwable exc) {
                    result.failure(new VtepException(vtep, exc));
                }
            }
        }, executor);
        return result;
    }

    private final Executor executor;
    private final OvsdbClient client;
    private final VtepEndPoint endPoint;

    // Database and table schemas
    private final DatabaseSchema databaseSchema;
    private final LogicalSwitchTable logicalSwitchTable;
    private final PhysicalSwitchTable physicalSwitchTable;
    private final PhysicalLocatorSetTable physicalLocatorSetTable;
    private final PhysicalLocatorTable physicalLocatorTable;
    private final UcastMacsTable ucastMacsLocalTable;
    private final UcastMacsTable ucastMacsRemoteTable;

    // Cache static table contents
    private final PhysicalSwitch physicalSwitch;
    private final ConcurrentHashMap<UUID, LogicalSwitch>
        logicalSwitches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LogicalSwitch>
        logicalSwitchesByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PhysicalLocator>
        physicalLocators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IPv4Addr, Set<PhysicalLocator>>
        physicalLocatorsByIp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>>
        physicalLocatorSets = new ConcurrentHashMap<>();

    private OvsdbVtepData(Executor executor, OvsdbClient client,
                          VtepEndPoint endPoint, DatabaseSchema databaseSchema,
                          LogicalSwitchTable logicalSwitchTable,
                          PhysicalSwitchTable physicalSwitchTable,
                          PhysicalLocatorSetTable physicalLocatorSetTable,
                          PhysicalLocatorTable physicalLocatorTable,
                          UcastMacsTable ucastMacsLocalTable,
                          UcastMacsTable ucastMacsRemoteTable)
        throws Exception {
        this.executor = executor;
        this.client = client;
        this.endPoint = endPoint;
        this.databaseSchema = databaseSchema;
        this.logicalSwitchTable = logicalSwitchTable;
        this.physicalSwitchTable = physicalSwitchTable;
        this.physicalLocatorSetTable = physicalLocatorSetTable;
        this.physicalLocatorTable = physicalLocatorTable;
        this.ucastMacsLocalTable = ucastMacsLocalTable;
        this.ucastMacsRemoteTable = ucastMacsRemoteTable;

        this.physicalSwitch = retrievePhysicalSwitch();
    }

    /**
     * Retrieve the physical switch information for the management ip.
     * Failure to retrieve this information prevents the Vtep object from
     * being created.
     */
    private PhysicalSwitch retrievePhysicalSwitch() throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows = Await.result(
                query(client, physicalSwitchTable,
                      physicalSwitchTable.getManagementIpsMatcher(
                          endPoint.mgmtIp())).future(), Duration.Inf());
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
    private Set<LogicalSwitch> retrieveLogicalSwitch(Condition cond)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows = Await.result(
                query(client, logicalSwitchTable, cond).future(),
                Duration.Inf());
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
     * Get the list of logical switches directly from the VTEP.
     * This flushes and rebuilds the local cache. Note that this
     * method is intended for testing and debugging purposes, mainly.
     */
    public Set<LogicalSwitch> getLogicalSwitches() throws Exception {
        Set<LogicalSwitch> lsSet = retrieveLogicalSwitch(null);
        logicalSwitches.clear();
        logicalSwitchesByName.clear();
        for (LogicalSwitch ls: lsSet) {
            logicalSwitches.put(ls.uuid(), ls);
            logicalSwitchesByName.put(ls.name(), ls);
        }
        return lsSet;
    }

    /**
     * Get the logical switch for the given internal id. It checks the cache
     * and, if not present, it tries to retrieve the information from the
     * VTEP.
     */
    private LogicalSwitch getLogicalSwitch(UUID id) throws Exception {
        LogicalSwitch ls = logicalSwitches.get(id);
        if (ls == null) {
            Set<LogicalSwitch> lsSet =
                retrieveLogicalSwitch(logicalSwitchTable.getUuidMatcher(id));
            if (lsSet.isEmpty())
                return null;
            ls = lsSet.iterator().next(); // there should be a single one
            logicalSwitches.put(ls.uuid(), ls);
            logicalSwitchesByName.put(ls.name(), ls);
        }
        return ls;
    }

    /**
     * Get the logical switch for the given midonet name. It checks the cache
     * and, if not present, it tries to retrieve the information from the
     * VTEP. Note that, according to OVSDB specs, the 'name' should be unique
     * in the table.
     */
    public LogicalSwitch getLogicalSwitch(String name) throws Exception {
        LogicalSwitch ls = logicalSwitchesByName.get(name);
        if (ls == null) {
            Set<LogicalSwitch> lsSet =
                retrieveLogicalSwitch(logicalSwitchTable.getNameMatcher(name));
            if (lsSet.isEmpty())
                return null;
            ls = lsSet.iterator().next(); // there should be a single one
            logicalSwitches.put(ls.uuid(), ls);
            logicalSwitchesByName.put(ls.name(), ls);
        }
        return ls;
    }

    /**
     * Retrieve the physical locator information directly from the VTEP
     */
    private Set<PhysicalLocator> retrievePhysicalLocator(Condition cond)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows = Await.result(
                query(client, physicalLocatorTable, cond).future(),
                Duration.Inf());
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
     * Get the list of physical locators directly from the VTEP.
     * This flushes and rebuilds the local cache. Note that this
     * method is intended for testing and debugging purposes, mainly.
     */
    public Set<PhysicalLocator> getPhysicalLocators() throws Exception {
        Set<PhysicalLocator> locators = retrievePhysicalLocator(null);
        physicalLocators.clear();
        physicalLocatorsByIp.clear();
        for (PhysicalLocator pl: locators) {
            physicalLocators.put(pl.uuid(), pl);
            physicalLocatorsByIp.putIfAbsent(
                pl.dstIp(), Collections.newSetFromMap(
                    new ConcurrentHashMap<PhysicalLocator, Boolean>()));
            physicalLocatorsByIp.get(pl.dstIp()).add(pl);
        }
        return locators;
    }

    /**
     * Retrieve the physical locator associated to a tunnel endpoint ip.
     * Note that OVSDB specs do not forbid multiple physical locators for the
     * same ip, so we return a set, which may be empty.
     */
    private Set<PhysicalLocator> getPhysicalLocators(IPv4Addr ip)
        throws Exception {
        Set<PhysicalLocator> locators = physicalLocatorsByIp.get(ip);
        if (locators == null) {
            locators =
                retrievePhysicalLocator(physicalLocatorTable.getDstIpMatcher(ip));
            for (PhysicalLocator it: locators) {
                physicalLocators.put(it.uuid(), it);
                physicalLocatorsByIp.putIfAbsent(
                    it.dstIp(), Collections.newSetFromMap(
                        new ConcurrentHashMap<PhysicalLocator, Boolean>()));
                physicalLocatorsByIp.get(it.dstIp()).add(it);
            }
        }
        return locators;
    }

    /**
     * Retrieve the information about a specific physical locator.
     */
    private PhysicalLocator getPhysicalLocator(UUID id) throws Exception {
        PhysicalLocator pl = physicalLocators.get(id);
        if (pl == null) {
            Set<PhysicalLocator> locators =
                retrievePhysicalLocator(physicalLocatorTable.getUuidMatcher(id));
            if (locators.isEmpty())
                return null;
            for (PhysicalLocator it: locators) {
                physicalLocators.put(it.uuid(), it);
                physicalLocatorsByIp.putIfAbsent(
                    it.dstIp(), Collections.newSetFromMap(
                        new ConcurrentHashMap<PhysicalLocator, Boolean>()));
                physicalLocatorsByIp.get(it.dstIp()).add(it);
            }
            pl = locators.iterator().next();
        }
        return pl;
    }

    /**
     * Acquire a physical locator associated to a given tunnel endpoint ip,
     * and try to create it if none exists. Note that OVSDB does not indicate
     * that the ip field in the physical locator table must be unique, so, in
     * case multiple locators exist for the same ip, one of them is returned
     * randomly.
     */
    private PhysicalLocator acquirePhysicalLocator(IPv4Addr ip)
        throws Exception {
        Set<PhysicalLocator> plSet = getPhysicalLocators(ip);
        if (!plSet.isEmpty()) {
            return plSet.iterator().next();
        }
        Insert<GenericTableSchema> op = physicalLocatorTable.insert(ip);
        try {
            Await.result(modify(client, physicalLocatorTable, op).future(),
                         Duration.Inf());
            plSet = getPhysicalLocators(ip);
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
        return (plSet.isEmpty())? null: plSet.iterator().next();
    }

    /*
     * Physical locator sets
     */
    private Map<UUID, Set<UUID>> retrievePhysicalLocatorSet(Condition cond)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows = Await.result(
                query(client, physicalLocatorSetTable, cond).future(),
                Duration.Inf());
            Map<UUID, Set<UUID>> locatorSets = new ConcurrentHashMap<>();
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
     * Get locator sets
     */
    public Map<java.util.UUID, Set<java.util.UUID>> getPhysicalLocatorSets()
        throws Exception {
        Map<UUID, Set<UUID>> internalSet = retrievePhysicalLocatorSet(null);
        Map<java.util.UUID, Set<java.util.UUID>> locatorSets = new HashMap<>();
        physicalLocatorSets.clear();
        physicalLocatorSets.putAll(internalSet);

        // Unefficient, but just for debugging purposes
        for (UUID k: internalSet.keySet()) {
            Set<java.util.UUID> locators = new HashSet<>();
            for (UUID v: internalSet.get(k)) {
                locators.add(java.util.UUID.fromString(v.toString()));
            }
            locatorSets.put(java.util.UUID.fromString(k.toString()), locators);
        }

        return locatorSets;
    }


    /*
     * Mac table entries
     */
    private Set<UcastMacEntry> retrieveUcastMacEntries(UcastMacsTable table,
                                                       Condition cond)
        throws Exception {
        try {
            Collection<Row<GenericTableSchema>> rows = Await.result(
                query(client, table, cond).future(), Duration.Inf());
            Set<UcastMacEntry> entrySet = new HashSet<>();
            for (Row<GenericTableSchema> r: rows) {
                entrySet.add(table.parseUcastMacEntry(r));
            }
            return entrySet;
        } catch (TimeoutException tOut) {
            throw new VtepException(endPoint, tOut);
        }
    }

    /*
     * Get mac table entries
     */
    private Set<MacLocation> getUcastMacEntries(UcastMacsTable table)
        throws Exception {
        Set<UcastMacEntry> entrySet =
            retrieveUcastMacEntries(ucastMacsLocalTable, null);
        Set<MacLocation> mlSet = new HashSet<>();
        for (UcastMacEntry entry: entrySet) {
            MacLocation ml = ucastToMacLocation(entry);
            if (ml != null)
                mlSet.add(ml);
        }
        return mlSet;
    }

    public Set<MacLocation> getUcastMacLocalEntries() throws Exception {
        return getUcastMacEntries(ucastMacsLocalTable);
    }

    public Set<MacLocation> getUcastMacRemoteEntries() throws Exception {
        return getUcastMacEntries(ucastMacsRemoteTable);
    }

    private MacLocation ucastToMacLocation(UcastMacEntry entry) {
        try {
            LogicalSwitch ls = getLogicalSwitch(entry.logicalSwitchId());
            if (ls == null) {
                log.warn("unknown logical switch in mac entry: {}",
                         entry.logicalSwitchId());
                return null;
            }
            PhysicalLocator loc = getPhysicalLocator(entry.locator());
            if (loc == null) {
                log.warn("unknown physical locator in mac entry: {}",
                         entry.locator());
                return null;
            }
            return
                new MacLocation(entry.mac(), entry.ip(), ls.name(), loc.dstIp());
        } catch (Throwable exc) {
            log.error("cannot translate mac entry to mac location", exc);
            return null;
        }
    }

    private UcastMacEntry macLocationToUcast(MacLocation ml) {
        try {
            LogicalSwitch ls = getLogicalSwitch(ml.logicalSwitchName());
            if (ls == null) {
                log.warn("unknown logical switch in mac location: {}",
                         ml.logicalSwitchName());
                return null;
            }
            PhysicalLocator loc = null;
            if (ml.vxlanTunnelEndpoint() != null) {
                Set<PhysicalLocator> locators =
                    getPhysicalLocators(ml.vxlanTunnelEndpoint());
                if (locators.isEmpty()) {
                    log.warn("unknown vxlan tunnel end point: {}",
                             ml.vxlanTunnelEndpoint());
                    return null;
                }
                if (locators.size() > 1) {
                    log.warn("multiple physical locators for ip: {}",
                             ml.vxlanTunnelEndpoint());
                }
                loc = locators.iterator().next();
            }
            return new UcastMacEntry(null, ls.uuid(), ml.mac(), ml.ipAddr(),
                                     (loc == null)? null: loc.uuid());
        } catch (Throwable exc) {
            log.error("cannot translate mac location to mac entry", exc);
            return null;
        }
    }

    /**
     * Get the list of tunnel ip addresses for this particular vtep
     * end point.
     */
    public Set<IPv4Addr> getTunnelIps() {
        return physicalSwitch.tunnelIps();
    }

    /**
     * Get the 'main' tunnel ip address for this particular vtep end point.
     * Note: we assume there is a single tunnel ip address per vtep... in case
     * there were more than one, we make sure we always return the same one.
     */
    public Option<IPv4Addr> getTunnelIp() {
        return physicalSwitch.tunnelIp();
    }

    /**
     * apply a mac location update
     */
    public void applyRemoteMacLocation(MacLocation macLocation)
        throws Exception {
        log.debug("Received MAC location update: {}", macLocation);
        if (macLocation == null)
            return;

        if (macLocation.vxlanTunnelEndpoint() != null) {
            if (macLocation.mac().isUcast()) {
                applyRemoteUcastAddition(macLocation);
            } else {
                applyRemoteMcastAddition(macLocation);
            }
        } else {
            if (macLocation.mac().isUcast()) {
                applyRemoteUcastDeletion(macLocation);
            } else {
                applyRemoteMcastDeletion(macLocation);
            }
        }
    }

    private void applyRemoteUcastAddition(MacLocation macLocation)
        throws Exception {
        UcastMacEntry entry = macLocationToUcast(macLocation);
        if (entry != null) {

        }
        /*
        Insert<GenericTableSchema> op = ucastMacsRemoteTable.insert(
            macLocation.mac(), locatorSet, ls.uuid(), macLocation.ipAddr());
        Await.ready(modify(client, ucastMacsRemoteTable, op).future(),
                    Duration.Inf());
                    */
    }

    private void applyRemoteUcastDeletion(MacLocation macLocation) {

    }
    private void applyRemoteMcastAddition(MacLocation macLocation) {

    }
    private void applyRemoteMcastDeletion(MacLocation macLocation) {

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
     * Get an observable with raw UcastMacsLocal table updates
     */
    public Observable<MacLocation> ucastMacLocalUpdates() {
        final Subject<TableUpdate<GenericTableSchema>,
            TableUpdate<GenericTableSchema>> updates = PublishSubject.create();
        monitorTableUpdates(ucastMacsLocalTable, updates);
        return updates.concatMap(
            new Func1<TableUpdate<GenericTableSchema>,
                Observable<MacLocation>>() {
                @Override
                public Observable<MacLocation> call(
                    TableUpdate<GenericTableSchema> update) {
                    return macTableUpdateToMacLocation(ucastMacsLocalTable,
                                                       update);
                }
            });
    }

    /**
     * Get an observable with raw UcastMacsRemote table updates
     */
    public Observable<MacLocation> ucastMacRemoteUpdates() {
        final Subject<TableUpdate<GenericTableSchema>,
            TableUpdate<GenericTableSchema>> updates = PublishSubject.create();
        monitorTableUpdates(ucastMacsRemoteTable, updates);
        return updates.concatMap(
            new Func1<TableUpdate<GenericTableSchema>,
                Observable<MacLocation>>() {
                @Override
                public Observable<MacLocation> call(
                    TableUpdate<GenericTableSchema> update) {
                    return macTableUpdateToMacLocation(ucastMacsRemoteTable,
                                                       update);
                }
            });
    }

    /**
     * Transform a mac table update into a sequence of MacLocation object.
     */
    private Observable<MacLocation> macTableUpdateToMacLocation(
        MacsTable table, TableUpdate<GenericTableSchema> update) {

        Collection<MacLocation> macLocations = new ArrayList<>();

        try {
            final Option<IPv4Addr> tunnelIp = getTunnelIp();
            if (tunnelIp.isEmpty()) {
                log.warn("no tunnel ip available for vtep {}: skipping",
                         endPoint);
                return Observable.from(macLocations);
            }

            Map<UUID,
                TableUpdate<GenericTableSchema>.RowUpdate<GenericTableSchema>>
                changes = update.getRows();

            for (TableUpdate<GenericTableSchema>.RowUpdate<GenericTableSchema>
                rowUpdate : changes.values()) {

                VtepMAC mac = RowUpdateParser.getMac(table, rowUpdate);
                UUID lsId = RowUpdateParser.getLogicalSwitch(table, rowUpdate);

                LogicalSwitch ls = getLogicalSwitch(lsId);
                if (ls == null) {
                    log.info("unknown logical switch {} on vtep {}", lsId,
                             endPoint);
                    continue;
                }

                // Set Vxlan tunnel endpoint to null if this is a deletion
                IPv4Addr tunnelEndPoint =
                    (rowUpdate.getNew() == null) ? null : tunnelIp.get();

                IPv4Addr oldMacIp = (rowUpdate.getOld() == null) ? null :
                                    table.parseIpaddr(rowUpdate.getOld());
                IPv4Addr newMacIp = (rowUpdate.getNew() == null) ? null :
                                    table.parseIpaddr(rowUpdate.getNew());

                // Update mac->ip mappings, if needed
                if (oldMacIp != null && newMacIp != null &&
                    !oldMacIp.equals(newMacIp)) {
                    // We're on an update. Lets remove the old entry and set
                    // the new one. This MacLocation indicates that the mac
                    // and ip have no endpoint. This will be intepreted on
                    // the other side a removal of just the IP (if the mac
                    // had been deleted, the newRow would've be null). A ML
                    // with null ip and null endpoint would be interpreted
                    // as a removal of the mac itself.
                    macLocations.add(
                        new MacLocation(mac, oldMacIp, ls.name(), null));
                }

                // deletions and additions of macs
                IPv4Addr newerIP = (newMacIp == null) ? oldMacIp : newMacIp;
                macLocations.add(new MacLocation(mac, newerIP, ls.name(),
                                                 tunnelEndPoint));
            }

            log.debug("VTEP update translates to: {}", macLocations);
            return Observable.from(macLocations);
        } catch(Throwable exc) {
            log.warn("cannot translate mac table update to maclocation: {}",
                     update, exc);
            return Observable.empty();
        }
    }

    static private class RowUpdateParser {
        static public VtepMAC getMac(MacsTable table,
            TableUpdate<GenericTableSchema>.RowUpdate<GenericTableSchema>
                rowUpdate) {
            VtepMAC macOld = table.parseMac(rowUpdate.getOld());
            return (macOld.mac() == null)? table.parseMac(rowUpdate.getNew())
                                         : macOld;
        }
        static public UUID getLogicalSwitch(MacsTable table,
            TableUpdate<GenericTableSchema>.RowUpdate<GenericTableSchema>
                rowUpdate) {
            UUID ls = table.parseLogicalSwitch(rowUpdate.getOld());
            return (ls == null)? table.parseLogicalSwitch(rowUpdate.getNew()): ls;
        }
    }
}
