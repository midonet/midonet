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

package org.midonet.southbound.vtep.mock;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;

import org.opendaylight.ovsdb.lib.EchoServiceCallbackFilters;
import org.opendaylight.ovsdb.lib.LockAquisitionCallback;
import org.opendaylight.ovsdb.lib.LockStolenCallback;
import org.opendaylight.ovsdb.lib.MonitorCallBack;
import org.opendaylight.ovsdb.lib.MonitorHandle;
import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.OvsdbConnectionInfo;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.message.TransactBuilder;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.Operation;
import org.opendaylight.ovsdb.lib.operations.OperationResult;
import org.opendaylight.ovsdb.lib.operations.Select;
import org.opendaylight.ovsdb.lib.operations.TransactionBuilder;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;
import org.opendaylight.ovsdb.lib.schema.TableSchema;
import org.opendaylight.ovsdb.lib.schema.typed.TypedBaseTable;
import org.opendaylight.ovsdb.lib.schema.typed.TyperUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.packets.IPv4Addr;

/**
 * A Mock ovsdb client handle
 */
public class MockOvsdbClient implements OvsdbClient {

    private final Logger log = LoggerFactory.getLogger(MockOvsdbClient.class);

    private final DatabaseSchema vtepSchema;
    private final MonitorRegistration monitorRegistrar;
    private final TransactionEngine engine;
    private final OvsdbConnectionInfo info;

    static private final String DEFAULT_IP = "127.0.0.1";
    static private final int DEFAULT_PORT = 6632;

    public interface MonitorRegistration {
        void register(String table, Set<String> columns, MonitorCallBack cb);
    }

    public interface TransactionEngine {
        ListenableFuture<List<OperationResult>> execute(TransactBuilder trans);
    }

    // For some obscure reason, MockTransactionBuilder is constructed from
    // OvsdbClientImpl, instead of an object implementing the OvsdbClient
    // interface... so we need to extend the TransactionBuilder and replace
    // the only method that actually uses the OvsdbClient(Impl) interface.
    private class MockTransactionBuilder extends TransactionBuilder {
        private final OvsdbClient client;
        private final DatabaseSchema dbSchema;
        public MockTransactionBuilder(OvsdbClient client,
                                      DatabaseSchema dbSchema) {
            super(null, dbSchema);
            this.client = client;
            this.dbSchema = dbSchema;
        }
        @Override
        public ListenableFuture<List<OperationResult>> execute() {
            return client.transact(dbSchema, getOperations());
        }
    }

    // Mock connection info
    private class MockConnectionInfo extends OvsdbConnectionInfo {
        private final InetAddress remoteAddr;
        private final int remotePort;
        private final InetAddress localAddr;
        private final int localPort;
        public MockConnectionInfo(IPv4Addr addr, int port)
            throws UnknownHostException {
            super(null, ConnectionType.ACTIVE);
            this.remoteAddr = InetAddress.getByAddress(addr.toBytes());
            this.remotePort = port;
            this.localAddr = InetAddress.getLocalHost();
            this.localPort = new Random().nextInt(1000)+51000;
        }

        @Override
        public InetAddress getRemoteAddress() {
            return remoteAddr;
        }
        @Override
        public int getRemotePort() {
            return remotePort;
        }
        @Override
        public InetAddress getLocalAddress() {
            return localAddr;
        }
        @Override
        public int getLocalPort() {
            return localPort;
        }
    }

    static public class NotImplemented extends UnsupportedOperationException {
        public NotImplemented() {
            super("not yet implemented");
        }
    }

    public MockOvsdbClient(DatabaseSchema vtepSchema,
                           MonitorRegistration monitorRegistrar,
                           TransactionEngine engine,
                           IPv4Addr ip, int port) throws UnknownHostException {
        this.vtepSchema = vtepSchema;
        this.monitorRegistrar = monitorRegistrar;
        this.engine = engine;
        this.info = new MockConnectionInfo(ip, port);
    }

    public MockOvsdbClient(DatabaseSchema vtepSchema,
                           MonitorRegistration monitorRegistrar,
                           TransactionEngine engine)
        throws UnknownHostException {
        this(vtepSchema, monitorRegistrar, engine,
             IPv4Addr.fromString(DEFAULT_IP), DEFAULT_PORT);
    }

