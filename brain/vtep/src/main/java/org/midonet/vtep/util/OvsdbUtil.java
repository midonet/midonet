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

package org.midonet.vtep.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;

import javax.annotation.Nonnull;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import org.opendaylight.ovsdb.lib.MonitorCallBack;
import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.OvsdbConnectionInfo;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.MonitorRequestBuilder;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.operations.Operation;
import org.opendaylight.ovsdb.lib.operations.OperationResult;
import org.opendaylight.ovsdb.lib.operations.TransactionBuilder;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import rx.Observer;

import org.midonet.packets.IPv4Addr;
import org.midonet.vtep.VtepEndPoint;

/**
 * Common utility Procedures related to ovsdb-based vtep management
 */
public class OvsdbUtil {

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

    /**
     * Retrieve a table schema from the containing database schema
     */
    static public GenericTableSchema getTblSchema(DatabaseSchema dbs, String tblName)
        throws NoSuchElementException {
        GenericTableSchema tblSchema =
            dbs.table(tblName, GenericTableSchema.class);
        if (tblSchema == null)
            throw new NoSuchElementException("cannot retrieve ovsdb table: "
                                             + tblName);
        return tblSchema;
    }

    /**
     * Perform a single operation in the ovs database and expect a result
     */
    static public Expectation<OperationResult> singleOp(OvsdbClient client,
                                                        DatabaseSchema dbs,
                                                        Operation op,
                                                        Executor executor) {
        final Expectation<OperationResult> result = new Expectation<>();
        TransactionBuilder transaction = client.transactBuilder(dbs);
        transaction.add(op);
        FutureCallback<List<OperationResult>> cb =
            new FutureCallback<List<OperationResult>>() {
                @Override
                public void onSuccess(List<OperationResult> rlist) {
                    try {
                        result.success(rlist.get(0));
                    } catch (Throwable err) {
                        result.failure(err);
                    }
                }

                @Override
                public void onFailure(@Nonnull Throwable exc) {
                    result.failure(exc);
                }
            };
        Futures.addCallback(transaction.execute(), cb, executor);
        return result;
    }

    /**
     * Retrieve the database schema from the ovsdb-based vtep asynchronously
     */
    static public Expectation<DatabaseSchema> getDbSchema(
        final OvsdbClient client, final String dbName, final Executor executor){
        final Expectation<DatabaseSchema> result = new Expectation<>();

        final FutureCallback<DatabaseSchema> dbsCb =
            new FutureCallback<DatabaseSchema>() {
            @Override
            public void onSuccess(DatabaseSchema dbs) {
                result.success(dbs);
            }
            @Override
            public void onFailure(@Nonnull Throwable exc) {
                result.failure(exc);
            }
        };

        FutureCallback<List<String>> dbListCb =
            new FutureCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> dbList) {
                if (!dbList.contains(dbName)) {
                    result.failure(
                        new NoSuchElementException("ovsdb db not found: " +
                                                   dbName));
                } else {
                    Futures.addCallback(client.getSchema(dbName), dbsCb,
                                        executor);
                }
            }
            @Override
            public void onFailure(@Nonnull Throwable exc) {
                result.failure(exc);
            }
        };

        Futures.addCallback(client.getDatabases(), dbListCb, executor);
        return result;
    }

    /**
     * Generate a table update monitor for a specific table that pushes
     * updates to an observer
     */
    static public MonitorCallBack newMonitor(final GenericTableSchema table,
                                             final Observer<TableUpdate> obs) {
        return new MonitorCallBack() {
            @Override
            public void update(TableUpdates tableUpdates,
                               DatabaseSchema databaseSchema) {
                obs.onNext(tableUpdates.getUpdate(table));
            }
            @Override
            public void exception(Throwable throwable) {
                obs.onError(throwable);
            }
        };
    }

    /**
     * Generate a new monitor request
     */
    static public MonitorRequest<GenericTableSchema> newMonitorRequest(
        GenericTableSchema table,
        Collection<ColumnSchema<GenericTableSchema, ?>> columns) {
        MonitorRequestBuilder<GenericTableSchema> builder =
            MonitorRequestBuilder.builder(table);
        List<ColumnSchema<GenericTableSchema, ?>> cList = new ArrayList<>();
        cList.addAll(columns);
        builder.addColumns(cList);
        return builder.build();
    }
}
