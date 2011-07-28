/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An Synchronous implementation of a connection to an Open vSwitch database.
 */
public class SyncOpenvSwitchDatabaseConnection
        implements OpenvSwitchDatabaseConnection, Runnable {

    private static final Logger log = LoggerFactory.getLogger(SyncOpenvSwitchDatabaseConnection.class);
    
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

    private Socket socket;

    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);
    
    private JsonParser jsonParser;
    
    private JsonGenerator jsonGenerator;

    /**
     * The next request ID to be used.
     */
    private long nextRequestId = 0;

    /**
     * The state of pending JSON-RPC 1.0 requests. Maps request IDs to result
     * placeholders. All accesses to this map must be synchronized on the map
     * itself.
     */
    private Map<Long, BlockingQueue<JsonNode>> pendingJsonRpcRequests = new
            HashMap<Long, BlockingQueue<JsonNode>>();
    
    private Timer timer = new Timer();
    
    private boolean stop = false;

    // TODO: Javadoc.
    public SyncOpenvSwitchDatabaseConnection(
            String database,
            String addr, int port) throws IOException {
        this.database = database;
        this.socket = new Socket(addr, port);
        
        jsonParser = jsonFactory.createJsonParser(new InputStreamReader(
                socket.getInputStream()));
        
        jsonGenerator = jsonFactory.createJsonGenerator(new OutputStreamWriter(
                socket.getOutputStream()));
        
        Thread me = new Thread(this);
        me.setDaemon(true);
        me.start();
        
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                echo();
            }
        }, 1000);
    }
    
    public void stop() {
        this.stop = true;
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
    public static ArrayNode getOvsUuid(String uuid) {
        ArrayNode ovsUuid = objectMapper.createArrayNode();
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
    public static ArrayNode getNewRowOvsUuid(String uuid) {
        ArrayNode ovsUuid = objectMapper.createArrayNode();
        ovsUuid.add("named-uuid");
        ovsUuid.add(getUuidNameFromUuid(uuid));
        return ovsUuid;
    }

    @Override
    public void run() {
        while (!stop) {
            try {
                JsonNode json = jsonParser.readValueAsTree();
    
                if (json.get("result") != null) {
                    long requestId = json.get("id").getLongValue();
                    BlockingQueue<JsonNode> queue = null;
                    synchronized (pendingJsonRpcRequests) {
                        queue = pendingJsonRpcRequests.get(requestId);
                        if (queue != null) {
                            // Pass the JSON object to the caller, and notify it.
                            queue.add(json);
                        }
                    }
                }
                
                //TODO: handle "notification" type
            } catch (Exception e) {
                //TODO: close it
            }
        }
    }
    
    private synchronized void echo() {
        ObjectNode transact = objectMapper.createObjectNode();
        transact.put("method", "transact");
        transact.put("params", objectMapper.createArrayNode());
        transact.put("id", "echo");
        
        try {
            jsonGenerator.writeTree(transact);
            jsonGenerator.flush();

        } catch(IOException e) {
            //TODO: close it
        }
    }

    // TODO: Javadoc.
    private synchronized JsonNode doJsonRpc(Transaction tx) {
        long requestId = nextRequestId++;
        JsonNode request = tx.createJsonRpcRequest(requestId);

        BlockingQueue<JsonNode> queue =
                new ArrayBlockingQueue<JsonNode>(1);
        synchronized (pendingJsonRpcRequests) {
            // TODO: Check that no queue is already registered with that
            // requestId.
            pendingJsonRpcRequests.put(requestId, queue);
        }

        try {
            // Serialize the JSON-RPC 1.0 request into JSON text in the output
            // channel.

            try {
                jsonGenerator.writeTree(request);
                jsonGenerator.flush();

            } catch(IOException e) {
                //TODO: close it
            }

            // Block until the response is received and parsed.
            // TODO: Set a timeout for the response, using poll() instead of
            // take().
            JsonNode response = null;
            try {
                response = queue.take();
            } catch (InterruptedException e) {
                log.warn("doJsonRpc", e);
                
                throw new RuntimeException(e);
            }
            JsonNode errorValue = response.get("error");
            if (!errorValue.isNull()) {
                log.warn("doJsonRpc: error from server: ", errorValue);
                throw new RuntimeException("OVSDB request error: " + errorValue.toString());
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
        private List<JsonNode> rowSelections;

        /**
         * Row deletions to be performed in the transaction.
         */
        private List<JsonNode> rowDeletions;

        /**
         * Row insertions to be performed in the transaction.
         */
        private List<JsonNode> rowInsertions;

        /**
         * Row updates to be performed in the transaction.
         */
        private List<JsonNode> rowUpdates;

        /**
         * Row mutations to be performed in the transaction.
         */
        private List<JsonNode> rowMutations;

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
        public JsonNode createJsonRpcRequest(long requestId) {
            // TODO: First check that no mutation refers to a row that is
            // inserted in this transaction using the ["uuid", "1234-abcd-..."]
            // form, instead of the required ['named-uuid', "row1234_abcd_..."]
            // form.

            ArrayNode params = objectMapper.createArrayNode();
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
                ObjectNode comment = objectMapper.createObjectNode();
                comment.put("op", "comment");
                comment.put("comment", comments);
                params.add(comment);
            }

            // Abort immediately in case this is a dry run.
            if (dryRun) {
                ObjectNode abort = objectMapper.createObjectNode();
                abort.put("op", "abort");
                params.add(abort);
            }

            // Return a "transact" JSON-RPC 1.0 request to perform those
            // changes.
            ObjectNode transact = objectMapper.createObjectNode();
//            Map<String, Object> transact = new HashMap<String, Object>();
            transact.put("method", "transact");
            transact.put("params", params);
            transact.put("id", requestId);

            return transact;
        }

        // TODO: Javadoc
        public void select(String table, ArrayNode where, ArrayNode columns) {
            if (rowSelections == null) {
                rowSelections = new LinkedList<JsonNode>();
            }
            
            ObjectNode rowSelection = objectMapper.createObjectNode();
            rowSelection.put("op", "select");
            rowSelection.put("table", table);
            rowSelection.put("where", where);
            rowSelection.put("columns", columns);
            rowSelections.add(rowSelection);
        }

        // TODO: Javadoc
        public void delete(String table, String rowUuid) {
            ArrayNode where = objectMapper.createArrayNode();
            if (rowUuid != null) {
                ArrayNode where1 = objectMapper.createArrayNode();
                where1.add("_uuid");
                where1.add("==");
                ArrayNode uuid = objectMapper.createArrayNode();
                uuid.add("uuid");
                uuid.add(rowUuid);
                where1.add(uuid);
                where.add(where1);
            }
            if (rowDeletions == null) {
                rowDeletions = new LinkedList<JsonNode>();
            }
            ObjectNode rowDeletion = objectMapper.createObjectNode();
            rowDeletion.put("op", "delete");
            rowDeletion.put("table", table);
            rowDeletion.put("where", where);
            rowDeletions.add(rowDeletion);
        }
        
        private JsonNode makeWhereClauseByUuid(String rowUuid) {
            ArrayNode where = objectMapper.createArrayNode();
            if (rowUuid != null) {
                ArrayNode where1 = objectMapper.createArrayNode();
                where1.add("_uuid");
                where1.add("==");
                
                ArrayNode uuid = objectMapper.createArrayNode();
                uuid.add("uuid");
                uuid.add(rowUuid);
                where1.add(uuid);
                where.add(where1);
            }
            return where;
        }

        // TODO: Javadoc
        public void insert(String table, String rowUuid, ObjectNode row) {
            if (rowInsertions == null) {
                rowInsertions = new LinkedList<JsonNode>();
            }
            ObjectNode rowInsertion = objectMapper.createObjectNode();
            rowInsertion.put("op", "insert");
            rowInsertion.put("table", table);
            rowInsertion.put("uuid-name", getUuidNameFromUuid(rowUuid));
            rowInsertion.put("row", row);
            rowInsertions.add(rowInsertion);
        }

        // TODO: Javadoc
        public void update(String table, String rowUuid, ObjectNode row) {
            // TODO: Factorize with delete().
            ArrayNode where = objectMapper.createArrayNode();
            if (rowUuid != null) {
                ArrayNode where1 = objectMapper.createArrayNode();
                where1.add("_uuid");
                where1.add("==");
                ArrayNode uuid = objectMapper.createArrayNode();
                uuid.add("uuid");
                uuid.add(rowUuid);
                where1.add(uuid);
                where.add(where1);
            }
            if (rowUpdates == null) {
                rowUpdates = new LinkedList<JsonNode>();
            }
            ObjectNode rowUpdate = objectMapper.createObjectNode();
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
            ArrayNode where = objectMapper.createArrayNode();
            if (rowUuid != null) {
                ArrayNode where1 = objectMapper.createArrayNode();
                where1.add("_uuid");
                where1.add("==");
                ArrayNode uuid = objectMapper.createArrayNode();
                uuid.add("uuid");
                uuid.add(rowUuid);
                where1.add(uuid);
                where.add(where1);
            }
            ArrayNode mutations = objectMapper.createArrayNode();
            for (String column : columns) {
                ArrayNode mutation = objectMapper.createArrayNode();
                mutation.add(column);
                mutation.add("+=");
                mutation.add(1);
                mutations.add(mutation);
            }
            if (rowMutations == null) {
                rowMutations = new LinkedList<JsonNode>();
            }
            ObjectNode rowMutation = objectMapper.createObjectNode();
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
                              String column, JsonNode value) {
            // TODO: Factorize with delete().
            ArrayNode where = objectMapper.createArrayNode();
            if (rowUuid != null) {
                ArrayNode where1 = objectMapper.createArrayNode();
                where1.add("_uuid");
                where1.add("==");
                ArrayNode uuid = objectMapper.createArrayNode();
                uuid.add("uuid");
                uuid.add(rowUuid);
                where1.add(uuid);
                where.add(where1);
            }
            // TODO: Factorize with increment().
            ArrayNode mutations = objectMapper.createArrayNode();
            ArrayNode mutation = objectMapper.createArrayNode();
            mutation.add(column);
            mutation.add("insert");
            mutation.add(value);
            mutations.add(mutation);
            if (rowMutations == null) {
                rowMutations = new LinkedList<JsonNode>();
            }
            
            ObjectNode rowMutation = objectMapper.createObjectNode();
            rowMutation.put("op", "mutate");
            rowMutation.put("table", table);
            rowMutation.put("where", where);
            rowMutation.put("mutations", mutations);
            rowMutations.add(rowMutation);
        }

        // TODO: Javadoc
        public void setDelete(String table, String rowUuid,
                              String column, JsonNode value) {
            // TODO: Factorize with delete().
            JsonNode where = makeWhereClauseByUuid(rowUuid);
            
            // TODO: Factorize with setInsert().
            ArrayNode mutations = objectMapper.createArrayNode();
            ArrayNode mutation = objectMapper.createArrayNode();
            mutation.add(column);
            mutation.add("delete");  // TODO: Only diff line with setInsert().
            mutation.add(value);
            mutations.add(mutation);
            if (rowMutations == null) {
                rowMutations = new LinkedList<JsonNode>();
            }
            ObjectNode rowMutation = objectMapper.createObjectNode();
            rowMutation.put("op", "mutate");
            rowMutation.put("table", table);
            rowMutation.put("where", where);
            rowMutation.put("mutations", mutations);
            rowMutations.add(rowMutation);
        }

    }

    /**
     * A BridgeBuilder that uses an Synchronous OVSDB connection.
     */
    private class SyncBridgeBuilder implements BridgeBuilder {

        /**
         * The name of the bridge.
         */
        private String name;

        /**
         * The attributes of the interface to be created with the same name as
         * the bridge.
         */
        private ObjectNode ifRow = objectMapper.createObjectNode();

        /**
         * The attributes of the port to be created with the same name as the
         * bridge.
         */
        private ObjectNode portRow = objectMapper.createObjectNode();

        /**
         * The attributes of the bridge.
         */
        private ObjectNode bridgeRow = objectMapper.createObjectNode();

        /**
         * Arbitrary pairs of key-value strings associated with the bridge.
         */
        private ObjectNode bridgeExternalIds;

        /**
         * Create a bridge builder.
         * @param name the name of the bridge to build
         */
        public SyncBridgeBuilder(String name) {
            this.name = name;
            ifRow.put("name", name);
            portRow.put("name", name);
            bridgeRow.put("name", name);
            bridgeRow.put("datapath_type", ""); // TODO: allow setting "netdev"
        }

        @Override
        public BridgeBuilder externalId(String key, String value) {
            if (bridgeExternalIds == null) {
                bridgeExternalIds = objectMapper.createObjectNode();
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
                bridgeRow.put("external_ids", bridgeExternalIds);
            }
            tx.insert(TABLE_BRIDGE, bridgeUuid, bridgeRow);

            tx.setInsert(TABLE_OPEN_VSWITCH, null, "bridges",
                    getNewRowOvsUuid(bridgeUuid));

            tx.increment(TABLE_OPEN_VSWITCH, null, "next_cfg");

//            if (bridgeExternalIds != null) {
//                String extIdsStr = null;
//                for (Map.Entry<String, String> extId :
//                        bridgeExternalIds.get) {
//                    if (extIdsStr == null) {
//                        extIdsStr = String.format("%s=%s", extId.getKey(),
//                                extId.getValue());
//                    } else {
//                        extIdsStr = String.format("%s, %s=%s",
//                                extIdsStr, extId.getKey(),
//                                extId.getValue());
//                    }
//                }
//                tx.addComment(String.format(
//                        "added bridge %s with external ids %s",
//                        name, extIdsStr));
//            } else {
//                tx.addComment(String.format("added bridge %s", name));
//            }

            doJsonRpc(tx);
        }

    }

    // TODO: Javadoc.
    private ArrayNode select(String table, ArrayNode where, ArrayNode columns) {
        Transaction tx = new Transaction(database);
        tx.select(table, where, columns);
        return (ArrayNode) doJsonRpc(tx).get(0).get("rows");
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

        ArrayNode where = objectMapper.createArrayNode();
        ArrayNode where1 = objectMapper.createArrayNode();
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
        
        ArrayNode columns = objectMapper.createArrayNode();
        columns.add("_uuid");
        
        ArrayNode bridgeRows = select(TABLE_BRIDGE, where, columns);
        if (bridgeRows.size() != 1) {
            throw new IllegalStateException("no single bridge with ID "
                    + (bridgeName == null ? Long.toString(bridgeId)
                    : bridgeName));
        }
        return bridgeRows.get(0).findValue("_uuid").get(2).getTextValue();
    }
    
    public List<String> getBridges() {

        ArrayNode where = objectMapper.createArrayNode();
        
        ArrayNode columns = objectMapper.createArrayNode();
        columns.add("_uuid");
        columns.add("name");
        
        ArrayNode bridgeRows = select(TABLE_BRIDGE, where, columns);

        List<String> brs = new ArrayList<String>();
        
        for (JsonNode row : bridgeRows) {
            brs.add(row.get("name").getTextValue());
        }
        
        return brs;
    }

    public List<String> getPortsForBridge(String bridge) {

        ArrayNode where = objectMapper.createArrayNode();
        where.add("");
        
        ArrayNode columns = objectMapper.createArrayNode();
        columns.add("_uuid");
        columns.add("name");
        
        ArrayNode bridgeRows = select(TABLE_PORT, where, columns);

        List<String> brs = new ArrayList<String>();
        
        for (JsonNode row : bridgeRows) {
            brs.add(row.get("name").getTextValue());
        }
        
        return brs;
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
            long bridgeId, String bridgeName, ObjectNode ifRow,
            ObjectNode ifOptions, ObjectNode portRow,
            ObjectNode portExternalIds) {

        String bridgeUuid = getBridgeUuidForId(bridgeId, bridgeName);

        Transaction tx = new Transaction(database);

        // TODO: do nothing if a port with that name already exists

        String ifUuid = generateUuid();
        if (ifOptions != null) {
            portRow.put("options", ifOptions);
        }
        tx.insert(TABLE_INTERFACE, ifUuid, ifRow);

        String portUuid = generateUuid();
        portRow.put("interfaces", getNewRowOvsUuid(ifUuid));
        if (portExternalIds != null) {
            portRow.put("external_ids", portExternalIds);
        }
        tx.insert(TABLE_PORT, portUuid, portRow);

        tx.setInsert(TABLE_BRIDGE, bridgeUuid, "ports",
                getNewRowOvsUuid(portUuid));

        tx.increment(TABLE_OPEN_VSWITCH, null, "next_cfg");

//        if (portExternalIds != null) {
//            // TODO: Factorize this code with BridgeBuilder.build().
//            String extIdsStr = null;
//            for (Map.Entry<String, String> extId :
//                    portExternalIds.entrySet()) {
//                if (extIdsStr == null) {
//                    extIdsStr = String.format("%s=%s", extId.getKey(),
//                            extId.getValue());
//                } else {
//                    extIdsStr = String.format("%s, %s=%s",
//                            extIdsStr, extId.getKey(),
//                            extId.getValue());
//                }
//            }
//            if (bridgeName == null) {
//                tx.addComment(String.format(
//                        "added port %s to bridge %x with external ids %s",
//                        portRow.get("name"), bridgeId, extIdsStr));
//            } else {
//                tx.addComment(String.format(
//                        "added port %s to bridge %s with external ids %s",
//                        portRow.get("name"), bridgeName, extIdsStr));
//            }
//        } else {
//            if (bridgeName == null) {
//                tx.addComment(String.format("added port %s to bridge %x",
//                        portRow.get("name"), bridgeId));
//            } else {
//                tx.addComment(String.format("added port %s to bridge %s",
//                        portRow.get("name"), bridgeName));
//            }
//        }

        doJsonRpc(tx);
    }

    /**
     * A PortBuilder that uses an Synchronous OVSDB connection.
     */
    private class SyncPortBuilder implements PortBuilder {

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
        private ObjectNode ifRow = objectMapper.createObjectNode();

        /**
         * The port's attributes.
         */
        private ObjectNode portRow = objectMapper.createObjectNode();

        /**
         * Arbitrary pairs of key-value strings associated with the port.
         */
        private ObjectNode portExternalIds;

        /**
         * Create a port builder.
         * @param ifType the type of the port to create
         * @param bridgeId the datapath identifier of the bridge to add the
         * port to
         * @param portName the name of the port and of the interface to create
         */
        public SyncPortBuilder(String ifType, long bridgeId,
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
        public SyncPortBuilder(String ifType, String bridgeName,
                                String portName) {
            ifRow.put("type", ifType);
            this.bridgeName = bridgeName;
            ifRow.put("name", portName);
            portRow.put("name", portName);
        }

        @Override
        public PortBuilder externalId(String key, String value) {
            if (portExternalIds == null) {
                portExternalIds = objectMapper.createObjectNode();
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
     * A GrePortBuilder that uses an Synchronous OVSDB connection.
     */
    private class SyncGrePortBuilder implements GrePortBuilder {

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
        private ObjectNode ifRow = objectMapper.createObjectNode();

        /**
         * The interface's options.
         */
        private ObjectNode ifOptions = objectMapper.createObjectNode();

        /**
         * The port's attributes.
         */
        private ObjectNode portRow = objectMapper.createObjectNode();

        /**
         * Arbitrary pairs of key-value strings associated with the port.
         */
        private ObjectNode portExternalIds;

        /**
         * Create a GRE port builder.
         * @param bridgeId the datapath identifier of the bridge to add the
         * port to
         * @param portName the name of the port and of the interface to create
         * @param remoteIp the tunnel remote endpoint's IP address
         */
        public SyncGrePortBuilder(long bridgeId, String portName,
                                   String remoteIp) {
            this.bridgeId = bridgeId;
            
            ifRow.put("type", INTERFACE_TYPE_GRE);
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
        public SyncGrePortBuilder(String bridgeName, String portName,
                                   String remoteIp) {
            this.bridgeName = bridgeName;

            ifRow.put("type", INTERFACE_TYPE_GRE);
            ifRow.put("name", portName);
            portRow.put("name", portName);
            ifOptions.put("remote_ip", remoteIp);
        }

        @Override
        public GrePortBuilder externalId(String key, String value) {
            if (portExternalIds == null) {
                portExternalIds = objectMapper.createObjectNode();
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
     * A ControllerBuilder that uses an Synchronous OVSDB connection.
     */
    private class SyncControllerBuilder implements ControllerBuilder {

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
        private ObjectNode ctrlRow = objectMapper.createObjectNode();

        /**
         * Arbitrary pairs of key-value strings associated with the controller.
         */
        private ObjectNode ctrlExternalIds;

        /**
         * Create a controller builder.
         * @param bridgeId the datapath identifier of the bridge to add the
         * controller to
         * @param target the target to connect to the OpenFlow controller
         */
        public SyncControllerBuilder(long bridgeId, String target) {
            this.bridgeId = bridgeId;
            ctrlRow.put("target", target);
        }

        /**
         * Create a controller builder.
         * @param bridgeName the name of the bridge to add the controller to
         * @param target the target to connect to the OpenFlow controller
         */
        public SyncControllerBuilder(String bridgeName, String target) {
            this.bridgeName = bridgeName;
            ctrlRow.put("target", target);
        }

        @Override
        public ControllerBuilder externalId(String key, String value) {
            if (ctrlExternalIds == null) {
                ctrlExternalIds = objectMapper.createObjectNode();
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
                ctrlRow.put("external_ids", ctrlExternalIds);
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
        return new SyncBridgeBuilder(name);
    }

    @Override
    public PortBuilder addSystemPort(long bridgeId, String portName) {
        return new SyncPortBuilder(INTERFACE_TYPE_SYSTEM, bridgeId, portName);
    }

    @Override
    public PortBuilder addSystemPort(String bridgeName, String portName) {
        return new SyncPortBuilder(INTERFACE_TYPE_SYSTEM, bridgeName, portName);
    }

    @Override
    public PortBuilder addInternalPort(long bridgeId, String portName) {
        return new SyncPortBuilder(INTERFACE_TYPE_INTERNAL, bridgeId, portName);
    }

    @Override
    public PortBuilder addInternalPort(String bridgeName, String portName) {
        return new SyncPortBuilder(INTERFACE_TYPE_INTERNAL, bridgeName, portName);
    }

    @Override
    public PortBuilder addTapPort(long bridgeId, String portName) {
        return new SyncPortBuilder(INTERFACE_TYPE_TAP, bridgeId, portName);
    }

    @Override
    public PortBuilder addTapPort(String bridgeName, String portName) {
        return new SyncPortBuilder(INTERFACE_TYPE_TAP, bridgeName, portName);
    }

    @Override
    public GrePortBuilder addGrePort(long bridgeId, String portName,
                                     String remoteIp) {
        return new SyncGrePortBuilder(bridgeId, portName, remoteIp);
    }

    @Override
    public GrePortBuilder addGrePort(String bridgeName, String portName,
                                     String remoteIp) {
        return new SyncGrePortBuilder(bridgeName, portName, remoteIp);
    }

    @Override
    public void delPort(String portName) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public ControllerBuilder addBridgeOpenflowController(long bridgeId,
                                                         String target) {
        return new SyncControllerBuilder(bridgeId, target);
    }

    @Override
    public ControllerBuilder addBridgeOpenflowController(String bridgeName,
                                                         String target) {
        return new SyncControllerBuilder(bridgeName, target);
    }

    @Override
    public void delBridgeOpenflowControllers(long bridgeId) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public void delBridgeOpenflowControllers(String bridgeName) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public boolean hasBridge(long bridgeId) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public boolean hasBridge(String bridgeName) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public void delBridge(long bridgeId) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public void delBridge(String bridgeName) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public String getDatapathExternalId(long bridgeId, String externalIdKey) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public String getDatapathExternalId(String bridgeName,
                                        String externalIdKey) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public String getPortExternalId(String portName, String externalIdKey) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public String getPortExternalId(long bridgeId, short portNum,
                                    String externalIdKey) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public String getPortExternalId(String bridgeName, short portNum,
                                    String externalIdKey) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public QosBuilder addQos(String type) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public QosBuilder updateQos(String qosUuid, String type) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public void clearQosQueues(String qosUuid) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public void delQos(String qosUuid) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public void setPortQos(String portName, String qosUuid) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public void unsetPortQos(String portName) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public QueueBuilder addQueue() {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public QueueBuilder updateQueue(String queueUuid) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public void delQueue(String queueUuid) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public Set<String> getBridgeNamesByExternalId(String key, String value) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public Set<String> getPortNamesByExternalId(String key, String value) {
        throw new RuntimeException("not implemented"); // TODO
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            log.warn("close", e);
        }
    }

}
