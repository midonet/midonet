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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.notation.Column;
import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.OperationResult;
import org.opendaylight.ovsdb.lib.operations.Select;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import rx.Observable;
import rx.functions.Func1;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.packets.IPv4Addr;
import org.midonet.vtep.model.VtepMAC;
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

    static private class OvsdbModel {
        static public final String DB_HARDWARE_VTEP = "hardware_vtep";
        static public final String TB_PHYSICAL_SWITCH = "Physical_Switch";
        static public final String TB_UCAST_MACS_LOCAL = "Ucast_Macs_Local";
        static public final String TB_UCAST_MACS_REMOTE = "Ucast_Macs_Remote";

        static public final String COL_MANAGEMENT_IPS = "management_ips";
        static public final String COL_TUNNEL_IPS = "tunnel_ips";

        static public final String COL_MAC = "MAC";
        static public final String COL_LOGICAL_SWITCH = "logical_switch";
        static public final String COL_LOCATOR = "locator";
        static public final String COL_IPADDR = "ipaddr";

        public final VtepEndPoint endPoint;
        public final DatabaseSchema databaseSchema;
        public final GenericTableSchema physicalSwitchSchema;

        public final UcastMacsLocalTable ucastMacsLocalTable;
        public final UcastMacsRemoteTable ucastMacsRemoteTable;

        abstract class MacsTable {
            protected GenericTableSchema tableSchema;

            public GenericTableSchema getSchema() {
                return tableSchema;
            }

            public Collection<ColumnSchema<GenericTableSchema, ?>> getColumnsSchemas() {
                Collection<ColumnSchema<GenericTableSchema, ?>> cols = new ArrayList<>();
                cols.add(getMacColumnSchema());
                cols.add(getLogicalSwitchColumnSchema());
                cols.add(getLocatorColumnSchema());
                cols.add(getIpaddrColumnSchema());
                return cols;
            }

            public ColumnSchema<GenericTableSchema, String> getMacColumnSchema() {
                return tableSchema.column(COL_MAC, String.class);
            }

            public ColumnSchema<GenericTableSchema, UUID> getLogicalSwitchColumnSchema() {
                return tableSchema.column(COL_LOGICAL_SWITCH, UUID.class);
            }

            public ColumnSchema<GenericTableSchema, UUID> getLocatorColumnSchema() {
                return tableSchema.column(COL_LOCATOR, UUID.class);
            }

            public ColumnSchema<GenericTableSchema, String> getIpaddrColumnSchema() {
                return tableSchema.column(COL_IPADDR, String.class);
            }

            public String parseMac(Row<GenericTableSchema> r) {
                Column<GenericTableSchema, String> col = r.getColumn(getMacColumnSchema());
                return col.getData();
            }
        }

        class UcastMacsLocalTable extends MacsTable {
            public UcastMacsLocalTable(GenericTableSchema tableSchema) {
                this.tableSchema = tableSchema;
            }
        }

        class UcastMacsRemoteTable extends MacsTable {
            public UcastMacsRemoteTable(GenericTableSchema tableSchema) {
                this.tableSchema = tableSchema;
            }
        }

        static public Expectation<OvsdbModel> get(final OvsdbClient client,
                                                  final Executor executor) {
            final Expectation<OvsdbModel> result = new Expectation<>();
            final VtepEndPoint ep = endPointFromOvsdbClient(client);

            Expectation<DatabaseSchema> dbReady =
                getDbSchema(client, DB_HARDWARE_VTEP, executor);
            dbReady.onFailureForward(result, executor);
            dbReady.onSuccess(new Expectation.OnSuccess<DatabaseSchema>() {
                @Override public void call(DatabaseSchema dbs) {
                    try {
                        GenericTableSchema physSwitch =
                            getTblSchema(dbs, TB_PHYSICAL_SWITCH);
                        GenericTableSchema uMacsLocal =
                            getTblSchema(dbs, TB_UCAST_MACS_LOCAL);
                        GenericTableSchema uMacsRemote =
                            getTblSchema(dbs, TB_UCAST_MACS_REMOTE);
                        result.success(new OvsdbModel(ep,
                                                      dbs,
                                                      physSwitch,
                                                      uMacsLocal,
                                                      uMacsRemote));
                    } catch(NoSuchElementException exc) {
                        result.failure(new VtepUnsupportedException(ep, exc));
                    } catch(Throwable exc) {
                        result.failure(exc);
                    }
                }
            }, executor);
            return result;
        }

        private OvsdbModel(VtepEndPoint endPoint,
                           DatabaseSchema databaseSchema,
                           GenericTableSchema physicalSwitchSchema,
                           GenericTableSchema ucastMacsLocalSchema,
                           GenericTableSchema ucastMacsRemoteSchema) {
            this.endPoint = endPoint;
            this.databaseSchema = databaseSchema;
            this.physicalSwitchSchema = physicalSwitchSchema;

            this.ucastMacsLocalTable = new UcastMacsLocalTable(ucastMacsLocalSchema);
            this.ucastMacsRemoteTable = new UcastMacsRemoteTable(ucastMacsRemoteSchema);
        }

        /**
         * Get the column schema for management ips
         */
        public ColumnSchema<GenericTableSchema, String> getManagementIpsSchema() {
            return physicalSwitchSchema.column(COL_MANAGEMENT_IPS, String.class);
        }

        public Condition matchManagementIps(String value) {
            return new Condition(COL_MANAGEMENT_IPS, Function.EQUALS, value);
        }

        public Condition matchManagementIps(IPv4Addr value) {
            return new Condition(COL_MANAGEMENT_IPS, Function.EQUALS,
                                 value.toString());
        }

        /**
         * Get the column schema for tunnel ips
         */
        public ColumnSchema<GenericTableSchema, String> getTunnelIpsSchema() {
            return physicalSwitchSchema.column(COL_TUNNEL_IPS, String.class);
        }

    }

    private final OvsdbClient client;
    private final VtepEndPoint endPoint;
    private final Executor executor;
    private final Expectation<OvsdbModel> model;

    public OvsdbVtepData(OvsdbClient client, Executor executor) {
        this.client = client;
        this.endPoint = endPointFromOvsdbClient(client);
        this.executor = executor;
        this.model = OvsdbModel.get(client, executor);
    }

    /**
     * Get the list of tunnel ip addresses for this particular vtep
     * end point.
     */
    public Expectation<List<IPv4Addr>> getTunnelIps() {
        final Expectation<List<IPv4Addr>> result = new Expectation<>();
        model.onFailureForward(result, executor);
        model.onSuccess(new Expectation.OnSuccess<OvsdbModel>() {
            @Override public void call(final OvsdbModel m) {
                Select<GenericTableSchema> op =
                    new Select<>(m.physicalSwitchSchema);
                op.column(m.getTunnelIpsSchema());
                op.addCondition(m.matchManagementIps(endPoint.mgmtIp()));
                Expectation<OperationResult> opResult =
                    singleOp(client, m.databaseSchema, op, executor);
                opResult.onFailureForward(result, executor);
                opResult.onSuccess(new Expectation.OnSuccess<OperationResult>(){
                    @Override public void call(OperationResult r) {
                        List<IPv4Addr> ipList = new ArrayList<>();
                        for (Row<GenericTableSchema> row: r.getRows()) {
                            Column<?, String> data =
                                row.getColumn(m.getTunnelIpsSchema());
                            ipList.add(IPv4Addr.fromString(data.getData()));
                        }
                        result.success(ipList);
                    }
                }, executor);
            }
        }, executor);

        return result;
    }

    /**
     * Get an observable with raw UcastMacsLocal table updates
     */
    public Observable<TableUpdate> ucastMacLocalUpdates() {
        final Subject<TableUpdate, TableUpdate> updates =
            PublishSubject.create();
        model.onFailure(new Expectation.OnFailure() {
            @Override public void call(Throwable exc) {
                updates.onError(exc);
            }
        }, executor);
        model.onSuccess(new Expectation.OnSuccess<OvsdbModel>() {
            @Override public void call(final OvsdbModel m) {
                List<MonitorRequest<GenericTableSchema>> reqList =
                    new ArrayList<>();
                reqList.add(newMonitorRequest(m.ucastMacsLocalTable.getSchema(),
                                              m.ucastMacsLocalTable.getColumnsSchemas()));
                client.monitor(m.databaseSchema, reqList,
                               newMonitor(m.ucastMacsLocalTable.getSchema(), updates));
            }
        }, executor);
        return updates;
    }

    public Observable<MacLocation> ucastLocalMacLocations() throws Exception {
        final OvsdbModel m = Await.result(model.future(), Duration.Inf());
        return ucastMacLocalUpdates().concatMap(
            new Func1<TableUpdate, Observable<MacLocation>>() {
                @Override
                public Observable<MacLocation> call(TableUpdate tableUpdate) {
                    Map<UUID, TableUpdate.RowUpdate> changes =
                        tableUpdate.getRows();
                    for (TableUpdate.RowUpdate row : changes.values()) {
                        VtepMAC vMac = VtepMAC.fromString(RowParser.getMac(m.ucastMacsLocalTable, row));


                    }
                    return null;
                }
            });
    }

    /**
     * Get an observable with raw UcastMacsRemote table updates
     */
    public Observable<TableUpdate> ucastMacRemoteUpdates() {
        final Subject<TableUpdate, TableUpdate> updates =
            PublishSubject.create();
        model.onFailure(new Expectation.OnFailure() {
            @Override public void call(Throwable exc) {
                updates.onError(exc);
            }
        }, executor);
        model.onSuccess(new Expectation.OnSuccess<OvsdbModel>() {
            @Override public void call(final OvsdbModel m) {
                List<MonitorRequest<GenericTableSchema>> reqList =
                    new ArrayList<>();
                reqList.add(newMonitorRequest(
                    m.ucastMacsRemoteTable.getSchema(),
                    m.ucastMacsRemoteTable.getColumnsSchemas()));
                client.monitor(m.databaseSchema, reqList,
                               newMonitor(m.ucastMacsRemoteTable.getSchema(), updates));
            }
        }, executor);
        return updates;
    }

    static class RowParser {
        static public String getMac(OvsdbModel.MacsTable t, Row r) {
            String macStr = (r == null)? null: t.parseMac(r);
            return (macStr == null || macStr.isEmpty())? null: macStr;
        }
        static public String getMac(OvsdbModel.MacsTable t, TableUpdate.RowUpdate rowUpdate) {
            String oldMac = getMac(t, rowUpdate.getOld());
            return (oldMac == null)? getMac(t, rowUpdate.getNew()): oldMac;
        }
    }
}
