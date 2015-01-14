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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Executor;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
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

import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.vtep.model.LogicalSwitch;
import org.midonet.vtep.model.PhysicalSwitch;
import org.midonet.vtep.model.VtepMAC;
import org.midonet.vtep.schema.LogicalSwitchTable;
import org.midonet.vtep.schema.MacsTable;
import org.midonet.vtep.schema.PhysicalSwitchTable;
import org.midonet.vtep.schema.Table;
import org.midonet.vtep.util.Expectation;

import static org.midonet.vtep.util.OvsdbUtil.endPointFromOvsdbClient;
import static org.midonet.vtep.util.OvsdbUtil.getDbSchema;
import static org.midonet.vtep.util.OvsdbUtil.getTblSchema;
import static org.midonet.vtep.util.OvsdbUtil.newMonitor;
import static org.midonet.vtep.util.OvsdbUtil.newMonitorRequest;
import static org.midonet.vtep.util.OvsdbUtil.singleOp;
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
                    result.failure(exc);
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
    private final Expectation<PhysicalSwitch> physicalSwitch;

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
        this.physicalSwitch = getPhysicalSwitch();
    }

    private Expectation<PhysicalSwitch> getPhysicalSwitch() {
        final Expectation<PhysicalSwitch> result = new Expectation<>();
        Select<GenericTableSchema> op = physicalSwitchTable.selectAll();
        op.addCondition(physicalSwitchTable.getManagementIpsMatcher(
            endPoint.mgmtIp()));
        Expectation<OperationResult> opResult =
            singleOp(client, databaseSchema, op, executor);
        opResult.onFailureForward(result, executor);
        opResult.onSuccess(new Expectation.OnSuccess<OperationResult>() {
            @Override public void call(OperationResult r) {
                Collection<Row<GenericTableSchema>> rows = r.getRows();
                if (rows.isEmpty()) {
                    result.failure(new NoSuchElementException());
                } else {
                    PhysicalSwitchTable
                    result.success(physicalSwitchTable.parsePhysicalSwitch(
                        rows.iterator().next()));
                }
            }
        }, executor);
        return result;
    }

    private Expectation<LogicalSwitch> getLogicalSwitch(final UUID uuid) {
        final Expectation<LogicalSwitch> result = new Expectation<>();
        Select<GenericTableSchema> op = logicalSwitchTable.selectAll();
        op.addCondition(logicalSwitchTable.getUuidMatcher(uuid));
        Expectation<OperationResult> opResult =
            singleOp(client, databaseSchema, op, executor);
        opResult.onFailureForward(result, executor);
        opResult.onSuccess(new Expectation.OnSuccess<OperationResult>() {
            @Override public void call(OperationResult r) {
                Collection<Row<GenericTableSchema>> rows = r.getRows();
                if (rows.isEmpty())
                    result.failure(new NoSuchElementException());
                else {
                    result.success(logicalSwitchTable.parseLogicalSwitch(
                        rows.iterator().next()));
                }
            }
        }, executor);
        return result;
    }

    /**
     * Get the list of tunnel ip addresses for this particular vtep
     * end point.
     */
    public IPv4Addr getTunnelIp() {
        try {
            return Await.result(
                physicalSwitch.future(), Duration.Inf()).tunnelIp();
        } catch (Throwable exc) {
            log.info("failed to retrieve tunnel ips for vtep " + endPoint, exc);
            return null;
        }
    }

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
    public Observable<TableUpdate<GenericTableSchema>> ucastMacLocalUpdates() {
        final Subject<TableUpdate<GenericTableSchema>,
            TableUpdate<GenericTableSchema>> updates = PublishSubject.create();
        monitorTableUpdates(ucastMacsLocalTable, updates);
        return updates;
    }

    /**
     * Get an observable with raw UcastMacsRemote table updates
     */
    public Observable<TableUpdate<GenericTableSchema>> ucastMacRemoteUpdates() {
        final Subject<TableUpdate<GenericTableSchema>,
            TableUpdate<GenericTableSchema>> updates = PublishSubject.create();
        monitorTableUpdates(ucastMacsRemoteTable, updates);
        return updates;
    }

    /**
     * Transform a mac table update into a sequence of MacLocation object
     */
    private Observable<MacLocation> macTableUpdateToMacLocation(
        MacsTable table, TableUpdate<GenericTableSchema> update) {
        IPv4Addr tunnelIp = getTunnelIp();

        Collection<MacLocation> macLocations = new ArrayList<>();

        Map<UUID, TableUpdate<GenericTableSchema>.RowUpdate<GenericTableSchema>>
            changes = update.getRows();
        for (TableUpdate<GenericTableSchema>.RowUpdate<GenericTableSchema>
            rowUpdate: changes.values()) {
            VtepMAC mac = VtepMAC.fromString(RowUpdateParser.getMac(table,
                                                                    rowUpdate));
            UUID lsId = RowUpdateParser.getLogicalSwitch(table, rowUpdate);

            // TODO this should be cached
            LogicalSwitch ls = null;
            try {
                ls = Await.result(getLogicalSwitch(lsId).future(),
                                  Duration.Inf());
            } catch (Throwable exc) {
                log.warn("cannot access logical switch info: " + lsId, exc);
            }
            if (ls == null)
                continue;

            // Set Vxlan tunnel endpoint to null if this is a deletion
            IPv4Addr endPoint = (rowUpdate.getNew() == null)? null: tunnelIp;

            IPv4Addr oldMacIp = (rowUpdate.getOld() == null)? null:
                                table.parseIpaddr(rowUpdate.getOld());
            IPv4Addr newMacIp = (rowUpdate.getNew() == null)? null:
                                table.parseIpaddr(rowUpdate.getNew());

            if (oldMacIp != null && newMacIp != null &&
                !oldMacIp.equals(newMacIp)) {
                // We're on an update. Lets remove the old entry and set the new
                // one. This MacLocation indicates that the mac and ip have no
                // endpoint. This will be intepreted on the other side a a removal
                // of just the IP (if the mac had been deleted, the newRow would've
                // bee null). A ML with null ip and null endpoint would be
                // interpreted as a removal of the mac itself.
                macLocations.add(new MacLocation(mac, oldMacIp, ls.name(), null));
            }

            // deletions and additions of macs
            IPv4Addr newerIP = (newMacIp == null)? oldMacIp: newMacIp;
            macLocations.add(new MacLocation(mac, newerIP, ls.name(), endPoint));
        }

        return Observable.from(macLocations);
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
