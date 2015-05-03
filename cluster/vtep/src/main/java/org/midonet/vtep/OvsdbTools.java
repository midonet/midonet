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
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nonnull;

import scala.concurrent.ExecutionContext;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import org.opendaylight.ovsdb.lib.MonitorCallBack;
import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.OvsdbConnectionInfo;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.MonitorRequestBuilder;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Operation;
import org.opendaylight.ovsdb.lib.operations.OperationResult;
import org.opendaylight.ovsdb.lib.operations.Select;
import org.opendaylight.ovsdb.lib.operations.TransactionBuilder;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import rx.Observable;
import rx.Subscriber;

import org.midonet.cluster.data.vtep.VtepException;
import org.midonet.cluster.data.vtep.model.VtepEndPoint;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.concurrent.CallingThreadExecutionContext$;
import org.midonet.vtep.schema.Table;
import org.midonet.util.concurrent.Expectation;


/**
 * Common utility Procedures related to ovsdb-based vtep management
 */
public class OvsdbTools {

    static private final ExecutionContext callingThreadContext =
        CallingThreadExecutionContext$.MODULE$;
    static private final Executor callingThreadExecutor =
        (Executor)CallingThreadExecutionContext$.MODULE$;

    static public final String DB_HARDWARE_VTEP = "hardware_vtep";

    static public class OvsdbException extends VtepException {
        public OvsdbException(OvsdbClient client, String msg) {
            super(endPointFromOvsdbClient(client), msg);
        }
        public OvsdbException(OvsdbClient client, Throwable cause) {
            super(endPointFromOvsdbClient(client), cause);
        }
    }

    static public class OvsdbOpException extends OvsdbException {
        public final Integer opIndex;
        public final Operation op;
        public final OperationResult result;
        public OvsdbOpException(OvsdbClient client,
                                List<Table.OvsdbOperation> ops,
                                Integer idx, OperationResult r) {
            super(client, (idx >= ops.size())?
                String.format("%s: %s", r.getError(), r.getDetails()):
                String.format("%s on %s: %s: %s", ops.get(idx).op.getOp(),
                              ops.get(idx).op.getTable(), r.getError(),
                              r.getDetails())
            );
            this.opIndex = idx;
            this.op = (idx >= ops.size())? null: ops.get(idx).op;
            this.result = r;
        }
    }

    static public class OvsdbNotFoundException extends OvsdbException {
        public OvsdbNotFoundException(OvsdbClient client, String msg) {
            super(client, msg);
        }
    }

    /**
     * Extract vtep end point information (management ip address and port)
     * from an OvsdbClient
     */
    static public VtepEndPoint endPointFromOvsdbClient(OvsdbClient client) {
        OvsdbConnectionInfo connInfo = client.getConnectionInfo();
        return VtepEndPoint.apply(
            IPv4Addr.fromBytes(connInfo.getRemoteAddress().getAddress()),
            connInfo.getRemotePort());
    }

    static public Boolean opResultHasError(OperationResult r) {
        return r.getError() != null && !r.getError().trim().isEmpty();
    }

    /**
     * Perform a single operation in the ovs database and expect a result
     */
    static public Expectation<OperationResult> singleOp(
        final OvsdbClient client, DatabaseSchema dbs, Table.OvsdbOperation op) {
        final Expectation<OperationResult> result = new Expectation<>();
        multiOp(client, dbs, Collections.singletonList(op)).onComplete(
            new Expectation.OnComplete<List<OperationResult>>() {
                @Override
                public void onSuccess(List<OperationResult> rlist) {
                    result.success(rlist.get(0));
                }

                @Override
                public void onFailure(@Nonnull Throwable exc) {
                    result.failure(exc);
                }
            }, callingThreadContext);
        return result;
    }

    /**
     * Perform multiple operations inside an ovs database transaction
     */
    static public Expectation<List<OperationResult>> multiOp(
        final OvsdbClient client, DatabaseSchema dbs,
        final List<Table.OvsdbOperation> ops) {
        final Expectation<List<OperationResult>> result = new Expectation<>();
        TransactionBuilder transaction = client.transactBuilder(dbs);
        for (Table.OvsdbOperation op: ops) {
            transaction.add(op.op);
        }
        FutureCallback<List<OperationResult>> cb =
            new FutureCallback<List<OperationResult>>() {
                @Override public void onSuccess(List<OperationResult> rlist) {
                    OvsdbOpException exc = null;
                    Integer idx = 0;
                    for (OperationResult r: rlist) {
                        if (opResultHasError(r)) {
                            exc = new OvsdbOpException(client, ops, idx, r);
                            break;
                        }
                        idx++;
                    }
                    if (exc != null)
                        result.failure(exc);
                    else
                        result.success(rlist);

                }
                @Override public void onFailure(@Nonnull Throwable exc) {
                    result.failure(new OvsdbException(client, exc));
                }
            };
        Futures.addCallback(transaction.execute(), cb, callingThreadExecutor);
        return result;
    }

