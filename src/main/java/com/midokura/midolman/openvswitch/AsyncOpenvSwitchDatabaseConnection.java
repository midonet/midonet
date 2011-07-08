/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

import com.midokura.midolman.eventloop.SelectListener;
import com.sun.tools.doclets.internal.toolkit.MethodWriter;
import org.async.json.JSONArray;
import org.async.json.JSONObject;
import org.async.json.in.JSONParser;
import org.async.json.in.JSONReader;
import org.async.json.in.RootParser;
import org.async.json.out.JSONWriter;
import sun.reflect.generics.reflectiveObjects.NotImplementedException; // TODO: remove this import

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritableByteChannel;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * An asynchronous implementation of a connection to an Open vSwitch database.
 */
public class AsyncOpenvSwitchDatabaseConnection
        implements OpenvSwitchDatabaseConnection, SelectListener {

    /**
     * The port type for system ports.
     */
    private static final String INTERFACE_TYPE_SYSTEM = "system";

    /**
     * The port type for internal ports.
     */
    private static final String INTERFACE_TYPE_INTERNAL = "internal";

    /**
     * The port type for TAP ports.
     */
    private static final String INTERFACE_TYPE_TAP = "tap";

    /**
     * The port type for GRE tunnel ports.
     */
    private static final String INTERFACE_TYPE_GRE = "gre";

    /**
     * The name of the table defining interfaces.
     */
    private static final String TABLE_INTERFACE = "Interface";

    /**
     * The name of the table defining ports.
     */
    private static final String TABLE_PORT = "Port";

    /**
     * The name of the table defining bridges.
     */
    private static final String TABLE_BRIDGE = "Bridge";

    /**
     * The name of the table defining controllers.
     */
    private static final String TABLE_CONTROLLER = "Controller";

    /**
     * The name of the top-level table.
     */
    private static final String TABLE_OPEN_VSWITCH = "Open_vSwitch";

    /**
     * The name of the database on which transactions are executed.
     */
    private String database;

    /**
     * The buffer used to read bytes.
     */
    private ByteBuffer readBuffer;

    /**
     * The channel used to write bytes.
     */
    private WritableByteChannel writeChannel;

    /**
     * The buffer used to write bytes.
     */
    private ByteBuffer writeBuffer;

    /**
     * A JSON bytes reader reading bytes from readBuffer.
     */
    private JSONReader jsonReader;

    /**
     * The JSON data parser.
     */
    private JSONParser jsonParser;

    /**
     * The JSON data writer.
     */
    private JSONWriter jsonWriter;

    /**
     * The next request ID to be used.
     */
    private long nextRequestId = 0;

    /**
     * The state of pending JSON-RPC 1.0 requests. Maps request IDs to result
     * placeholders. All accesses to this map must be synchronized on the map
     * itself.
     */
    private Map<Long, BlockingQueue<JSONObject>> pendingJsonRpcRequests = new
            HashMap<Long, BlockingQueue<JSONObject>>();

    // TODO: Javadoc.
    public AsyncOpenvSwitchDatabaseConnection(
            String database, ByteBuffer readBuffer,
            WritableByteChannel writeChannel, ByteBuffer writeBuffer) {
        this.database = database;
        this.readBuffer = readBuffer;
        this.writeChannel = writeChannel;
        this.writeBuffer = writeBuffer;
        jsonReader = new JSONReader(new InputStreamReader(
                new ByteBufferInputStream(readBuffer)));
        jsonParser = new JSONParser(new RootParser());
        jsonWriter = new JSONWriter(new OutputStreamWriter(
                new ByteBufferOutputStream(writeBuffer)));
    }

    /**
     * Generates a new UUID to identify a newly added row.
     * @return a new UUID
     */
    public static String generateUuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Transforms a newly inserted row's temporary UUID into a "UUID name", to
     * reference the inserted row in the same transaction.
     * @param uuid the UUID to convert
     * @return the converted UUID
     */
    public static String getUuidNameFromUuid(String uuid) {
        return "row" + uuid.replace('-', '_');
    }

    /**
     * Converts a row UUID into an Open vSwitch UUID reference to an existing
     * row.
     * @param uuid the UUID to convert
     * @return the Open vSwitch DB row UUID reference
     */
    public static List<String> getOvsUuid(String uuid) {
        List<String> ovsUuid = new ArrayList<String>(2);
        ovsUuid.add("uuid");
        ovsUuid.add(uuid);
        return ovsUuid;
    }

    /**
     * Converts a row UUID into an Open vSwitch UUID reference to a row
     * inserted in the same transaction.
     * @param uuid the UUID to convert
     * @return the Open vSwitch DB row UUID reference
     */
    public static List<String> getNewRowOvsUuid(String uuid) {
        List<String> ovsUuid = new ArrayList<String>(2);
        ovsUuid.add("named-uuid");
        ovsUuid.add(getUuidNameFromUuid(uuid));
        return ovsUuid;
    }

    /**
     * Converts a Map into an Open vSwitch DB map.
     * @param map the Map to convert
     * @return the Open vSwitch DB map corresponding to the map
     */
    public static Collection getOvsMap(Map<?, ?> map) {
        List<List<?>> ovsMap = new ArrayList<List<?>>(map.size());
        for (Map.Entry mapEntry : map.entrySet()) {
            List<Object> ovsMapEntry = new ArrayList<Object>(2);
            ovsMapEntry.add(mapEntry.getKey());
            ovsMapEntry.add(mapEntry.getValue());
            ovsMap.add(ovsMapEntry);
        }
        return ovsMap;
    }

    @Override
    public void handleEvent(SelectionKey key) throws IOException {
        if (key.isValid() && (key.readyOps() & SelectionKey.OP_READ) != 0) {
            ReadableByteChannel channel = (ReadableByteChannel)key.channel();
            readBuffer.clear();
            channel.read(readBuffer);
            readBuffer.flip();

            JSONObject json = jsonParser.parse(jsonReader);

            if (json.get("result") != null) {
                long requestId = json.getLong("id");
                BlockingQueue<JSONObject> queue = null;
                synchronized (pendingJsonRpcRequests) {
                    queue = pendingJsonRpcRequests.get(requestId);
                    if (queue != null) {
                        // Pass the JSON object to the caller, and notify it.
                        queue.add(json);
                    }
                }
            }
        }
    }

    // TODO: Javadoc.
    private synchronized Object doJsonRpc(Transaction tx) {
        long requestId = nextRequestId++;
        Map<String, ?> request = tx.createJsonRpcRequest(requestId);

        BlockingQueue<JSONObject> queue =
                new ArrayBlockingQueue<JSONObject>(1);
        synchronized (pendingJsonRpcRequests) {
            // TODO: Check that no queue is already registered with that
            // requestId.
            pendingJsonRpcRequests.put(requestId, queue);
        }

        try {
            // Serialize the JSON-RPC 1.0 request into JSON text in the output
            // buffer, and write the buffer into the output channel.
            writeBuffer.clear();
            try {
                jsonWriter.writeObject(null, request);
                jsonWriter.flush();
                writeBuffer.flip();
                writeChannel.write(writeBuffer);
            } catch(IOException e) {
                throw new RuntimeException(e);
            }

            // Block until the response is received and parsed.
            // TODO: Set a timeout for the response, using poll() instead of
            // take().
            JSONObject response = null;
            try {
                response = queue.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            Object error = response.get("error");
            if (error != null) {
                throw new RuntimeException("OVSDB request error: " + error.toString());
            }
            return response.get("result");
        } finally {
            synchronized (pendingJsonRpcRequests) {
                pendingJsonRpcRequests.remove(requestId);
            }
        }
    }

    /**
     * A transaction to be performed by an Open vSwitch database server.
     */
    private class Transaction {

        /**
         * The name of the mutated database.
         */
        private String database;

        /**
         * If true, the transaction's changes are unconditionally aborted at
         * the end of the transaction.
         */
        private boolean dryRun;

        /**
         * Comments to be added into the logs when the transaction is
         * successfully committed. Comments are separated by newlines.
         */
        private String comments;

        /**
         * Row selections to be performed in the transaction.
         */
        private List<Map<String, Object>> rowSelections;

        /**
         * Row deletions to be performed in the transaction.
         */
        private List<Map<String, Object>> rowDeletions;

        /**
         * Row insertions to be performed in the transaction.
         */
        private List<Map<String, Object>> rowInsertions;

        /**
         * Row updates to be performed in the transaction.
         */
        private List<Map<String, Object>> rowUpdates;

        /**
         * Row mutations to be performed in the transaction.
         */
        private List<Map<String, Object>> rowMutations;

        /**
         * Create a new transaction.
         * @param database the name of the mutated database
         */
        public Transaction(String database) {
            this.database = database;
        }

        /**
         * The transaction to abort all changes, or not.
         * @param dryRun f true, the transaction's changes are unconditionally
         * aborted at the end of the transaction
         */
        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        /**
         * Add a comment to be added into the logs when the transaction is
         * successfully committed.
         * @param comment the comment to log
         */
        public void addComment(String comment) {
            if (comments == null) {
                comments = comment;
            } else {
                comments = comments + '\n' + comment;
            }
        }

        // TODO: Javadoc
        public Map<String, Object> createJsonRpcRequest(Object requestId) {
            // TODO: First check that no mutation refers to a row that is
            // inserted in this transaction using the ["uuid", "1234-abcd-..."]
            // form, instead of the required ['named-uuid', "row1234_abcd_..."]
            // form.

            List<Object> params = new LinkedList<Object>();
            params.add(database);

            // Add row selections.
            if (rowSelections != null) {
                params.addAll(rowSelections);
            }
            // Add row deletions.
            if (rowDeletions != null) {
                params.addAll(rowDeletions);
            }
            // Add row insertions.
            if (rowInsertions != null) {
                params.addAll(rowInsertions);
            }
            // Add row updates.
            if (rowUpdates != null) {
                params.addAll(rowUpdates);
            }
            // Add row mutations.
            if (rowMutations != null) {
                params.addAll(rowMutations);
            }

            // Add comments.
            if (comments != null) {
                Map<String, String> comment = new HashMap<String, String>();
                comment.put("op", "comment");
                comment.put("comment", comments);
                params.add(comment);
            }

            // Abort immediately in case this is a dry run.
            if (dryRun) {
                Map<String, String> abort = new HashMap<String, String>();
                abort.put("op", "abort");
                params.add(abort);
            }

            // Return a "transact" JSON-RPC 1.0 request to perform those
            // changes.
            Map<String, Object> transact = new HashMap<String, Object>();
            transact.put("method", "transact");
            transact.put("params", params);
            transact.put("id", requestId);

            return transact;
        }

        // TODO: Javadoc
        public void select(String table, List<?> where, List<String> columns) {
            if (rowSelections == null) {
                rowSelections = new LinkedList<Map<String, Object>>();
            }
            Map<String, Object> rowSelection = new HashMap<String, Object>();
            rowSelection.put("op", "select");
            rowSelection.put("table", table);
            rowSelection.put("where", where);
            rowSelection.put("columns", columns);
            rowSelections.add(rowSelection);
        }

        // TODO: Javadoc
        public void delete(String table, String rowUuid) {
            List<List<Object>> where = new ArrayList<List<Object>>(1);
            if (rowUuid != null) {
                List<Object> where1 = new ArrayList<Object>(3);
                where1.add("_uuid");
                where1.add("==");
                List<String> uuid = new ArrayList<String>(2);
                uuid.add("uuid");
                uuid.add(rowUuid);
                where1.add(uuid);
                where.add(where1);
            }
            if (rowDeletions == null) {
                rowDeletions = new LinkedList<Map<String, Object>>();
            }
            Map<String, Object> rowDeletion = new HashMap<String, Object>();
            rowDeletion.put("op", "delete");
            rowDeletion.put("table", table);
            rowDeletion.put("where", where);
            rowDeletions.add(rowDeletion);
        }

        // TODO: Javadoc
        public void insert(String table, String rowUuid,
                           Map<String, ?> row) {
            if (rowInsertions == null) {
                rowInsertions = new LinkedList<Map<String, Object>>();
            }
            Map<String, Object> rowInsertion = new HashMap<String, Object>();
            rowInsertion.put("op", "insert");
            rowInsertion.put("table", table);
            rowInsertion.put("uuid-name", getUuidNameFromUuid(rowUuid));
            rowInsertion.put("row", row);
            rowInsertions.add(rowInsertion);
        }

        // TODO: Javadoc
        public void update(String table, String rowUuid,
                           Map<String, ?> row) {
            // TODO: Factorize with delete().
            List<List<Object>> where = new ArrayList<List<Object>>(1);
            if (rowUuid != null) {
                List<Object> where1 = new ArrayList<Object>(3);
                where1.add("_uuid");
                where1.add("==");
                List<String> uuid = new ArrayList<String>(2);
                uuid.add("uuid");
                uuid.add(rowUuid);
                where1.add(uuid);
                where.add(where1);
            }
            if (rowUpdates == null) {
                rowUpdates = new LinkedList<Map<String, Object>>();
            }
            Map<String, Object> rowUpdate = new HashMap<String, Object>();
            rowUpdate.put("op", "update");
            rowUpdate.put("table", table);
            rowUpdate.put("where", where);
            rowUpdate.put("row", row);
            rowUpdates.add(rowUpdate);
        }

        // TODO: Javadoc
        public void increment(String table, String rowUuid,
                              List<String> columns) {
            // TODO: Factorize with delete().
            List<List<Object>> where = new ArrayList<List<Object>>(1);
            if (rowUuid != null) {
                List<Object> where1 = new ArrayList<Object>(3);
                where1.add("_uuid");
                where1.add("==");
                List<String> uuid = new ArrayList<String>(2);
                uuid.add("uuid");
                uuid.add(rowUuid);
                where1.add(uuid);
                where.add(where1);
            }
            List<List<Object>> mutations =
                    new ArrayList<List<Object>>(columns.size());
            for (String column : columns) {
                List<Object> mutation = new ArrayList<Object>(3);
                mutation.add(column);
                mutation.add("+=");
                mutation.add(1);
                mutations.add(mutation);
            }
            if (rowMutations == null) {
                rowMutations = new LinkedList<Map<String, Object>>();
            }
            Map<String, Object> rowMutation = new HashMap<String, Object>();
            rowMutation.put("op", "mutate");
            rowMutation.put("table", table);
            rowMutation.put("where", where);
            rowMutation.put("mutations", mutations);
            rowMutations.add(rowMutation);
        }

        // TODO: Javadoc
        public void increment(String table, String rowUuid,
                              String column) {
            List<String> columns = new ArrayList<String>(1);
            columns.add(column);
            increment(table, rowUuid, columns);
        }

        // TODO: Javadoc
        public void setInsert(String table, String rowUuid,
                              String column, Object value) {
            // TODO: Factorize with delete().
            List<List<Object>> where = new ArrayList<List<Object>>(1);
            if (rowUuid != null) {
                List<Object> where1 = new ArrayList<Object>(3);
                where1.add("_uuid");
                where1.add("==");
                List<String> uuid = new ArrayList<String>(2);
                uuid.add("uuid");
                uuid.add(rowUuid);
                where1.add(uuid);
                where.add(where1);
            }
            // TODO: Factorize with increment().
            List<List<Object>> mutations =new ArrayList<List<Object>>(1);
            List<Object> mutation = new ArrayList<Object>(3);
            mutation.add(column);
            mutation.add("insert");
            mutation.add(value);
            mutations.add(mutation);
            if (rowMutations == null) {
                rowMutations = new LinkedList<Map<String, Object>>();
            }
            Map<String, Object> rowMutation = new HashMap<String, Object>();
            rowMutation.put("op", "mutate");
            rowMutation.put("table", table);
            rowMutation.put("where", where);
            rowMutation.put("mutations", mutations);
            rowMutations.add(rowMutation);
        }

        // TODO: Javadoc
        public void setDelete(String table, String rowUuid,
                              String column, Object value) {
            // TODO: Factorize with delete().
            List<List<Object>> where = new ArrayList<List<Object>>(1);
            if (rowUuid != null) {
                List<Object> where1 = new ArrayList<Object>(3);
                where1.add("_uuid");
                where1.add("==");
                List<String> uuid = new ArrayList<String>(2);
                uuid.add("uuid");
                uuid.add(rowUuid);
                where1.add(uuid);
                where.add(where1);
            }
            // TODO: Factorize with setInsert().
            List<List<Object>> mutations =new ArrayList<List<Object>>(1);
            List<Object> mutation = new ArrayList<Object>(3);
            mutation.add(column);
            mutation.add("delete");  // TODO: Only diff line with setInsert().
            mutation.add(value);
            mutations.add(mutation);
            if (rowMutations == null) {
                rowMutations = new LinkedList<Map<String, Object>>();
            }
            Map<String, Object> rowMutation = new HashMap<String, Object>();
            rowMutation.put("op", "mutate");
            rowMutation.put("table", table);
            rowMutation.put("where", where);
            rowMutation.put("mutations", mutations);
            rowMutations.add(rowMutation);
        }

    }

    /**
     * A BridgeBuilder that uses an asynchronous OVSDB connection.
     */
    private class AsyncBridgeBuilder implements BridgeBuilder {

        /**
         * The name of the bridge.
         */
        private String name;

        /**
         * The attributes of the interface to be created with the same name as
         * the bridge.
         */
        private Map<String, String> ifRow = new HashMap<String, String>();

        /**
         * The attributes of the port to be created with the same name as the
         * bridge.
         */
        private Map<String, Object> portRow = new HashMap<String, Object>();

        /**
         * The attributes of the bridge.
         */
        private Map<String, Object> bridgeRow = new HashMap<String, Object>();

        /**
         * Arbitrary pairs of key-value strings associated with the bridge.
         */
        private Map<String, String> bridgeExternalIds;

        /**
         * Create a bridge builder.
         * @param name the name of the bridge to build
         */
        public AsyncBridgeBuilder(String name) {
            this.name = name;
            ifRow.put("name", name);
            portRow.put("name", name);
            bridgeRow.put("name", name);
            bridgeRow.put("datapath_type", ""); // TODO: allow setting "netdev"
        }

        @Override
        public BridgeBuilder externalId(String key, String value) {
            if (bridgeExternalIds == null) {
                bridgeExternalIds = new HashMap<String, String>();
            }
            bridgeExternalIds.put(key, value);
            return this;
        }

        @Override
        public BridgeBuilder failMode(BridgeFailMode failMode) {
            bridgeRow.put("fail_mode", failMode.getMode());
            return this;
        }

        @Override
        public void build() {
            Transaction tx = new Transaction(database);

            // Create an internal interface and a port with the same name as
            // the bridge.
            String ifUuid = generateUuid();
            tx.insert(TABLE_INTERFACE, ifUuid, ifRow);

            String portUuid = generateUuid();
            portRow.put("interfaces", getNewRowOvsUuid(ifUuid));
            tx.insert(TABLE_PORT, portUuid, portRow);

            String bridgeUuid = generateUuid();
            bridgeRow.put("ports", getNewRowOvsUuid(portUuid));
            if (bridgeExternalIds != null) {
                bridgeRow.put("external_ids", getOvsMap(bridgeExternalIds));
            }
            tx.insert(TABLE_BRIDGE, bridgeUuid, bridgeRow);

            tx.setInsert(TABLE_OPEN_VSWITCH, null, "bridges",
                    getNewRowOvsUuid(bridgeUuid));

            tx.increment(TABLE_OPEN_VSWITCH, null, "next_cfg");

            if (bridgeExternalIds != null) {
                String extIdsStr = null;
                for (Map.Entry<String, String> extId :
                        bridgeExternalIds.entrySet()) {
                    if (extIdsStr == null) {
                        extIdsStr = String.format("%s=%s", extId.getKey(),
                                extId.getValue());
                    } else {
                        extIdsStr = String.format("%s, %s=%s",
                                extIdsStr, extId.getKey(),
                                extId.getValue());
                    }
                }
                tx.addComment(String.format(
                        "added bridge %s with external ids %s",
                        name, extIdsStr));
            } else {
                tx.addComment(String.format("added bridge %s", name));
            }

            doJsonRpc(tx);
        }

    }

    // TODO: Javadoc.
    private JSONArray select(String table, List<?> where, List<String> columns) {
        Transaction tx = new Transaction(database);
        tx.select(table, where, columns);
        return (JSONArray)doJsonRpc(tx);
    }

    /**
     * Query the UUID of a bridge given its datapath ID or its name.
     * @param bridgeId the datapath identifier of the bridge; significant only
     * if bridgeName is null
     * @param bridgeName the name of the bridge; may be null, in which case the
     * bridgeId is used to identify the bridge
     * @return
     */
    private String getBridgeUuidForId(long bridgeId, String bridgeName) {

        List<List<String>> where = new ArrayList<List<String>>(1);
        List<String> where1 = new ArrayList<String>(3);
        if (bridgeName != null) {
            where1.add("name");
            where1.add("==");
            where1.add(bridgeName);
        } else {
            where1.add("datapath_id");
            where1.add("==");
            where1.add(String.format("%016x", bridgeId));
        }
        where.add(where1);
        List<String> columns = new ArrayList<String>(1);
        columns.add("_uuid");
        JSONArray bridgeRows = select(TABLE_BRIDGE, where, columns);
        if (bridgeRows.size() != 1) {
            throw new IllegalStateException("no single bridge with ID "
                    + (bridgeName == null ? Long.toString(bridgeId)
                    : bridgeName));
        }
        return bridgeRows.getObject(0).getArray("_uuid").getString(1);

    }

    /**
     * Add a port.
     * @param bridgeId the datapath identifier of the bridge to add the port
     * to; significant only if bridgeName is null
     * @param bridgeName the name of the bridge to add the port to; may be
     * null, in which case the bridgeId is used to identify the bridge
     * @param ifRow the interface's attributes
     * @param ifOptions the interface's options; may be null
     * @param portRow the port's attributes
     * @param portExternalIds arbitrary pairs of key-value strings associated
     * with the port
     */
    private void addPort(
            long bridgeId, String bridgeName, Map<String, Object> ifRow,
            Map<String, String> ifOptions, Map<String, Object> portRow,
            Map<String, String> portExternalIds) {

        String bridgeUuid = getBridgeUuidForId(bridgeId, bridgeName);

        Transaction tx = new Transaction(database);

        // TODO: do nothing if a port with that name already exists

        String ifUuid = generateUuid();
        if (ifOptions != null) {
            portRow.put("options", getOvsMap(ifOptions));
        }
        tx.insert(TABLE_INTERFACE, ifUuid, ifRow);

        String portUuid = generateUuid();
        portRow.put("interfaces", getNewRowOvsUuid(ifUuid));
        if (portExternalIds != null) {
            portRow.put("external_ids", getOvsMap(portExternalIds));
        }
        tx.insert(TABLE_PORT, portUuid, portRow);

        tx.setInsert(TABLE_BRIDGE, bridgeUuid, "ports",
                getNewRowOvsUuid(portUuid));

        tx.increment(TABLE_OPEN_VSWITCH, null, "next_cfg");

        if (portExternalIds != null) {
            // TODO: Factorize this code with BridgeBuilder.build().
            String extIdsStr = null;
            for (Map.Entry<String, String> extId :
                    portExternalIds.entrySet()) {
                if (extIdsStr == null) {
                    extIdsStr = String.format("%s=%s", extId.getKey(),
                            extId.getValue());
                } else {
                    extIdsStr = String.format("%s, %s=%s",
                            extIdsStr, extId.getKey(),
                            extId.getValue());
                }
            }
            if (bridgeName == null) {
                tx.addComment(String.format(
                        "added port %s to bridge %x with external ids %s",
                        portRow.get("name"), bridgeId, extIdsStr));
            } else {
                tx.addComment(String.format(
                        "added port %s to bridge %s with external ids %s",
                        portRow.get("name"), bridgeName, extIdsStr));
            }
        } else {
            if (bridgeName == null) {
                tx.addComment(String.format("added port %s to bridge %x",
                        portRow.get("name"), bridgeId));
            } else {
                tx.addComment(String.format("added port %s to bridge %s",
                        portRow.get("name"), bridgeName));
            }
        }

        doJsonRpc(tx);
    }

    /**
     * A PortBuilder that uses an asynchronous OVSDB connection.
     */
    private class AsyncPortBuilder implements PortBuilder {

        /**
         * The datapath identifier of the bridge to add the port to.
         * This is significant only if bridgeName is null.
         */
        private long bridgeId;

        /**
         * The name of the bridge to add the port to.
         * May be null, in which case the bridgeId is used to identify the
         * bridge.
         */
        private String bridgeName;

        /**
         * The interface's attributes.
         */
        private Map<String, Object> ifRow = new HashMap<String, Object>();

        /**
         * The port's attributes.
         */
        private Map<String, Object> portRow = new HashMap<String, Object>();

        /**
         * Arbitrary pairs of key-value strings associated with the port.
         */
        private Map<String, String> portExternalIds;

        /**
         * Create a port builder.
         * @param ifType the type of the port to create
         * @param bridgeId the datapath identifier of the bridge to add the
         * port to
         * @param portName the name of the port and of the interface to create
         */
        public AsyncPortBuilder(String ifType, long bridgeId,
                                String portName) {
            ifRow.put("type", ifType);
            this.bridgeId = bridgeId;
            ifRow.put("name", portName);
            portRow.put("name", portName);
        }

        /**
         * Create a port builder.
         * @param ifType the type of the port to create
         * @param bridgeName the name of the bridge to add the port to
         * @param portName the name of the port and of the interface to create
         */
        public AsyncPortBuilder(String ifType, String bridgeName,
                                String portName) {
            ifRow.put("type", ifType);
            this.bridgeName = bridgeName;
            ifRow.put("name", portName);
            portRow.put("name", portName);
        }

        @Override
        public PortBuilder externalId(String key, String value) {
            if (portExternalIds == null) {
                portExternalIds = new HashMap<String, String>();
            }
            portExternalIds.put(key, value);
            return this;
        }

        @Override
        public PortBuilder ifMac(String ifMac) {
            ifRow.put("mac", ifMac);
            portRow.put("mac", ifMac);
            return this;
        }

        @Override
        public void build() {
            addPort(bridgeId, bridgeName, ifRow, null, portRow,
                    portExternalIds);
        }

    }

    /**
     * A GrePortBuilder that uses an asynchronous OVSDB connection.
     */
    private class AsyncGrePortBuilder implements GrePortBuilder {

        /**
         * The datapath identifier of the bridge to add the port to.
         * This is significant only if bridgeName is null.
         */
        private long bridgeId;

        /**
         * The name of the bridge to add the port to.
         * May be null, in which case the bridgeId is used to identify the
         * bridge.
         */
        private String bridgeName;

        /**
         * The interface's attributes.
         */
        private Map<String, Object> ifRow = new HashMap<String, Object>();

        /**
         * The interface's options.
         */
        private Map<String, String> ifOptions =
                new HashMap<String, String>();

        /**
         * The port's attributes.
         */
        private Map<String, Object> portRow = new HashMap<String, Object>();

        /**
         * Arbitrary pairs of key-value strings associated with the port.
         */
        private Map<String, String> portExternalIds;

        /**
         * Create a GRE port builder.
         * @param bridgeId the datapath identifier of the bridge to add the
         * port to
         * @param portName the name of the port and of the interface to create
         * @param remoteIp the tunnel remote endpoint's IP address
         */
        public AsyncGrePortBuilder(long bridgeId, String portName,
                                   String remoteIp) {
            ifRow.put("type", INTERFACE_TYPE_GRE);
            this.bridgeId = bridgeId;
            ifRow.put("name", portName);
            portRow.put("name", portName);
            ifOptions.put("remote_ip", remoteIp);
        }

        /**
         * Create a GRE port builder.
         * @param bridgeName the name of the bridge to add the port to
         * @param portName the name of the port and of the interface to create
         * @param remoteIp the tunnel remote endpoint's IP address
         */
        public AsyncGrePortBuilder(String bridgeName, String portName,
                                   String remoteIp) {
            ifRow.put("type", INTERFACE_TYPE_GRE);
            this.bridgeName = bridgeName;
            ifRow.put("name", portName);
            portRow.put("name", portName);
            ifOptions.put("remote_ip", remoteIp);
        }

        @Override
        public GrePortBuilder externalId(String key, String value) {
            if (portExternalIds == null) {
                portExternalIds = new HashMap<String, String>();
            }
            portExternalIds.put(key, value);
            return this;
        }

        @Override
        public GrePortBuilder ifMac(String ifMac) {
            ifRow.put("mac", ifMac);
            portRow.put("mac", ifMac);
            return this;
        }

        @Override
        public GrePortBuilder localIp(String localIp) {
            ifOptions.put("local_ip", localIp);
            return this;
        }

        @Override
        public GrePortBuilder outKey(int outKey) {
            // TODO(romain): This is wrong, GRE keys are *unsigned* 32-bit
            // integers.
            ifOptions.put("out_key", Integer.toString(outKey));
            return this;
        }

        @Override
        public GrePortBuilder outKeyFlow() {
            ifOptions.put("out_key", "flow");
            return this;
        }

        @Override
        public GrePortBuilder inKey(int inKey) {
            // TODO(romain): This is wrong, GRE keys are *unsigned* 32-bit
            // integers.
            ifOptions.put("in_key", Integer.toString(inKey));
            return this;
        }

        @Override
        public GrePortBuilder inKeyFlow() {
            ifOptions.put("in_key", "flow");
            return this;
        }

        @Override
        public GrePortBuilder key(int key) {
            // TODO(romain): This is wrong, GRE keys are *unsigned* 32-bit
            // integers.
            ifOptions.put("key", Integer.toString(key));
            return this;
        }

        @Override
        public GrePortBuilder keyFlow() {
            ifOptions.put("key", "flow");
            return this;
        }

        @Override
        public GrePortBuilder tos(byte tos) {
            // TODO(romain): This is wrong, the TOS is an *unsigned* 8-bit
            // integer.
            ifOptions.put("tos", Byte.toString(tos));
            return this;
        }

        @Override
        public GrePortBuilder tosInherit() {
            ifOptions.put("tos", "inherit");
            return this;
        }

        @Override
        public GrePortBuilder ttl(byte ttl) {
            // TODO(romain): This is wrong, the TOS is an *unsigned* 8-bit
            // integer.
            ifOptions.put("ttl", Byte.toString(ttl));
            return this;
        }

        @Override
        public GrePortBuilder ttlInherit() {
            ifOptions.put("ttl", "inherit");
            return this;
        }

        @Override
        public GrePortBuilder enableCsum() {
            ifOptions.put("csum", "true");
            return this;
        }

        @Override
        public GrePortBuilder disablePmtud() {
            ifOptions.put("pmtud", "false");
            return this;
        }

        @Override
        public GrePortBuilder disableHeaderCache() {
            ifOptions.put("header_cache", "false");
            return this;
        }

        @Override
        public void build() {
            addPort(bridgeId, bridgeName, ifRow, ifOptions, portRow,
                    portExternalIds);
        }

    }

    /**
     * A ControllerBuilder that uses an asynchronous OVSDB connection.
     */
    private class AsyncControllerBuilder implements ControllerBuilder {

        /**
         * The datapath identifier of the bridge to add the controller to.
         */
        private long bridgeId;

        /**
         * The name of the bridge to add the controller to.
         */
        private String bridgeName;

        /**
         * The controller's attributes.
         */
        private Map<String, Object> ctrlRow = new HashMap<String, Object>();

        /**
         * Arbitrary pairs of key-value strings associated with the controller.
         */
        private Map<String, String> ctrlExternalIds;

        /**
         * Create a controller builder.
         * @param bridgeId the datapath identifier of the bridge to add the
         * controller to
         * @param target the target to connect to the OpenFlow controller
         */
        public AsyncControllerBuilder(long bridgeId, String target) {
            this.bridgeId = bridgeId;
            ctrlRow.put("target", target);
        }

        /**
         * Create a controller builder.
         * @param bridgeName the name of the bridge to add the controller to
         * @param target the target to connect to the OpenFlow controller
         */
        public AsyncControllerBuilder(String bridgeName, String target) {
            this.bridgeName = bridgeName;
            ctrlRow.put("target", target);
        }

        @Override
        public ControllerBuilder externalId(String key, String value) {
            if (ctrlExternalIds == null) {
                ctrlExternalIds = new HashMap<String, String>();
            }
            ctrlExternalIds.put(key, value);
            return this;
        }

        @Override
        public ControllerBuilder connectionMode(
                ControllerConnectionMode connectionMode) {
            ctrlRow.put("connection_mode", connectionMode.getMode());
            return this;
        }

        @Override
        public ControllerBuilder maxBackoff(int maxBackoff) {
            ctrlRow.put("max_backoff", Integer.toString(maxBackoff));
            return this;
        }

        @Override
        public ControllerBuilder inactivityProbe(int inactivityProbe) {
            ctrlRow.put("inactivity_probe", Integer.toString(inactivityProbe));
            return this;
        }

        @Override
        public ControllerBuilder controllerRateLimit(int controllerRateLimit) {
            ctrlRow.put("controller_rate_limit",
                    Integer.toString(controllerRateLimit));
            return this;
        }

        @Override
        public ControllerBuilder controllerBurstLimit(
                int controllerBurstLimit) {
            ctrlRow.put("controller_burst_limit",
                    Integer.toString(controllerBurstLimit));
            return this;
        }

        @Override
        public ControllerBuilder discoverAcceptRegex(
                String discoverAcceptRegex) {
            ctrlRow.put("discover_accept_regex", discoverAcceptRegex);
            return this;
        }

        @Override
        public ControllerBuilder discoverUpdateResolvConf(
                boolean discoverUpdateResolvConf) {
            ctrlRow.put("discover_update_resolv_conf",
                    discoverUpdateResolvConf ? "true" : "false");
            return this;
        }

        @Override
        public ControllerBuilder localIp(String localIp) {
            ctrlRow.put("local_ip", localIp);
            return this;
        }

        @Override
        public ControllerBuilder localNetmask(String localNetmask) {
            ctrlRow.put("local_netmask", localNetmask);
            return this;
        }

        @Override
        public ControllerBuilder localGateway(String localGateway) {
            ctrlRow.put("local_gateway", localGateway);
            return this;
        }

        @Override
        public void build() {

            String bridgeUuid = getBridgeUuidForId(bridgeId, bridgeName);

            Transaction tx = new Transaction(database);

            String ctrlUuid = generateUuid();
            if (ctrlExternalIds != null) {
                ctrlRow.put("external_ids", getOvsMap(ctrlExternalIds));
            }
            tx.insert(TABLE_CONTROLLER, ctrlUuid, ctrlRow);

            tx.setInsert(TABLE_BRIDGE, bridgeUuid, "controller",
                    getNewRowOvsUuid(ctrlUuid));

            tx.increment(TABLE_OPEN_VSWITCH, null, "next_cfg");

            doJsonRpc(tx);

        }

    }

    @Override
    public BridgeBuilder addBridge(String name) {
        return new AsyncBridgeBuilder(name);
    }

    @Override
    public PortBuilder addSystemPort(long bridgeId, String portName) {
        return new AsyncPortBuilder(INTERFACE_TYPE_SYSTEM, bridgeId, portName);
    }

    @Override
    public PortBuilder addSystemPort(String bridgeName, String portName) {
        return new AsyncPortBuilder(INTERFACE_TYPE_SYSTEM, bridgeName, portName);
    }

    @Override
    public PortBuilder addInternalPort(long bridgeId, String portName) {
        return new AsyncPortBuilder(INTERFACE_TYPE_INTERNAL, bridgeId, portName);
    }

    @Override
    public PortBuilder addInternalPort(String bridgeName, String portName) {
        return new AsyncPortBuilder(INTERFACE_TYPE_INTERNAL, bridgeName, portName);
    }

    @Override
    public PortBuilder addTapPort(long bridgeId, String portName) {
        return new AsyncPortBuilder(INTERFACE_TYPE_TAP, bridgeId, portName);
    }

    @Override
    public PortBuilder addTapPort(String bridgeName, String portName) {
        return new AsyncPortBuilder(INTERFACE_TYPE_TAP, bridgeName, portName);
    }

    @Override
    public GrePortBuilder addGrePort(long bridgeId, String portName,
                                     String remoteIp) {
        return new AsyncGrePortBuilder(bridgeId, portName, remoteIp);
    }

    @Override
    public GrePortBuilder addGrePort(String bridgeName, String portName,
                                     String remoteIp) {
        return new AsyncGrePortBuilder(bridgeName, portName, remoteIp);
    }

    @Override
    public void delPort(String portName) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public ControllerBuilder addBridgeOpenflowController(long bridgeId,
                                                         String target) {
        return new AsyncControllerBuilder(bridgeId, target);
    }

    @Override
    public ControllerBuilder addBridgeOpenflowController(String bridgeName,
                                                         String target) {
        return new AsyncControllerBuilder(bridgeName, target);
    }

    @Override
    public void delBridgeOpenflowControllers(long bridgeId) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public void delBridgeOpenflowControllers(String bridgeName) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public boolean hasBridge(long bridgeId) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public boolean hasBridge(String bridgeName) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public void delBridge(long bridgeId) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public void delBridge(String bridgeName) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public String getDatapathExternalId(long bridgeId, String externalIdKey) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public String getDatapathExternalId(String bridgeName,
                                        String externalIdKey) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public String getPortExternalId(String portName, String externalIdKey) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public String getPortExternalId(long bridgeId, short portNum,
                                    String externalIdKey) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public String getPortExternalId(String bridgeName, short portNum,
                                    String externalIdKey) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public QosBuilder addQos(String type) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public QosBuilder updateQos(String qosUuid, String type) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public void clearQosQueues(String qosUuid) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public void delQos(String qosUuid) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public void setPortQos(String portName, String qosUuid) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public void unsetPortQos(String portName) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public QueueBuilder addQueue() {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public QueueBuilder updateQueue(String queueUuid) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public void delQueue(String queueUuid) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public Set<String> getBridgeNamesByExternalId(String key, String value) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public Set<String> getPortNamesByExternalId(String key, String value) {
        throw new NotImplementedException(); // TODO
    }

    @Override
    public void close() {
        throw new NotImplementedException(); // TODO
    }

}
