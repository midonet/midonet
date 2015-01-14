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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.OvsdbConnectionInfo;
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

import org.midonet.packets.IPv4Addr;

import scala.Function1;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Promise;
import scala.util.Try;

/**
 * A class to handle data exchanges with an Ovsdb-based VTEP
 */
public class OvsdbVtepData {
    static private final String DB_HARDWARE_VTEP = "hardware_vtep";
    static private final String TB_PHYSICAL_SWITCH = "Physical_Switch";

    private final OvsdbClient client;
    private final OvsdbConnectionInfo connInfo;
    private final VtepEndPoint endPoint;

    private final Executor executor;
    private final ExecutionContext executionCtx;

    private final Promise<DatabaseSchema> dbReady;
    private final Promise<List<IPv4Addr>> tunnelIps;
    private GenericTableSchema physSwitchSchema = null;

    static class OnFutureResult<T> implements Function1<Try<T>, Object> {
        @Override
        final public Object apply(Try<T> value) {
            if (value.isFailure())
                onFailure(value.failed().get());
            else
                onSuccess(value.get());
            return null;
        }
        public void onSuccess(T result) {}
        public void onFailure(Throwable exception) {}
    }

    public OvsdbVtepData(OvsdbClient client) {
        this.client = client;
        this.connInfo = client.getConnectionInfo();
        this.endPoint = VtepEndPoint.apply(
            IPv4Addr.fromBytes(connInfo.getRemoteAddress().getAddress()),
            connInfo.getRemotePort());
        this.executor = Executors.newSingleThreadExecutor();
        this.executionCtx = ExecutionContext.fromExecutor(this.executor);
        this.dbReady = new Promise<>();
        this.tunnelIps = new Promise<>();
        initialize();
        dbReady.future().onComplete(new OnFutureResult<DatabaseSchema>() {
            @Override public void onSuccess(DatabaseSchema dbs) {
                internalTunnelIps(dbs);
            }
            @Override public void onFailure(Throwable exc) {
                tunnelIps.failure(exc);
            }
        }, executionCtx);
    }

    private GenericTableSchema getTblSchema(DatabaseSchema dbs, String name)
        throws VtepUnsupportedException {
        GenericTableSchema tschema = dbs.table(name, GenericTableSchema.class);
        if (tschema == null)
            throw new VtepUnsupportedException(
                endPoint, "failed to retrieve ovsdb " + name + " table");
        return tschema;
    }

    private void initialize() {
        Futures.addCallback(client.getDatabases(),
            new FutureCallback<List<String>>() {
                @Override
                public void onSuccess(List<String> dbList) {
                    if (!dbList.contains(DB_HARDWARE_VTEP)) {
                        dbReady.failure(
                            new VtepUnsupportedException(endPoint,
                                "ovsdb client does not contain any" +
                                DB_HARDWARE_VTEP + " database"));
                    } else {
                        Futures.addCallback(client.getSchema(DB_HARDWARE_VTEP),
                            new FutureCallback<DatabaseSchema>() {
                                @Override
                                public void onSuccess(DatabaseSchema dbs) {
                                    try {
                                        physSwitchSchema = getTblSchema(
                                            dbs, TB_PHYSICAL_SWITCH);
                                        dbReady.success(dbs);
                                    } catch (Throwable exc) {
                                        dbReady.failure(exc);
                                    }
                                }

                                @Override
                                public void onFailure(@Nonnull Throwable exc) {
                                    dbReady.failure(exc);
                                }
                            }
                        );
                    }

                }

                @Override
                public void onFailure(@Nonnull Throwable exc) {
                    dbReady.failure(exc);
                }
            }
        );
    }

    private Future<OperationResult> doSingleOp(DatabaseSchema dbs, Operation op) {
        final Promise<OperationResult> result = new Promise<>();
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
        return result.future();
    }

    private void internalTunnelIps(DatabaseSchema dbs) {
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

            doSingleOp(dbs, op).onComplete(new OnFutureResult<OperationResult>() {
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

    public Future<IPv4Addr> getTunnelIp() {
        final Promise<IPv4Addr> result = new Promise<>();
        tunnelIps.future().onComplete(new OnFutureResult<List<IPv4Addr>>() {
            @Override public void onFailure(Throwable exc) {
                result.failure(exc);
            }
            @Override public void onSuccess(List<IPv4Addr> ipList) {
                if (ipList.isEmpty())
                    result.failure(new NoSuchElementException(
                        "no tunnel IP for vtep: " + endPoint));
                else
                    result.success(ipList.get(0));
            }
        }, executionCtx);
        return result.future();
    }

}
