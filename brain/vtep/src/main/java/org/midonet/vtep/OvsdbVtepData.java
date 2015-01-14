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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executor;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.operations.OperationResult;
import org.opendaylight.ovsdb.lib.operations.Select;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observer;
import rx.functions.Func1;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import sun.rmi.runtime.Log;

import org.midonet.packets.IPv4Addr;
import org.midonet.vtep.model.LogicalSwitch;
import org.midonet.vtep.model.MacLocation;
import org.midonet.vtep.model.PhysicalLocator;
import org.midonet.vtep.model.PhysicalSwitch;
import org.midonet.vtep.model.VtepMAC;
import org.midonet.vtep.schema.LogicalSwitchTable;
import org.midonet.vtep.schema.MacsTable;
import org.midonet.vtep.schema.PhysicalLocatorSetTable;
import org.midonet.vtep.schema.PhysicalLocatorTable;
import org.midonet.vtep.schema.PhysicalSwitchTable;
import org.midonet.vtep.schema.Table;
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
                    MacsTable uMacsLocalTable = new MacsTable(
                        dbs, getTblSchema(dbs, TB_UCAST_MACS_LOCAL));
                    MacsTable uMacsRemoteTable = new MacsTable(
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
    private final MacsTable ucastMacsLocalTable;
    private final MacsTable ucastMacsRemoteTable;

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
    private final ConcurrentHashMap<IPv4Addr, Set<UUID>>
        physicalLocatorSetsByIp = new ConcurrentHashMap<>();

    private OvsdbVtepData(Executor executor, OvsdbClient client,
                          VtepEndPoint endPoint, DatabaseSchema databaseSchema,
                          LogicalSwitchTable logicalSwitchTable,
                          PhysicalSwitchTable physicalSwitchTable,
                          PhysicalLocatorSetTable physicalLocatorSetTable,
                          PhysicalLocatorTable physicalLocatorTable,
                          MacsTable ucastMacsLocalTable,
                          MacsTable ucastMacsRemoteTable) throws Exception {
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

        this.physicalSwitch = getPhysicalSwitchInternal();

    }

    /*
     * Physical switches
     */
    private PhysicalSwitch getPhysicalSwitchInternal() throws Exception {
        Collection<Row<GenericTableSchema>> rows = Await.result(
            query(client, physicalSwitchTable,
                  physicalSwitchTable.getManagementIpsMatcher(
                      endPoint.mgmtIp())).future(), Duration.Inf());
        if (rows.isEmpty())
            throw new VtepException(
                endPoint, "cannot retrieve vtep's physical switch data");
        return physicalSwitchTable.parsePhysicalSwitch(rows.iterator().next());
    }

    /*
     * Logical switches
     */
    private LogicalSwitch getLogicalSwitchInternal(Condition cond)
        throws Exception {
        Collection<Row<GenericTableSchema>> rows = Await.result(
            query(client, logicalSwitchTable, cond).future(), Duration.Inf());
        if (rows.isEmpty())
            return null;
        return logicalSwitchTable.parseLogicalSwitch(rows.iterator().next());
    }

    private LogicalSwitch getLogicalSwitchInternal(UUID id)
        throws Exception {
        return getLogicalSwitchInternal(logicalSwitchTable.getUuidMatcher(id));
    }

    private LogicalSwitch getLogicalSwitchInternal(String name)
        throws Exception {
        return getLogicalSwitchInternal(logicalSwitchTable.getNameMatcher(name));
    }

    /**
     * Get logical switch data
     */
    public LogicalSwitch getLogicalSwitch(UUID id) throws Exception {
        LogicalSwitch ls = logicalSwitches.get(id);
        if (ls == null) {
            ls = getLogicalSwitchInternal(id);
            if (ls != null) {
                logicalSwitches.put(ls.uuid(), ls);
                logicalSwitchesByName.put(ls.name(), ls);
            }
        }
        return ls;
    }

    public LogicalSwitch getLogicalSwitch(String name) throws Exception {
        LogicalSwitch ls = logicalSwitchesByName.get(name);
        if (ls == null) {
            ls = getLogicalSwitchInternal(name);
            if (ls != null) {
                logicalSwitches.put(ls.uuid(), ls);
                logicalSwitchesByName.put(ls.name(), ls);
            }
        }
        return ls;
    }

    /*
     * Physical locators
     */
    private Set<PhysicalLocator> getPhysicalLocatorInternal(Condition cond)
        throws Exception {
        Collection<Row<GenericTableSchema>> rows = Await.result(
            query(client, physicalLocatorTable, cond).future(), Duration.Inf());
        Set<PhysicalLocator> locators = new HashSet<>();
        for (Row<GenericTableSchema> it: rows) {
            locators.add(physicalLocatorTable.parsePhysicalLocator(it));
        }
        return locators;
    }

    private Set<PhysicalLocator> getPhysicalLocatorInternal(UUID id)
        throws Exception{
        return getPhysicalLocatorInternal(
            physicalLocatorTable.getUuidMatcher(id));
    }

    private Set<PhysicalLocator> getPhysicalLocatorInternal(IPv4Addr ip)
        throws Exception{
        return getPhysicalLocatorInternal(
            physicalLocatorTable.getDstIpMatcher(ip));
    }

    private Set<PhysicalLocator> getPhysicalLocator(UUID id) throws Exception {
        Set<PhysicalLocator> locators;
        PhysicalLocator pl = physicalLocators.get(id);
        if (pl == null) {
            locators = getPhysicalLocatorInternal(id);
            for(PhysicalLocator loc: locators) {
                physicalLocators.put(loc.uuid(), loc);
                physicalLocatorsByIp.putIfAbsent(
                    loc.dstIp(), new ConcurrentSkipListSet<PhysicalLocator>());
                physicalLocatorsByIp.get(loc.dstIp()).add(loc);
            }
        } else {
            locators = new HashSet<>();
            locators.add(pl);
        }
        return locators;
    }

    private Set<PhysicalLocator> getPhysicalLocator(IPv4Addr ip)
        throws Exception {
        Set<PhysicalLocator> locators = physicalLocatorsByIp.get(ip);
        if (locators == null) {
            locators = getPhysicalLocatorInternal(ip);
            for(PhysicalLocator loc: locators) {
                physicalLocators.put(loc.uuid(), loc);
                physicalLocatorsByIp.putIfAbsent(
                    loc.dstIp(), new ConcurrentSkipListSet<PhysicalLocator>());
                physicalLocatorsByIp.get(loc.dstIp()).add(loc);
            }
        }
        return locators;
    }

    private Set<PhysicalLocator> newPhysicalLocator(IPv4Addr ip)
        throws Exception {
        Insert<GenericTableSchema> op = physicalLocatorTable.insert(ip);
        Await.result(modify(client, physicalLocatorTable, op).future(),
                     Duration.Inf());
        return getPhysicalLocator(ip);
    }

    /*
     * Locator sets
     */

    private Set<UUID> getPhysicalLocatorSetInternal(UUID locId)
        throws Exception {
        Collection<Row<GenericTableSchema>> rows = Await.result(
            query(client, physicalLocatorSetTable,
                  physicalLocatorSetTable.getLocatorMatcher(locId)).future(),
            Duration.Inf());
        Set<UUID> locatorSetIds = new HashSet<>();
        for (Row<GenericTableSchema> r: rows) {
            Set<UUID> locatorIds = physicalLocatorSetTable.parseLocators(r);
            // Only interested in single item sets
            if (locatorIds.size() == 1)
                locatorSetIds.add(physicalLocatorSetTable.parseUuid(r));
        }
        return locatorSetIds;
    }

    private Set<UUID> getPhysicalLocatorSet(IPv4Addr ip)
        throws Exception {
        Set<UUID> locatorIds = physicalLocatorSetsByIp.get(ip);
        if (locatorIds == null) {
            Set<UUID> newSet = new ConcurrentSkipListSet<>();
            Set<PhysicalLocator> locators = getPhysicalLocator(ip);
            for (PhysicalLocator loc: locators) {
                newSet.addAll(getPhysicalLocatorSetInternal(loc.uuid()));
            }
            if (newSet.isEmpty())
                return null;
            locatorIds = physicalLocatorSetsByIp.putIfAbsent(ip, newSet);
            return (locatorIds == null)? newSet: locatorIds;
        } else {
            return locatorIds;
        }
    }

    private Set<UUID> newPhysicalLocatorSetInternal(UUID locId)
        throws Exception {
        Insert<GenericTableSchema> op = physicalLocatorSetTable.insert(locId);
        Await.result(modify(client, physicalLocatorTable, op).future(),
                     Duration.Inf());
        return getPhysicalLocatorSetInternal(locId);
    }

    private UUID getPhysicalLocatorSetOrCreate(IPv4Addr ip)
        throws Exception {
        Set<UUID> locatorSets = getPhysicalLocatorSet(ip);
        if (locatorSets == null) {
            Set<PhysicalLocator> locators = getPhysicalLocator(ip);
            if (locators.isEmpty()) {
                // create a new locator with the desired IP
                locators = newPhysicalLocator(ip);
                if (locators.isEmpty())
                    throw new VtepException(
                        endPoint, "cannot create physical locator for: " + ip);
            }
            PhysicalLocator loc = locators.iterator().next();
            newPhysicalLocatorSetInternal(loc.uuid());
            locatorSets = getPhysicalLocatorSet(ip);
            if (locatorSets == null)
                throw new VtepException(
                    endPoint, "cannot create physical locator set for: " + ip);
        }
        return locatorSets.iterator().next();
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

        LogicalSwitch ls = null;
        try {
            ls = getLogicalSwitch(macLocation.logicalSwitchName());
        } catch (Throwable exc) {
            log.warn("cannot retrieve vtep logical switch info: {} {}",
                     endPoint, macLocation.logicalSwitchName(), exc);
            throw new VtepException(endPoint, exc);
        }
        if (ls == null) {
            log.warn("unknown vtep logical switch: {} {}",
                     endPoint, macLocation.logicalSwitchName());
            return;
        }

        if (macLocation.vxlanTunnelEndpoint() != null) {
            UUID locatorSet = null;
            try {
                locatorSet = getPhysicalLocatorSetOrCreate(
                    macLocation.vxlanTunnelEndpoint());
            } catch (Throwable exc) {
                log.warn("cannot retrieve vtep physical locator set for ip: {} {}",
                         endPoint, macLocation.vxlanTunnelEndpoint(), exc);
                throw new VtepException(endPoint, exc);
            }

            if (macLocation.mac().isUcast()) {
                applyRemoteUcastAddition(macLocation, ls, locatorSet);
            } else {
                applyRemoteMcastAddition(macLocation, ls, locatorSet);
            }
        } else {
            if (macLocation.mac().isUcast()) {
                applyRemoteUcastDeletion(macLocation, ls);
            } else {
                applyRemoteMcastDeletion(macLocation, ls);
            }
        }
    }

    private void applyRemoteUcastAddition(MacLocation macLocation,
                                          LogicalSwitch ls, UUID locatorSet)
        throws Exception {
        Insert<GenericTableSchema> op = ucastMacsRemoteTable.insert(
            macLocation.mac(), locatorSet, ls.uuid(), macLocation.ipAddr());
        Await.ready(modify(client, ucastMacsRemoteTable, op).future(),
                    Duration.Inf());
    }

    private void applyRemoteUcastDeletion(MacLocation macLocation,
                                          LogicalSwitch ls) {

    }
    private void applyRemoteMcastAddition(MacLocation macLocation,
                                          LogicalSwitch ls, UUID locatorSet) {

    }
    private void applyRemoteMcastDeletion(MacLocation macLocation,
                                          LogicalSwitch ls) {

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