    private DatabaseSchema getDbSchema(String dbName) {
        return (vtepSchema.getName().equals(dbName))? vtepSchema: null;
    }

    // Schema operations
    @Override public ListenableFuture<List<String>> getDatabases() {
        return new MockListenableFuture<>(
            Collections.singletonList(vtepSchema.getName()));
    }
    @Override public ListenableFuture<DatabaseSchema> getSchema(String s) {
        return new MockListenableFuture<>(getDbSchema(s));
    }
    @Override public DatabaseSchema getDatabaseSchema(String s) {
        return getDbSchema(s);
    }

    // Connection
    @Override public void disconnect() {}
    @Override public boolean isActive() {
        return true;
    }
    @Override public OvsdbConnectionInfo getConnectionInfo() {
        return info;
    }

    // Monitor support
    @Override public <E extends TableSchema<E>> TableUpdates monitor(
        DatabaseSchema dbSchema, List<MonitorRequest<E>> requests,
        MonitorCallBack cb) {
        List<Operation> ops = new ArrayList<>();
        for (MonitorRequest<E> e: requests) {
            monitorRegistrar.register(e.getTableName(), e.getColumns(), cb);
            Select<GenericTableSchema> query =
                new Select<>(vtepSchema.table(e.getTableName(),
                                              GenericTableSchema.class));
            if (e.getColumns() != null)
                query.setColumns(Lists.newArrayList(e.getColumns()));
            ops.add(query);
        }
        ListenableFuture<List<OperationResult>> results =
            transact(vtepSchema, ops);

        try {
            return tableUpdates(results.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.error("exception on monitored table data", e);
            return null;
        }
    }
    @Override public void cancelMonitor(MonitorHandle monitorHandle) {
        throw new NotImplemented();
    }

    // Transactions
    @Override public TransactionBuilder transactBuilder(DatabaseSchema dbSchema) {
        return new MockTransactionBuilder(this, dbSchema);
    }
    @Override public ListenableFuture<List<OperationResult>> transact(
        DatabaseSchema dbSchema, List<Operation> operations) {
        TransactBuilder builder = new TransactBuilder(dbSchema);
        for (Operation o: operations) {
            builder.addOperation(o);
        }
        return engine.execute(builder);
    }

    // Typed row wrappers
    @Override public <T extends TypedBaseTable<?>> T getTypedRowWrapper(
        Class<T> clazz, Row<GenericTableSchema> row) {
        return TyperUtils.getTypedRowWrapper(vtepSchema, clazz, row);
    }
    @Override public <T extends TypedBaseTable<?>> T createTypedRowWrapper(
        Class<T> clazz) {
        return createTypedRowWrapper(vtepSchema, clazz);
    }
    @Override public <T extends TypedBaseTable<?>> T createTypedRowWrapper(
        DatabaseSchema dbSchema, Class<T> clazz) {
        return TyperUtils.getTypedRowWrapper(dbSchema, clazz, new Row<>());
    }

    // Vtep operation locking
    @Override public void lock(String s,
                               LockAquisitionCallback acquisitionCallback,
                               LockStolenCallback stolenCallback) {
        throw new NotImplemented();
    }
    @Override public ListenableFuture<Boolean> unLock(String s) {
        throw new NotImplemented();
    }
    @Override public ListenableFuture<Boolean> steal(String s) {
        throw new NotImplemented();
    }

    // Echo service
    @Override public void startEchoService(
        EchoServiceCallbackFilters callbackFilters) {
        throw new NotImplemented();
    }
    @Override public void stopEchoService() {
        throw new NotImplemented();
    }

    @SuppressWarnings(value = "unchecked")
    public TableUpdates tableUpdates(List<OperationResult> results) {
        Map<String, TableUpdate> map = new HashMap<>();
        for (OperationResult r: results) {
            for (Row<GenericTableSchema> row: r.getRows()) {
                String table = row.getTableSchema().getName();
                TableUpdate<GenericTableSchema> update;
                if (map.containsKey(table)) {
                    update = map.get(table);
                } else {
                    update = new TableUpdate<>();
                    map.put(table, update);
                }
                GenericTableSchema ts =
                    vtepSchema.table(table, GenericTableSchema.class);
                UUID rowId = row.getColumn(ts.column("_uuid", UUID.class)).getData();
                update.addRow(rowId, null, row);
            }
        }
        return new TableUpdates(map);
    }
}