    /**
     * Retrieve the database schema from the ovsdb-based vtep asynchronously
     */
    static public Expectation<DatabaseSchema> getDbSchema(
        final OvsdbClient client, final String dbName) {
        final Expectation<DatabaseSchema> result = new Expectation<>();

        final FutureCallback<DatabaseSchema> dbsCb =
            new FutureCallback<DatabaseSchema>() {
            @Override
            public void onSuccess(DatabaseSchema dbs) {
                result.success(dbs);
            }
            @Override
            public void onFailure(@Nonnull Throwable exc) {
                result.failure(new OvsdbException(client, exc));
            }
        };

        FutureCallback<List<String>> dbListCb =
            new FutureCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> dbList) {
                if (!dbList.contains(dbName)) {
                    result.failure(new OvsdbNotFoundException(
                        client, "db not found: " + dbName));
                } else {
                    Futures.addCallback(client.getSchema(dbName), dbsCb,
                                        callingThreadExecutor);
                }
            }
            @Override
            public void onFailure(@Nonnull Throwable exc) {
                result.failure(new OvsdbException(client, exc));
            }
        };

        Futures.addCallback(client.getDatabases(), dbListCb,
                            callingThreadExecutor);
        return result;
    }

    /**
     * Create an observable to monitor table updates
     */
    static public Observable<TableUpdate<GenericTableSchema>> tableUpdates(
        final OvsdbClient client,
        final DatabaseSchema db,
        final GenericTableSchema table,
        final Collection<ColumnSchema<GenericTableSchema, ?>> columns) {

        // Prepare request
        MonitorRequestBuilder<GenericTableSchema> builder =
            MonitorRequestBuilder.builder(table);
        List<ColumnSchema<GenericTableSchema, ?>> cList = new ArrayList<>();
        cList.addAll(columns);
        builder.addColumns(cList);
        final List<MonitorRequest<GenericTableSchema>> req = new ArrayList<>();
        req.add(builder.build());

        // Start monitoring on subscribe
        Observable.OnSubscribe<TableUpdate<GenericTableSchema>> onSubscribe =
            new Observable.OnSubscribe<TableUpdate<GenericTableSchema>>() {
                @Override
                public void call(
                    final Subscriber<? super TableUpdate<GenericTableSchema>>
                        subscriber) {
                    MonitorCallBack cb = new MonitorCallBack() {
                        @Override
                        public void update(TableUpdates updates,
                                           DatabaseSchema dbSchema) {
                            TableUpdate<GenericTableSchema> update =
                                updates.getUpdate(table);
                            if (update != null)
                                subscriber.onNext(update);
                        }
                        @Override
                        public void exception(Throwable throwable) {
                            subscriber.onError(
                                new OvsdbException(client, throwable));

                        }
                    };
                    client.monitor(db, req, cb);
                }
            };

        return Observable.create(onSubscribe);
    }

    /**
     * A helper class to implement a common failure handling for
     * ovsdb operations
     */
    private static abstract class ResultWrapper<T>
        implements Expectation.OnComplete<OperationResult> {
        private final Expectation<T> result;
        public ResultWrapper(Expectation<T> result) {
            this.result = result;
        }
        @Override
        public void onFailure(Throwable exc) {
            result.failure(exc);
        }
    }

    /**
     * Retrieve the current entries from a table
     */
    static public Expectation<Collection<Row<GenericTableSchema>>> tableEntries(
        OvsdbClient client,
        DatabaseSchema db,
        GenericTableSchema table,
        Collection<ColumnSchema<GenericTableSchema, ?>> columns,
        Condition cond) {

        final Expectation<Collection<Row<GenericTableSchema>>> result =
            new Expectation<>();
        Select<GenericTableSchema> op = new Select<>(table);
        for (ColumnSchema<GenericTableSchema, ?> col: columns) {
            op.column(col);
        }
        if (cond != null)
            op.addCondition(cond);

        Expectation<OperationResult> opResult =
            singleOp(client, db, new Table.OvsdbSelect(op));
        opResult.onComplete(
            new ResultWrapper<Collection<Row<GenericTableSchema>>>(result) {
            @Override public void onSuccess(OperationResult r) {
                result.success(r.getRows());
            }
        }, callingThreadContext);

        return result;
    }

    /**
     * Insert into database
     */
    static public Expectation<java.util.UUID> insert(final OvsdbClient client,
                                                     final Table table,
                                                     Table.OvsdbInsert op) {
        final Expectation<java.util.UUID> result = new Expectation<>();
        Expectation<OperationResult> opResult =
            singleOp(client, table.getDbSchema(), op);
        opResult.onComplete(new ResultWrapper<java.util.UUID>(result) {
            @Override
            public void onSuccess(OperationResult r) {
                if (r.getCount() > 0) {
                    Row<GenericTableSchema> row = r.getRows().get(0);
                    result.success(table.parseUuid(row));
                } else {
                    result.success(null);
                }
            }
        }, callingThreadContext);
        return result;
    }

    /**
     * Delete from database
     */
    static public Expectation<Integer> delete(final OvsdbClient client,
                                              Table table,
                                              Table.OvsdbDelete op) {
        final Expectation<Integer> result = new Expectation<>();
        Expectation<OperationResult> opResult =
            singleOp(client, table.getDbSchema(), op);
        opResult.onComplete(new ResultWrapper<Integer>(result) {
            @Override
            public void onSuccess(OperationResult r) {
                result.success(r.getCount());
            }
        }, callingThreadContext);
        return result;
    }
}
