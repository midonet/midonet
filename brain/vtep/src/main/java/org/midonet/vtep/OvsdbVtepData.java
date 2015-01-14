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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
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

import org.midonet.packets.IPv4Addr;
import org.midonet.vtep.model.LogicalSwitch;
import org.midonet.vtep.model.MacLocation;
import org.midonet.vtep.model.PhysicalSwitch;
import org.midonet.vtep.model.VtepMAC;
import org.midonet.vtep.schema.LogicalSwitchTable;
import org.midonet.vtep.schema.MacsTable;
import org.midonet.vtep.schema.PhysicalSwitchTable;
import org.midonet.vtep.schema.Table;
import org.midonet.vtep.util.Expectation;
import org.midonet.vtep.util.SameThreadExecutor;

import static org.midonet.vtep.util.OvsdbUtil.endPointFromOvsdbClient;
import static org.midonet.vtep.util.OvsdbUtil.getDbSchema;
import static org.midonet.vtep.util.OvsdbUtil.getTblSchema;
import static org.midonet.vtep.util.OvsdbUtil.newMonitor;
import static org.midonet.vtep.util.OvsdbUtil.newMonitorRequest;
import static org.midonet.vtep.util.OvsdbUtil.singleOp;
import scala.Function1;
import scala.Option;
import scala.concurrent.Await;
import scala.concurrent.Future;
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

    static private final Logger log =
        LoggerFactory.getLogger(OvsdbVtepData.class);

    static public Expectation<OvsdbVtepData> get(final OvsdbClient client,
                                                 final Executor executor) {
        final Expectation<OvsdbVtepData> result = new Expectation<>();
        final VtepEndPoint vtep = endPointFromOvsdbClient(client);

        Expectation<DatabaseSchema> dbReady =
            getDbSchema(client, DB_HARDWARE_VTEP, executor);
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
                    MacsTable uMacsLocalTable = new MacsTable(
                        dbs, getTblSchema(dbs, TB_UCAST_MACS_LOCAL));
                    MacsTable uMacsRemoteTable = new MacsTable(
                        dbs, getTblSchema(dbs, TB_UCAST_MACS_REMOTE));

                    result.success(
                        new OvsdbVtepData(executor, client, vtep, dbs,
                                          logicalSwitchTable,
                                          physicalSwitchTable,
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
    private final DatabaseSchema databaseSchema;
    private final LogicalSwitchTable logicalSwitchTable;
    private final PhysicalSwitchTable physicalSwitchTable;
    private final MacsTable ucastMacsLocalTable;
    private final MacsTable ucastMacsRemoteTable;
    private final Expectation<PhysicalSwitch>
        physicalSwitch = new Expectation<>();
    private final ConcurrentHashMap<UUID, Expectation<Option<LogicalSwitch>>>
        logicalSwitches = new ConcurrentHashMap<>();

    private OvsdbVtepData(Executor executor, OvsdbClient client,
                          VtepEndPoint endPoint, DatabaseSchema databaseSchema,
                          LogicalSwitchTable logicalSwitchTable,
                          PhysicalSwitchTable physicalSwitchTable,
                          MacsTable ucastMacsLocalTable,
                          MacsTable ucastMacsRemoteTable) {
        this.executor = executor;
        this.client = client;
        this.endPoint = endPoint;
        this.databaseSchema = databaseSchema;
        this.logicalSwitchTable = logicalSwitchTable;
        this.physicalSwitchTable = physicalSwitchTable;
        this.ucastMacsLocalTable = ucastMacsLocalTable;
        this.ucastMacsRemoteTable = ucastMacsRemoteTable;

        retrievePhysicalSwitch(physicalSwitch);
    }

    private void retrievePhysicalSwitch(
        final Expectation<PhysicalSwitch> result) {
        Select<GenericTableSchema> op = physicalSwitchTable.selectAll();
        op.addCondition(physicalSwitchTable.getManagementIpsMatcher(
            endPoint.mgmtIp()));
        Expectation<OperationResult> opResult =
            singleOp(client, databaseSchema, op, executor);
        opResult.onComplete(new Expectation.OnComplete<OperationResult>() {
            @Override
            public void onSuccess(OperationResult r) {
                Collection<Row<GenericTableSchema>> rows = r.getRows();
                if (rows.isEmpty()) {
                    result.failure(new VtepException(
                        endPoint,
                        "cannot retrieve vtep's physical switch data"));
                } else {
                    result.success(physicalSwitchTable.parsePhysicalSwitch(
                        rows.iterator().next()));
                }
            }

            @Override
            public void onFailure(Throwable exc) {
                result.failure(new VtepException(endPoint, exc));
            }
        }, executor);
    }

    private void retrieveLogicalSwitch(
        final UUID uuid, final Expectation<Option<LogicalSwitch>> result) {
        Select<GenericTableSchema> op = logicalSwitchTable.selectAll();
        op.addCondition(logicalSwitchTable.getUuidMatcher(uuid));
        Expectation<OperationResult> opResult =
            singleOp(client, databaseSchema, op, executor);
        opResult.onComplete(new Expectation.OnComplete<OperationResult>() {
            @Override
            public void onSuccess(OperationResult r) {
                Collection<Row<GenericTableSchema>> rows = r.getRows();
                if (rows.isEmpty())
                    // generate a 'None' from java...
                    result.success(Option.apply((LogicalSwitch)null));
                else {
                    result.success(Option.apply(
                        logicalSwitchTable.parseLogicalSwitch(
                            rows.iterator().next())));
                }
            }
            @Override
            public void onFailure(Throwable exc) {
                result.failure(new VtepException(endPoint, exc));
            }
        }, executor);
    }

    /**
     * Get the logical switch information
     */
    public Future<Option<LogicalSwitch>> getLogicalSwitch(UUID lsId) {
        Expectation<Option<LogicalSwitch>> lsFuture = new Expectation<>();
        Expectation<Option<LogicalSwitch>> lsData =
            logicalSwitches.putIfAbsent(lsId, lsFuture);
        if (lsData == null) {
            retrieveLogicalSwitch(lsId, lsFuture);
            return lsFuture.future();
        } else {
            return lsData.future();
        }
    }


    /**
     * Get the list of tunnel ip addresses for this particular vtep
     * end point.
     */
    public Future<Set<IPv4Addr>> getTunnelIps() {
        return physicalSwitch.future().map(
            new Function1<PhysicalSwitch, Set<IPv4Addr>>() {
                @Override public Set<IPv4Addr> apply(PhysicalSwitch ps) {
                    return ps.tunnelIps();
                }
            }, SameThreadExecutor.asExecutionContext());
    }

    /**
     * Get the 'main' tunnel ip address for this particular vtep end point.
     * Note: we assume there is a single tunnel ip address per vtep... in case
     * there were more than one, we make sure we always return the same one.
     */
    public Future<Option<IPv4Addr>> getTunnelIp() {
        return physicalSwitch.future().map(
            new Function1<PhysicalSwitch, Option<IPv4Addr>>() {
                @Override public Option<IPv4Addr> apply(PhysicalSwitch ps) {
                    return ps.tunnelIp();
                }
            }, SameThreadExecutor.asExecutionContext());
    }

    /**
     * apply a mac location update
     */
    public void applyRemoteMacLocation(MacLocation macLocation) {
        log.debug("Received MAC location update: {}", macLocation);
        if (macLocation == null)
            return;

        if (macLocation.mac().isUcast()) {
            if (macLocation.vxlanTunnelEndpoint() == null) {
                applyRemoteUcastDeletion(macLocation);
            } else {
                applyRemoteUcastAddition(macLocation);
            }
        } else {
            if (macLocation.vxlanTunnelEndpoint() == null) {
                applyRemoteMcastDeletion(macLocation);
            } else {
                applyRemoteMcastAddition(macLocation);
            }
        }
    }

    private void applyRemoteUcastAddition(MacLocation macLocation) {
        Insert<GenericTableSchema> op =
            new Insert<>(ucastMacsRemoteTable.getSchema());
    }
    private void applyRemoteUcastDeletion(MacLocation macLocation) {

    }
    private void applyRemoteMcastAddition(MacLocation macLocation) {

    }
    private void applyRemoteMcastDeletion(MacLocation macLocation) {

    }

    private Row<GenericTableSchema> macLocationToRow(MacsTable table,
                                                     MacLocation macLocation) {
        Row<GenericTableSchema> row = new Row<>(table.getSchema());
        return row;
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
            final Option<IPv4Addr> tunnelIp =
                Await.result(getTunnelIp(), Duration.Inf());
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

                VtepMAC mac = VtepMAC.fromString(
                    RowUpdateParser.getMac(table, rowUpdate));
                UUID lsId =
                    RowUpdateParser.getLogicalSwitch(table, rowUpdate);

                Option<LogicalSwitch> ls = Await.result(getLogicalSwitch(lsId),
                                                        Duration.Inf());
                if (ls.isEmpty()) {
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
                    macLocations
                        .add(new MacLocation(mac, oldMacIp, ls.get().name(),
                                             null));
                }

                // deletions and additions of macs
                IPv4Addr newerIP = (newMacIp == null) ? oldMacIp : newMacIp;
                macLocations.add(new MacLocation(mac, newerIP, ls.get().name(),
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
        static public String getMac(MacsTable table,
            TableUpdate<GenericTableSchema>.RowUpdate<GenericTableSchema>
                rowUpdate) {
            String mac = table.parseMac(rowUpdate.getOld());
            return (mac == null)? table.parseMac(rowUpdate.getNew()): mac;
        }
        static public UUID getLogicalSwitch(MacsTable table,
            TableUpdate<GenericTableSchema>.RowUpdate<GenericTableSchema>
                rowUpdate) {
            UUID ls = table.parseLogicalSwitch(rowUpdate.getOld());
            return (ls == null)? table.parseLogicalSwitch(rowUpdate.getNew()): ls;
        }
    }
}
