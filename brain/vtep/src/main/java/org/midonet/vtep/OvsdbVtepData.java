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
import java.util.List;
import java.util.NoSuchElementException;

import javax.annotation.Nonnull;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.OvsdbConnectionInfo;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.Column;
import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Operation;
import org.opendaylight.ovsdb.lib.operations.OperationResult;
import org.opendaylight.ovsdb.lib.operations.Select;
import org.opendaylight.ovsdb.lib.operations.TransactionBuilder;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.packets.IPv4Addr;
import org.midonet.vtep.util.Expectation;

import scala.concurrent.ExecutionContext;

/**
 * A class to handle data exchanges with an Ovsdb-based VTEP
 */
public class OvsdbVtepData {

    static private VtepEndPoint endPointFromOvsdbClient(OvsdbClient client) {
        OvsdbConnectionInfo connInfo = client.getConnectionInfo();
        return VtepEndPoint.apply(
            IPv4Addr.fromBytes(connInfo.getRemoteAddress().getAddress()),
            connInfo.getRemotePort());
    }

    static private class OvsdbModel {
        static public final String DB_HARDWARE_VTEP = "hardware_vtep";
        static public final String TB_PHYSICAL_SWITCH = "Physical_Switch";
        static public final String TB_UCAST_MACS_LOCAL = "Ucast_Macs_Local";
        static public final String TB_UCAST_MACS_REMOTE = "Ucast_Macs_Remote";

        public final DatabaseSchema databaseSchema;
        public final GenericTableSchema physicalSwitchSchema;
        public final GenericTableSchema ucastMacsLocalSchema;
        public final GenericTableSchema ucastMacsRemoteSchema;

        static public Expectation<OvsdbModel> get(final OvsdbClient client,
                                                  final ExecutionContext ec) {
            final VtepEndPoint endPoint = endPointFromOvsdbClient(client);
            final Expectation<OvsdbModel> result = new Expectation<>();

            Expectation<DatabaseSchema> dbReady = getDbSchema(endPoint, client);
            dbReady.onFailureForward(result, ec);
            dbReady.onSuccess(new Expectation.OnSuccess<DatabaseSchema>() {
                @Override public void call(DatabaseSchema dbs) {
                    try {
                        GenericTableSchema physSwitch =
                            getTblSchema(endPoint, dbs, TB_PHYSICAL_SWITCH);
                        GenericTableSchema uMacsLocal =
                            getTblSchema(endPoint, dbs, TB_UCAST_MACS_LOCAL);
                        GenericTableSchema uMacsRemote =
                            getTblSchema(endPoint, dbs, TB_UCAST_MACS_REMOTE);
                        result.success(new OvsdbModel(dbs,
                                                      physSwitch,
                                                      uMacsLocal,
                                                      uMacsRemote));
                    } catch(Throwable exc) {
                        result.failure(exc);
                    }
                }
            }, ec);
            return result;
        }

        private OvsdbModel(DatabaseSchema databaseSchema,
                           GenericTableSchema physicalSwitchSchema,
                           GenericTableSchema ucastMacsLocalSchema,
                           GenericTableSchema ucastMacsRemoteSchema) {
            this.databaseSchema = databaseSchema;
            this.physicalSwitchSchema = physicalSwitchSchema;
            this.ucastMacsLocalSchema = ucastMacsLocalSchema;
            this.ucastMacsRemoteSchema = ucastMacsRemoteSchema;
        }

        static private Expectation<DatabaseSchema> getDbSchema(
            final VtepEndPoint endPoint, final OvsdbClient client) {
            final Expectation<DatabaseSchema> result = new Expectation<>();
            Futures.addCallback(client.getDatabases(),
                new FutureCallback<List<String>>() {
                    @Override public void onSuccess(List<String> dbList) {
                        if (!dbList.contains(DB_HARDWARE_VTEP)) {
                            result.failure(
                                new VtepUnsupportedException(
                                    endPoint,
                                    "ovsdb client does not contain any" +
                                    DB_HARDWARE_VTEP + " database")
                            );
                        } else {
                            Futures.addCallback(client.getSchema(DB_HARDWARE_VTEP),
                                new FutureCallback<DatabaseSchema>() {
                                    @Override
                                    public void onSuccess(DatabaseSchema dbs) {
                                        result.success(dbs);
                                    }
                                    @Override
                                    public void onFailure(@Nonnull Throwable exc) {
                                        result.failure(exc);
                                    }
                                }
                            );
                        }
                    }

                    @Override public void onFailure(@Nonnull Throwable exc) {
                        result.failure(exc);
                    }
                }
            );
            return result;
        }

        static private GenericTableSchema getTblSchema(VtepEndPoint endPoint,
                                                       DatabaseSchema dbs,
                                                       String name)
            throws VtepUnsupportedException {
            GenericTableSchema tschema = dbs.table(name,
                                                   GenericTableSchema.class);
            if (tschema == null)
                throw new VtepUnsupportedException(
                    endPoint, "failed to retrieve ovsdb " + name + " table");
            return tschema;
        }


    }

