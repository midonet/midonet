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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nonnull;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

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
import org.midonet.util.concurrent.Expectation;
import org.midonet.util.concurrent.package$;
import org.midonet.vtep.schema.Table;


/**
 * Common utility Procedures related to ovsdb-based vtep management
 */
public class OvsdbTools {

    static public final String DB_HARDWARE_VTEP = "hardware_vtep";
    static public final ChannelFuture VOID_FUTURE = new ChannelFuture() {
        @Override
        public Channel channel() {
            return null;
        }

        @Override
        public ChannelFuture addListener(
            GenericFutureListener<? extends Future<? super Void>> listener) {
            return this;
        }

        @Override
        @SafeVarargs
        public final ChannelFuture addListeners(
            GenericFutureListener<? extends Future<? super Void>>... listeners) {
            return this;
        }

        @Override
        public ChannelFuture removeListener(
            GenericFutureListener<? extends Future<? super Void>> listener) {
            return this;
        }

        @Override
        @SafeVarargs
        public final ChannelFuture removeListeners(
            GenericFutureListener<? extends Future<? super Void>>... listeners) {
            return this;
        }

        @Override
        public ChannelFuture sync() throws InterruptedException {
            return this;
        }

        @Override
        public ChannelFuture syncUninterruptibly() {
            return this;
        }

        @Override
        public ChannelFuture await() throws InterruptedException {
            return this;
        }

        @Override
        public ChannelFuture awaitUninterruptibly() {
            return this;
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public boolean isCancellable() {
            return false;
        }

        @Override
        public Throwable cause() {
            return null;
        }

        @Override
        public boolean await(long timeout, TimeUnit unit)
            throws InterruptedException {
            return false;
        }

        @Override
        public boolean await(long timeoutMillis) throws InterruptedException {
            return false;
        }

        @Override
        public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public boolean awaitUninterruptibly(long timeoutMillis) {
            return false;
        }

        @Override
        public Void getNow() {
            return null;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Void get(long timeout, @Nonnull TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    };

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
        final OvsdbClient client, DatabaseSchema dbs, Table.OvsdbOperation op,
        Executor executor) {
        final Expectation<OperationResult> result = new Expectation<>();
        multiOp(client, dbs, Collections.singletonList(op), executor).onComplete(
            new Expectation.OnComplete<List<OperationResult>>() {
                @Override
                public void onSuccess(List<OperationResult> rlist) {
                    result.success(rlist.get(0));
                }

                @Override
                public void onFailure(@Nonnull Throwable exc) {
                    result.failure(exc);
                }
            }, package$.MODULE$.toExecutionContext(executor));
        return result;
    }

    /**
     * Perform multiple operations inside an ovs database transaction
     */
    static public Expectation<List<OperationResult>> multiOp(
        final OvsdbClient client, DatabaseSchema dbs,
        final List<Table.OvsdbOperation> ops,
        Executor executor) {
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
        Futures.addCallback(transaction.execute(), cb, executor);
        return result;
    }

    /**
     * Retrieves the database schema from the OVSDB-based VTEP asynchronously.
     */
    static public Expectation<DatabaseSchema> getDbSchema(
        OvsdbClient client, String dbName, Executor executor) {
        return getDbSchema(client, dbName, executor, VOID_FUTURE, null);
    }

    /**
     * Retrieves the database schema from the OVSDB-based VTEP asynchronously.
     * The method receives a channel future, which, when completed will
     * complete the returned expectation a failure.
     */
    static public Expectation<DatabaseSchema> getDbSchema(
        final OvsdbClient client, final String dbName,
        final Executor executor,
        final ChannelFuture closeFuture,
        final Exception closeException) {
        final Expectation<DatabaseSchema> result = new Expectation<>();

        final ChannelFutureListener channelListener = new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future)
                throws Exception {
                future.removeListener(this);
                result.tryFailure(closeException);
            }
        };
        closeFuture.addListener(channelListener);

        final FutureCallback<DatabaseSchema> dbsCb =
            new FutureCallback<DatabaseSchema>() {
            @Override
            public void onSuccess(DatabaseSchema dbs) {
                closeFuture.removeListener(channelListener);
                result.trySuccess(dbs);
            }
            @Override
            public void onFailure(@Nonnull Throwable exc) {
                closeFuture.removeListener(channelListener);
                result.tryFailure(new OvsdbException(client, exc));
            }
        };

        FutureCallback<List<String>> dbListCb =
            new FutureCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> dbList) {
                if (!dbList.contains(dbName)) {
                    closeFuture.removeListener(channelListener);
                    result.tryFailure(new OvsdbNotFoundException(
                        client, "db not found: " + dbName));
                } else {
                    Futures.addCallback(client.getSchema(dbName), dbsCb,
                                        executor);
                }
            }
            @Override
            public void onFailure(@Nonnull Throwable exc) {
                closeFuture.removeListener(channelListener);
                result.tryFailure(new OvsdbException(client, exc));
            }
        };

        Futures.addCallback(client.getDatabases(), dbListCb, executor);
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
        Condition cond,
        Executor executor) {

        final Expectation<Collection<Row<GenericTableSchema>>> result =
            new Expectation<>();
        Select<GenericTableSchema> op = new Select<>(table);
        for (ColumnSchema<GenericTableSchema, ?> col : columns) {
            op.column(col);
        }
        if (cond != null)
            op.addCondition(cond);

        Expectation<OperationResult> opResult =
            singleOp(client, db, new Table.OvsdbSelect(op), executor);
        opResult.onComplete(
            new ResultWrapper<Collection<Row<GenericTableSchema>>>(result) {
            @Override public void onSuccess(OperationResult r) {
                result.success(r.getRows());
            }
        }, package$.MODULE$.toExecutionContext(executor));

        return result;
    }

    /**
     * Insert into database
     */
    static public Expectation<java.util.UUID> insert(final OvsdbClient client,
                                                     final Table table,
                                                     Table.OvsdbInsert op,
                                                     Executor executor) {
        final Expectation<java.util.UUID> result = new Expectation<>();
        Expectation<OperationResult> opResult =
            singleOp(client, table.getDbSchema(), op, executor);
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
        }, package$.MODULE$.toExecutionContext(executor));
        return result;
    }

    /**
     * Delete from database
     */
    static public Expectation<Integer> delete(final OvsdbClient client,
                                              Table table,
                                              Table.OvsdbDelete op,
                                              Executor executor) {
        final Expectation<Integer> result = new Expectation<>();
        Expectation<OperationResult> opResult =
            singleOp(client, table.getDbSchema(), op, executor);
        opResult.onComplete(new ResultWrapper<Integer>(result) {
            @Override
            public void onSuccess(OperationResult r) {
                result.success(r.getCount());
            }
        }, package$.MODULE$.toExecutionContext(executor));
        return result;
    }
}