    private final OvsdbClient client;
    private final VtepEndPoint endPoint;

    private final ExecutionContext executionCtx;

    private final Expectation<OvsdbModel> ready;
    private final Expectation<List<IPv4Addr>> tunnelIps = new Expectation<>();

    public OvsdbVtepData(OvsdbClient client, ExecutionContext executionCtx) {
        this.client = client;
        this.endPoint = endPointFromOvsdbClient(client);
        this.executionCtx = executionCtx;
        this.ready = OvsdbModel.get(client, executionCtx);

        this.ready.onFailureForward(tunnelIps, executionCtx);
        this.ready.onSuccess(new Expectation.OnSuccess<OvsdbModel>() {
            @Override
            public void call(OvsdbModel model) {
                internalTunnelIps(model);
            }
        }, executionCtx);
    }

    private Expectation<OperationResult> doSingleOp(DatabaseSchema dbs, Operation op) {
        final Expectation<OperationResult> result = new Expectation<>();
        TransactionBuilder transaction = client.transactBuilder(dbs);
        transaction.add(op);
        Futures.addCallback(transaction.execute(),
            new FutureCallback<List<OperationResult>>() {
                @Override
                public void onSuccess(List<OperationResult> rlist) {
                    try {
                        if (rlist.size() == 0)
                            throw new VtepException(endPoint,
                                "ovsdb operation got no results");
                        if (rlist.size() > 1)
                            throw new VtepException(endPoint,
                                "ovsdb operation got too many results: " +
                                rlist.size());
                        result.success(rlist.get(0));
                    } catch (Throwable exc) {
                        result.failure(exc);
                    }
                }

                @Override
                public void onFailure(@Nonnull Throwable err) {
                    result.failure(err);
                }
            }
        );
        return result;
    }

    // Retrieve the tunnel Ips for this particular vtep endpoint
    // Note: it satisfies the tunnelIps expectation.
    private void internalTunnelIps(OvsdbModel model) {
        DatabaseSchema dbs = model.databaseSchema;
        GenericTableSchema physSwitchSchema = model.physicalSwitchSchema;
        try {
            final ColumnSchema mgmtIpsCol =
                physSwitchSchema.column("management_ips");
            final ColumnSchema tunnelIpsCol =
                physSwitchSchema.column("tunnel_ips");
            Condition matchMgmtIp =
                new Condition(mgmtIpsCol.getName(), Function.EQUALS,
                              endPoint.mgmtIp().toString());

            Select<GenericTableSchema> op = new Select<>(physSwitchSchema);
            op.column(tunnelIpsCol);
            op.addCondition(matchMgmtIp);

            doSingleOp(dbs, op).onComplete(
                new Expectation.OnComplete<OperationResult>() {
                @Override public void onFailure(Throwable exc) {
                    tunnelIps.failure(exc);
                }
                @Override public void onSuccess(OperationResult result) {
                    try {
                        List<IPv4Addr> ipList = new ArrayList<>();
                        for (Row<GenericTableSchema> row : result.getRows()) {
                            Column<?, String> data = row.getColumn(tunnelIpsCol);
                            ipList.add(IPv4Addr.fromString(data.getData()));
                        }
                        tunnelIps.success(ipList);
                    } catch (Throwable exc) {
                        tunnelIps.failure(exc);
                    }
                }
            }, executionCtx);
        } catch (Throwable err) {
            tunnelIps.failure(err);
        }
    }

    /**
     * Get the cached list of tunnel ip addresses for this particular vtep
     * end point.
     */
    public Expectation<IPv4Addr> getTunnelIp() {
        final Expectation<IPv4Addr> result = new Expectation<>();

        tunnelIps.onComplete(new Expectation.OnComplete<List<IPv4Addr>>() {
            @Override
            public void onFailure(Throwable exc) {
                result.failure(exc);
            }

            @Override
            public void onSuccess(List<IPv4Addr> ipList) {
                if (ipList.isEmpty())
                    result.failure(new NoSuchElementException(
                        "no tunnel IP for vtep: " + endPoint));
                else
                    result.success(ipList.get(0));
            }
        }, executionCtx);
        return result;
    }

    public Observable<MacLocation> macLocalUpdates() {
        final Subject<MacLocation, MacLocation> updates = PublishSubject.create();
        ready.onComplete(new Expectation.OnComplete<OvsdbModel>() {
            @Override
            public void onFailure(Throwable exc) {updates.onError(exc);}
            @Override
            public void onSuccess(OvsdbModel model) {
                //DatabaseSchema
                //MonitorRequest req = new MonitorRequest(TB_PHYSICAL_SWITCH)
                //TableUpdates macLocal = client.monitor(dbs, )
            }
        }, executionCtx);

        return updates;
    }

}
