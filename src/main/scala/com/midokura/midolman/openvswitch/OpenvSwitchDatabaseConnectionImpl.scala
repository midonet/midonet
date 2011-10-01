/**
 * OpenvSwitchDatabaaseConnectionImpl.scala - OVSDB connection management classes.
 *
 * A pure Scala implementation of the Open vSwitch database protocol used to
 * configure bridges, ports, etc.
 *
 * This module can connect to the ovsdb daemon using a TCP server socket,
 * using a 'tcp:...' URL.  Other connection schemes (Unix domain, etc.) are
 * not supported.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.openvswitch

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import java.net.{Socket, SocketException}
import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue}
import java.util.{UUID, Timer, TimerTask}

import com.fasterxml.jackson.module.scala.ScalaModule
import org.codehaus.jackson.{JsonNode, JsonFactory, JsonGenerator, JsonParser}
import org.codehaus.jackson.node.{JsonNodeFactory, ObjectNode, ArrayNode, TextNode}
import org.codehaus.jackson.map.ObjectMapper
import org.slf4j.LoggerFactory

import OpenvSwitchDatabaseConsts._
import com.midokura.midolman.openvswitch.OpenvSwitchException._
import java.io._

/**
 * Static methods and constants for OpenvSwitchDatabaseConnection.
 */
object OpenvSwitchDatabaseConnectionImpl {
    private final val echo_interval = 1000
    private final val log = LoggerFactory.getLogger(this.getClass)

    /**
     * Generates a new UUID to identify a newly added row.
     *
     * @return A new UUID.
     */
    def generateUUID(): String = UUID.randomUUID.toString

    /**
     * Transforms a newly inserted row's temporary UUID into a "UUID name", to
     * reference the inserted row in the same transaction.
     *
     * @param uuid The UUID to convert.
     * @return The converted UUID.
     */
    def getUUIDNameFromUUID(uuid: String) = "row" + uuid.replace("-", "_")

    /**
     * Converts a row UUID into an Open vSwitch UUID reference to a row
     * inserted in the same transaction.
     *
     * @param uuid The UUID to convert.
     * @return The Open vSwitch DB row UUID reference.
     */
    def getNewRowOvsUUID(uuid: String): List[String] = {
        List("named-uuid", getUUIDNameFromUUID(uuid))
    }

    /**
     * Create a where clause to select a bridge given its identifier.
     *
     * @param bridgeId If a string, the name of the bridge.  If an integer,
     *                 the datapath identifier of the bridge.
     */
    def bridgeWhereClause(bridgeId: Long): List[List[String]] = {
        List(List(ColumnDatapathId, "==", "%016x".format(bridgeId)))
    }

    /**
     * Create a where clause to select a bridge given its identifier.
     *
     * @param bridgeId If a string, the name of the bridge.  If an integer,
     *                 the datapath identifier of the bridge.
     */
    def bridgeWhereClause(bridgeName: String): List[List[String]] = {
        List(List(ColumnName, "==", bridgeName))
    }

    /**
     * Get a JOSN-encodable 'where' clause to match the row with the uuid.
     *
     * @param uuid The UUID string of the row to match with returned 'where'
     *             clause.
     * @return A JSON-encodable value that represents a 'where' clause matching
     *         row with the given UUID.
     */
    def whereUUIDEquals(uuid: String): List[List[_]] = {
        List(List(ColumnUUID, "==", List("uuid", uuid)))
    }

    /**
     * implicit conversion from Int to String for objects' keys of JSON.
     *
     * @param i The integer value to convert.
     * @return The String of the value.
     */
    implicit def intToString(i: Int): String = i.toString

    /**
     * implicit conversion from Long to String for objects' keys of JSON.
     *
     * @param i The integer value to convert.
     * @return The String of the value.
     */
    implicit def longToString(i: Long): String = i.toString

    /**
     * Converts an Open vSwitch DB map into a Map.
     *
     * @param table   The name of the table containing the row to select.
     * @param where   The JSON-encodable 'where' clause to be matched by the row.
     * @param columns The List of columns to return.
     * @return The Map from the key to the JsonNode.
     */
    def ovsMapToMap(ovsMap: JsonNode): Map[String, JsonNode] = {
        require(ovsMap.get(0).toString != "map",
                "map should be the first entry.")
        (for {
            node <- ovsMap.get(1)
            key = node.get(0) if key != null
            value = node.get(1) if value != null
        } yield (key.getValueAsText,
                 value)).toMap[String, JsonNode]
     }

    /**
     * Converts and Open vSwitch DB map into a Map
     *
     * @param map The Map to convert.
     * @return An Open vSwitch DB map with the key-value pairs of the Map.
     */
    def mapToOvsMap[T](map: Map[T, _], uuid: Boolean = false)
    (implicit f: T => String): List[_] = {
        List("map", (for ((k, v) <- map)
                     yield List(k, if (uuid) List("uuid", v) else v)).toList)
    }

    /**
     * Converts ObjectNode to Map.
     * @param node The ObjectNode to convert.
     * @return An Map from the keys to the values of JsonNode.
     */
    implicit def objectNodeToMap(objectNode: ObjectNode): Map[String, JsonNode] =
        (for (entry <- objectNode.getFields)
         yield (entry.getKey -> entry.getValue)).toMap[String, JsonNode]
}

/**
 * An implementation of a connection to an Open vSwitch database server.
 */
class OpenvSwitchDatabaseConnectionImpl(val database: String, val addr: String,
                                        val port: Int)
extends OpenvSwitchDatabaseConnection with Runnable {
    import OpenvSwitchDatabaseConnectionImpl._

    private var nextRequestid = 0
    private val pendingJsonRpcRequests =
        new mutable.HashMap[Long, BlockingQueue[JsonNode]]()
    private val objectMapper = new ObjectMapper().withModule(new ScalaModule())
    private val jsonFactory = new JsonFactory(objectMapper)
    private val factory = JsonNodeFactory.instance
    private val socket = new Socket(addr, port)
    private val jsonParser = jsonFactory.createJsonParser(
        new InputStreamReader(socket.getInputStream))
    private val jsonGenerator = jsonFactory.createJsonGenerator(
        new OutputStreamWriter(socket.getOutputStream))
    private val timer = new Timer()
    @volatile private var continue = true

    { val me = new Thread(this); me.setDaemon(true); me.start }

    timer.scheduleAtFixedRate(new TimerTask() {
        override def run(): Unit =
            OpenvSwitchDatabaseConnectionImpl.this.synchronized {
            val transact = Map(
                "method" -> "echo",
                "params" -> objectMapper.createArrayNode, "id" -> "echo")
            try {
                objectMapper.writeValue(jsonGenerator, transact)
                jsonGenerator.flush
            } catch {
                case e: IOException =>
                    { log.warn("echo", e); throw new RuntimeException(e) }
            }
        }
    }, 0, echo_interval)

    def stop: Unit = { continue = false }

    /**
     * Apply a operation to the database.
     *
     * @param tx The instance of the transaction.
     * @return The Java representation of the JSON object.
     */
    private def doJsonRpc(tx: Transaction,
                          async: Boolean = false): JsonNode
    = this.synchronized {
        val requestId = nextRequestid
        val request = tx.createJsonRpcRequest(requestId)
        val queue = new ArrayBlockingQueue[JsonNode](1)

        nextRequestid += 1
        pendingJsonRpcRequests.synchronized {
            // TODO: Check that no queue is already registered with that
            // requestId.
            pendingJsonRpcRequests.put(requestId, queue)
        }
        log.debug("doJsonRpc request: %s".format(request))
        try {
            // Serialize the JSON-RPC 1.0 request into JSON text in the output
            // channel.
            try {
                objectMapper.writeValue(jsonGenerator, request)
                jsonGenerator.flush
            } catch {
                case e: IOException =>
                    { log.warn("doJsonRpc", e); throw new RuntimeException(e) }
            }
            // Block until the response is received and parsed.
            // TODO: Set a timeout for the response, using poll() instead of
            // take().
            var response: JsonNode = null
            try {
                response = queue.take
            } catch {
                case e: InterruptedException =>
                    { log.warn("doJsonRpc", e); throw new RuntimeException(e) }
            }
            val responseId: Long = response.get("id").getValueAsLong
            if (responseId != requestId)
                throw new OVSDBException(
                    "wrong id in JSON-RPC result: %s".format(responseId))
            val errorValue = response.get("error")
            if (!errorValue.isNull) {
                log.warn("doJsonRpc: error from server: ", errorValue)
                throw new OVSDBException(
                    "OVSDB request error: " + errorValue.toString,
                    response.get("details").getTextValue)
            }
            // Error may appears in the result field of the response.
            if (response.has("result")) {
                val results = response.get("result").asInstanceOf[ArrayNode]
                log.debug("results: " + results.toString)
                if (results.isNull)
                    return response.get("result")
                for {
                    result <- results if result != null && results.has("error")
                    val error = result.get("error").getTextValue if error != null
                    val details =
                        result.get("details").getTextValue if details != null
                } {
                    log.warn("doJsonRpc: %s : %s".format(error, details))
                    throw new OVSDBException(
                        "OVSDB response error: %s".format(error), details)
                }
            }
            return response.get("result")
        } finally {
            pendingJsonRpcRequests.synchronized {
                pendingJsonRpcRequests.remove(requestId)
            }
        }
    }

    override def run(): Unit = {
        while (continue) {
            try {
                val json = jsonParser.readValueAsTree
                log.debug("OVSDB response: %s".format(json))

                if (json.get("result") != null) {
                    val id = json.get("id")
                    assume(id != null, "Invalid JSON object.")
                    // Ignore echo response.
                    if (id.getTextValue != "echo") {
                        val requestId = json.get("id").getValueAsLong
                        pendingJsonRpcRequests.synchronized {
                            pendingJsonRpcRequests.get(requestId) match {
                                // Pass the JSON object to the caller, and
                                // notify it.
                                case Some(queue) => queue.add(json)
                                case None => throw new OVSDBException(
                                    "Invalid requestId %d".format(requestId))
                            }
                        }
                    }
                }
                //TODO: handle "notification" type
            } catch {
                case e: InterruptedException =>
                    { stop; log.warn("run", e) }
                case e: SocketException =>
                    { stop;
                      // TODO: Ignore this when the parent thread close the 
                      //       socket.
                    }
                case e: EOFException =>
                    { stop; log.info("run", "EOF: the socket closed.") }
                case e: IOException =>
                    { stop; log.warn("run", e) }
            }
        }
    }

    /**
     * A transaction to be performed by an Open vSwitch database server.
     */
    private class Transaction(val database: String) {
        private var dryRun: Boolean = false
        private val comments = ListBuffer[String]()
        private val rowSelections = ListBuffer[Map[String, _]]()
        private val rowDeletions = ListBuffer[Map[String, _]]()
        private val rowInsertions = ListBuffer[Map[String, _]]()
        private val rowUpdates = ListBuffer[Map[String, _]]()
        private val rowMutations = ListBuffer[Map[String, _]]()

        /**
         * The transaction to abort all changes, or not.
         *
         * @param dryRun If true, the transaction's changes are unconditionally
         *               aborted at the end of the transaction.
         */
        def setDryRun(dryRun: Boolean) = {
            this.dryRun = dryRun
        }

        /**
         * Add a comment to be added into the logs when the transaction is
         * successfully committed.
         *
         * @param comment The comment to log.
         */
        def addComment(comment: String) = {
            comments += comment
        }

        /**
         * Get a JSON-encodable representation of this transaction's changes
         *
         * @param id An unambigous JSON-RPC request ID assigned to the
         *           newrequest.
         * @return A Scala value that can be encoded into JSON to represent
         *         this transaction in a JSON-RPC call to a DB.
         */
        def createJsonRpcRequest(requestId: Long): Map[String, _] = {
            var params: ListBuffer[Any] = ListBuffer(database)

            if (!rowSelections.isEmpty)  params ++= rowSelections
            if (!rowDeletions.isEmpty)   params ++= rowDeletions
            if (!rowInsertions.isEmpty)  params ++= rowInsertions
            if (!rowUpdates.isEmpty)     params ++= rowUpdates
            if (!rowMutations.isEmpty)   params ++= rowMutations
            if (!this.comments.isEmpty)
                params += Map("op" -> "comment",
                              "comment" -> comments.mkString("\n"))
            if (dryRun)
                params += Map("op" -> "abort")
            Map("method" -> "transact", "params" -> params, "id" -> requestId)
        }

        /**
         * Select columns for the rows that match the 'where' clause.
         *
         * @param table The name of the table containing the rows to select.
         * @param where The JSON-encodable 'where' clauses to be matched by
         *              the row.
         * @param columns The list of columns to return.
         */
        def select(table: String, where: List[List[_]],
                   columns: List[String]) = {
            rowSelections += Map("op" -> "select", "table" -> table,
                                 "where" -> where, "columns" -> columns)
        }

        /**
         * Delete a row in this transaction.
         *
         * @param table The name of the table containing the row to delete.
         * @param rowUuid The UUID string of the row to delete.
         */
        def delete(table: String, rowUUID: Option[String]) = {
            val where: List[List[_]] = rowUUID match {
                case Some(s) => whereUUIDEquals(s)
                case None => List()
            }
            rowDeletions += Map("op" -> "delete", "table" -> table,
                                "where" -> where)
        }

        /**
         * Insert a row in this transaction.
         *
         * @param table The name of the table to contain the inserted row.
         * @param rowUUID The UUID string of the row to insert.
         * @param row A Map of the column / values of the inserted row.
         */
        def insert(table: String, rowUUID: String, row: Map[String, _]) = {
            rowInsertions += Map("op" -> "insert", "table" -> table,
                                 "uuid-name" -> getUUIDNameFromUUID(rowUUID),
                                 "row" -> row)
        }

        /**
         * Update a row in this transaction.
         *
         * @param table   The name of the table containing the row to update.
         * @param rowUUID The UUID string of the row to update.
         * @param row     A Map of the column / values updated.
         */
        def update(table: String, rowUUID: Option[String],
                   row: Map[String, _]) = {
            val where: List[List[_]] = rowUUID match {
                case Some(s) => whereUUIDEquals(s)
                case None => List()
            }
            rowUpdates += Map("op" -> "update", "table" -> table,
                              "where" -> where, "row" -> row)
        }

        /**
         * Increment values in columns for a given row in this transaction.
         *
         * @param table   The name of the table containig the row to update.
         * @param rowUUID The UUID string of the row to update.
         * @param columns The List of column names of the columns to increment.
         */
        def increment(table: String, rowUUID: Option[String],
                      columns: List[String]) = {
            val where: List[List[_]] = rowUUID match {
                case Some(s) => whereUUIDEquals(s)
                case None => List()
            }
            rowMutations += Map("op" -> "mutate", "table" -> table,
                                "where" -> where,
                                "mutations" -> (for (column <- columns)
                                                yield List(column, "+=", 1)))
        }

        /**
         * Increment values in columns for a given row in this transaction.
         *
         * This method is overloaded alias for
         *   increment(table: String, rowUUID: String, columns: List[String])
         *
         * @param table   The name of the table containig the row to update.
         * @param rowUUID The UUID string of the row to update.
         * @param columns The List of column names of the columns to increment.
         * @see           #increment(String, Option[String], List[String]): Unit
         */
        def increment(table: String, rowUUID: Option[String],
                      columns: String): Unit = {
            increment(table, rowUUID, List(columns))
        }

        /**
         * Insert a value into a set column for a given row in this transaction.
         *
         * @param table   The name of the table containing the row to update.
         * @param rowUUID The UUID string of th row update.
         * @param column  The set column to update.
         * @param value   The value to insert into the set.
         */
        def setInsert(table: String, rowUUID: Option[String],
                      column: String , value: Any) = {
            val where: List[List[_]] = rowUUID match {
                case Some(s) => whereUUIDEquals(s)
                case None => List()
            }
            rowMutations += Map("op" -> "mutate", "table" -> table,
                                "where" -> where,
                                "mutations" -> List(List(column, "insert",
                                                         value)))
        }

        /**
         * Delete a value from a set column for a given row in this transaction.
         *
         * @param table   The name of the table containing the row to update
         * @param rowUUID The UUID string of the row to update.
         * @param column  The set column to update.
         * @param value   The value to delete from the set
         */
        def setDelete(table: String, rowUUID: Option[String],
                      column: String, value: Any) = {
            val where: List[List[_]] = rowUUID match {
                case Some(s) => whereUUIDEquals(s)
                case None => List()
            }
            rowMutations += Map("op" -> "mutate", "table" -> table,
                                "where" -> where,
                                "mutations" -> List(
                                    List(column, "delete", value)))
        }
    }

    /**
     * Select data from the database.
     *
     * @param table   The name of the table containing the rows to select.
     * @param where   The JSON-encodable 'where' clause to be matched by the
     *                rows.
     * @param columns The list of columns to return.
     * @return The list of selected rows.
     */
    private def select(table: String, where: List[List[_]],
                       column: List[String]): JsonNode = {
        val tx = new Transaction(database)
        tx.select(table, where, column)
        val json = doJsonRpc(tx)
        assume(json.get(0) != null, "Invalid JSON object.")
        json.get(0).get("rows")
    }

    /**
     * Get rows of columns in table that contains key-val pair in the
     * external_ids column.
     *
     * @param table The name of the table containing the rows to select.
     * @param key key to seek in the external_ids column.
     * @param value value to seek in the external_ids column.
     * @param columns The list of columns to return.
     * @return The list of selected rows.
     */
    def selectByExternalId(table: String, key: String, value: String,
                           columns: List[String]): JsonNode = {
        val tx = new Transaction(database)
        tx.select(table, List(List(ColumnExternalIds, "includes",
                                   mapToOvsMap(Map(key -> value)))), columns)
        val json = doJsonRpc(tx)
        assume(json.get(0) != null, "Invalid JSON object.")
        json.get(0).get("rows")
    }

    /**
     * Query the UUID of a bridge given its datapath ID or its name.
     *
     * @param bridgeId The datapath identifier of the bridge; significant
     *                 only if bridgeName is null.
     * @return The UUID of the bridge associated with the given bridge id.
     */
    private def getBridgeUUID(bridgeId: Long): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeId),
                                List(ColumnUUID))
        if (bridgeRows.isEmpty)
            throw new NotFoundException("no bridge with id " + bridgeId)
        for {
            bridgeRow <- bridgeRows
            _uuid = bridgeRow.get(ColumnUUID) if _uuid != null
            bridgeUUID = _uuid.get(1) if bridgeUUID != null
        } return bridgeUUID.getTextValue

        return ""
    }

    /**
     * Query the UUID of a bridge given its datapath ID or its name.
     *
     * @param bridgeName The name of the bridge; may be null, in which case the
     *                   bridgeId is used to identify the bridge.
     * @return The UUID of the bridge associated with the given bridge id.
     */
    private def getBridgeUUID(bridgeName: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List(ColumnUUID))
        if (bridgeRows.isEmpty)
            throw new NotFoundException("no bridge with name " + bridgeName)
        for {
            bridgeRow <- bridgeRows
            _uuid = bridgeRow.get(ColumnUUID) if _uuid != null
            bridgeUUID = _uuid.get(1) if bridgeUUID != null
        } return bridgeUUID.getTextValue

        return ""
    }

    /**
     * A BridgeBuilder that uses a synchronous OVSDB connection.
     */
    private class BridgeBuilderImpl(val name: String) extends BridgeBuilder {
        require(name != null, "The name of the bridge is required.")
        private var ifRow = Map[String, String](ColumnName -> name)
        private var portRow = Map[String, Any](ColumnName -> name)
        private var bridgeRow = Map[String, Any](ColumnName -> name,
                                                 ColumnDatapathType -> "")
        private var bridgeExternalIds = Map[String, String]()

        /**
         * Add an external id.
         *
         * @param key The key of the external id entry.
         * @param key The value of the external id entry.
         * @return This SBridgeBuilder instance.
         */
        override def externalId(key: String, value: String) = {
            bridgeExternalIds += (key -> value)
            this
        }

        /**
         * Set the fail mode.
         *
         * @param failMode The failMode instance to set.
         * @return This SBridgeBuilder instance.
         */
        override def failMode(failMode: BridgeFailMode) = {
            bridgeRow += ("fail_mode" -> failMode.getMode)
            this
        }

        /**
         * Build the bridge base on this BridgeBuilderImpl instance.
         */
        override def build() = {
            val tx = new Transaction(database)
            val ifUUID: String = generateUUID
            tx.insert(TableInterface, ifUUID, ifRow)
            val portUUID = generateUUID
            portRow += (ColumnInterfaces -> getNewRowOvsUUID(ifUUID))
            tx.insert(TablePort, portUUID, portRow)
            val bridgeUUID = generateUUID
            bridgeRow += (ColumnPorts -> getNewRowOvsUUID(portUUID))
            bridgeRow += (ColumnExternalIds -> mapToOvsMap(bridgeExternalIds))
            tx.insert(TableBridge, bridgeUUID, bridgeRow)
            tx.setInsert(TableOpenvSwitch, None, ColumnBridges,
                         getNewRowOvsUUID(bridgeUUID))
            val extIds: Iterable[String] =
                for ((k, v) <- bridgeExternalIds) yield "%s=%s".format(k, v)
            val extIdsStr: String = extIds.mkString(", ")
            tx.addComment("added bridge %s with external ids %s.".format(
                bridgeUUID, extIdsStr))
            tx.increment(TableOpenvSwitch, None, ColumnNextConfig)
            doJsonRpc(tx)
        }
    }

    /**
     * A BridgeBuilder that uses a synchronous OVSDB connection.
     */
    private class PortBuilderImpl(
        val ifType: String, val bridgeId: Long = 0, val portName: String,
        val bridgeName: String="") extends PortBuilder {
        private var ifRow = Map[String, String](ColumnType -> ifType,
                                                ColumnName -> portName)
        private var portRow = Map[String, String](ColumnName -> portName)
        private var portExternalIds = Map[String, String]()

        /**
         * Add an external id.
         *
         * @param key The key of the external id entry.
         * @param key The value of the external id entry.
         * @return This SBridgeBuilder instance.
         */
        override def externalId(key: String, value: String) = {
            portExternalIds += (key -> value)
            this
        }

        /**
         * Add a MAC.
         *
         * @param ifMac The MAC address of the interface to add.
         * @return This SBridgeBuilder instance.
         */
        override def ifMac(ifMac: String) = {
            ifRow += (ColumnMac -> ifMac)
            portRow += (ColumnMac -> ifMac)
            this
        }

        /**
         * Build the port base on this PortBuilder instance.
         */
        override def build() = {
            val bridgeUUID = if (!bridgeName.isEmpty) {
                getBridgeUUID(bridgeName)
            } else {
                getBridgeUUID(bridgeId)
            }
            addPort(bridgeUUID, ifRow, portRow, None, Some(portExternalIds))
        }
    }

    /**
     * A GrePortBuilder that uses an synchronous OVSDB connection.
     */
    private class GrePortBuilderImpl(val bridgeId: Long = 0, val portName: String,
                                     val remoteIp: String,
                                     val bridgeName: String="")
            extends GrePortBuilder {
        private var ifRow: Map[String, String] =
            Map(ColumnType -> InterfaceTypeGre, ColumnName -> portName)
        private var portRow: Map[String, String] = Map(ColumnName -> portName)

        private var ifOptions: Map[String, _] = Map(ColumnRemoteIp -> remoteIp)
        private var portExternalIds = Map[String, String]()

        override def externalId(key: String, value: String) =
            { portExternalIds += (key -> value); this }
        override def ifMac(ifMac: String) =
            { ifRow += (ColumnMac -> ifMac); this }
        override def localIp(localIp: String) =
            { ifOptions += (ColumnLocalIp -> localIp); this }
        override def outKey(outKey: Int) =
            { ifOptions += (ColumnOutKey -> outKey.toString); this }
        override def outKeyFlow() =
            { ifOptions += (ColumnOutKey -> "flow"); this }
        override def inKey(inKey: Int) =
            { ifOptions += (ColumnInKey -> inKey.toString); this }
        override def inKeyFlow() =
            { ifOptions += (ColumnInKey -> "flow"); this }
        override def key(key: Int) =
            { ifOptions += (ColumnKey -> key.toString); this }
        override def keyFlow() =
            { ifOptions += (ColumnInKey -> "flow"); this }
        override def tos(tos: Byte) =
            { ifOptions += (ColumnTos -> tos.toString); this }
        override def tosInherit() =
            { ifOptions += (ColumnTos -> "inherit"); this }
        override def ttl(ttl: Byte) =
            { ifOptions += (ColumnTtl -> ttl.toString); this }
        override def ttlInherit() =
            { ifOptions += (ColumnTtl -> "inherit"); this }
        override def enableCsum() =
            { ifOptions += (ColumnCsum -> true); this }
        override def disablePmtud() =
            { ifOptions += (ColumnPmtud -> false); this }
        override def disableHeaderCache() =
            { ifOptions += (ColumnHeaderCache -> false); this }
        override def build() = {
            val bridgeUUID = if (!bridgeName.isEmpty) {
                getBridgeUUID(bridgeName)
            } else {
                getBridgeUUID(bridgeId)
            }
            addPort(bridgeUUID, ifRow, portRow, Some(ifOptions),
                    Some(portExternalIds))
        }
     }

    /**
     * A ControllerBuilder that uses an synchronous OVSDB connection.
     */
    private class ControllerBuilderImpl(val bridgeId: Long = 0, val target: String,
                                        val bridgeName: String="")
            extends ControllerBuilder {
        private var ctrlRow: Map[String, _] = Map(ColumnTarget -> target)
        private var ctrlExternalIds: Map[String, String] = Map()

        override def externalId(key: String, value: String) =
            { ctrlExternalIds += (key -> value); this }
        override def connectionMode(connectionMode: ControllerConnectionMode) =
            { ctrlRow += (ColumnConnectionMode -> connectionMode.getMode); this }
        override def maxBackoff(maxBackoff: Int) =
            { ctrlRow += (ColumnMaxBackoff -> maxBackoff.toString); this }
        override def inactivityProbe(inactivityProbe: Int) = {
            ctrlRow += (ColumnInacrivityProbe -> inactivityProbe.toString); this
        }
        override def controllerRateLimit(controllerRateLimit: Int) = {
            ctrlRow += (ColumnControllerRateLimit -> controllerRateLimit); this
        }
        override def controllerBurstLimit(controllerBurstLimit: Int) = {
            ctrlRow += (
                ColumnControllerBurstLimit -> controllerBurstLimit.toString)
            this
        }
        override def discoverAcceptRegex(discoverAcceptRegex: String) = {
            ctrlRow += (ColumnDiscoverAcceptRegex -> discoverAcceptRegex); this
        }
        override def discoverUpdateResolvConf(
            discoverUpdateResolvConf: Boolean) = {
            ctrlRow += (ColumnDiscoverUpdateResolvConf ->
                        (if (discoverUpdateResolvConf) true else false))
            this
        }
        override def localIp(localIp: String) = {
            ctrlRow += (ColumnLocalIp -> localIp); this
        }
        override def localNetmask(localNetmask: String) = {
            ctrlRow += (ColumnLocalNetMask -> localNetmask); this
        }
        override def localGateway(localGateway: String) = {
            ctrlRow += (ColumnLocalGateway -> localGateway); this
        }
        override def build() = {
            val bridgeUUID: String = if (!bridgeName.isEmpty) {
                getBridgeUUID(bridgeName)
            } else {
                getBridgeUUID(bridgeId)
            }
            val tx = new Transaction(database)
            val ctrlUUID: String = generateUUID
            ctrlRow += (ColumnExternalIds -> mapToOvsMap(ctrlExternalIds))
            tx.insert(TableController, ctrlUUID, ctrlRow)
            tx.setInsert(TableBridge, Some(bridgeUUID), ColumnController,
                         getNewRowOvsUUID(ctrlUUID))
            tx.increment(TableOpenvSwitch, None, ColumnNextConfig)
            doJsonRpc(tx)
        }
    }

    /**
     * A QosBuilder that uses an synchronous OVSDB connection.
     */
    class QosBuilderImpl(var qosType: String) extends QosBuilder {
        private var qosQueues: Option[Map[Long, String]] = Some(Map())
        private var qosOtherConfig: Option[Map[String, Long]] = Some(Map())
        private var qosExternalIds: Option[Map[String, _]] = Some(Map())
        override def clear() = {
            qosType = ""
            qosQueues =  Some(Map())
            qosOtherConfig = Some(Map())
            qosExternalIds = Some(Map())
            this
        }
        override def externalId(key: String, value: String) =
            { qosExternalIds = Some(qosExternalIds.get + (key -> value)); this }
        override def maxRate(maxRate: Int) = {
            qosOtherConfig = Some(qosOtherConfig.get + (ColumnMaxRate -> maxRate));
            this
        }
        override def queues(queueUUIDs: 
            java.util.Map[java.lang.Long, java.lang.String]): QosBuilder =
                queues((for ((k, v) <- queueUUIDs)
                        yield ((k: Long) -> (v: String))).toMap[Long, String])
        def queues(queueUUIDs: Map[Long, String]) =
            { qosQueues = Some(qosQueues.get ++ queueUUIDs); this }
        override def build(): String = {
            val tx = new Transaction(database)
            val qosUUID: String = generateUUID
            var qosRow: Map[String, Any] = Map(ColumnType -> qosType)
            if (!qosQueues.isEmpty)
                qosRow += (ColumnQueues -> mapToOvsMap(qosQueues.get, uuid = true))
            if (!qosOtherConfig.isEmpty)
                qosRow += (ColumnOtherConfig -> mapToOvsMap(qosOtherConfig.get))
            if (!qosExternalIds.isEmpty)
                qosRow += (ColumnExternalIds -> mapToOvsMap(qosExternalIds.get))
            tx.insert(TableQos, qosUUID, qosRow)
            tx.setInsert(TablePort, Some(qosUUID), ColumnQos,
                         getNewRowOvsUUID(qosUUID))
            tx.addComment("created QoS with uuid " + qosUUID)
            tx.increment(TableOpenvSwitch, None, ColumnNextConfig)
            val json = doJsonRpc(tx)
            assume(json.get(0) != null, "Invalid JSON object.")
            for {
                qosRow <- json
                uuid = qosRow.get("uuid") if uuid != null
                qosUUID = uuid.get(1) if qosUUID != null
            } return qosUUID.getTextValue
            return ""
        }
        def update(qosUUID: String): QosBuilder = {
            val tx = new Transaction(database)
            val qosRows = select(TableQos, whereUUIDEquals(qosUUID),
                                 List(ColumnUUID, ColumnQueues, 
                                      ColumnOtherConfig, ColumnExternalIds))
            if (qosRows.isEmpty)
                throw new NotFoundException("no QoS with uuid " + qosUUID)
            for (qosRow <- qosRows) {
                var updatedQosRow: Map[String, Any] =
                    objectNodeToMap(qosRow.asInstanceOf[ObjectNode])
                if (!qosQueues.isEmpty)
                    updatedQosRow += (ColumnQueues ->
                                      mapToOvsMap(qosQueues.get, uuid = true))
                if (!qosOtherConfig.isEmpty)
                    updatedQosRow +=(ColumnOtherConfig ->
                                      mapToOvsMap(qosOtherConfig.get))
                if (!qosExternalIds.isEmpty)
                    updatedQosRow += (ColumnExternalIds ->
                                      mapToOvsMap(qosExternalIds.get))
                tx.update(TableQos, Some(qosUUID), updatedQosRow)
            }
            tx.increment(TableOpenvSwitch, None, List(ColumnNextConfig))
            doJsonRpc(tx)
            this
        }
    }

    class QueueBuilderImpl extends QueueBuilder {
        private var queueMaxRate: Option[Long] = None
        private var queueMinRate: Option[Long] = None
        private var queueExternalIds: Option[Map[String, String]] = Some(Map())
        private var queueBurst: Option[Long] = None
        private var queuePriority: Option[Long] = None

        override def clear() = {
            queueMaxRate = None
            queueMinRate = None
            queueExternalIds = Some(Map())
            queueBurst= None
            queuePriority = None
            this
        }
        override def externalId(key: String, value: String) =
            { queueExternalIds = Some(queueExternalIds.get + (key -> value)); this }
        override def minRate(minRate: Long) =
            { queueMinRate = Some(minRate); this }
        override def maxRate(maxRate: Long) =
            { queueMaxRate = Some(maxRate); this }
        override def burst(burst: Long) =
            { queueBurst = Some(burst); this }
        override def priority(priority: Long) =
            { queuePriority = Some(priority); this }
        override def build(): String = {
            val tx = new Transaction(database)
            val queueUUID: String = generateUUID
            var queueOtherConfig: Map[String, String] = Map()
            if (!queueMinRate.isEmpty)
                queueOtherConfig += (ColumnMinRate -> queueMinRate.get.toString)
            if (!queueMaxRate.isEmpty)
                queueOtherConfig += (ColumnMaxRate -> queueMaxRate.get.toString)
            if (!queueBurst.isEmpty)
                queueOtherConfig += (ColumnBurst -> queueBurst.get.toString)
            if (!queuePriority.isEmpty)
                queueOtherConfig += (ColumnPriority -> queuePriority.get.toString)
            var queueRow: Map[String, Any] = Map()
            if (!queueOtherConfig.isEmpty)
                queueRow += (ColumnOtherConfig -> mapToOvsMap(queueOtherConfig))
            if (!queueExternalIds.isEmpty)
                queueRow += (ColumnExternalIds -> mapToOvsMap(queueExternalIds.get))
            tx.insert(TableQueue, queueUUID, queueRow)
            tx.addComment("created Queue with uuid" + queueUUID)
            tx.increment(TableOpenvSwitch, None, ColumnNextConfig)
            val json = doJsonRpc(tx)
            assume(json.get(0) != null, "Invalid JSON object.")
            for {
                queueRow <- json
                uuid = queueRow.get("uuid") if uuid != null
                queueUUID = uuid.get(1) if queueUUID != null
            } return queueUUID.getTextValue
            return ""
        }
        def update(queueUUID: String) = {
            val tx = new Transaction(database)
            val queueRows = select(TableQueue, whereUUIDEquals(queueUUID),
                                   List(ColumnUUID, ColumnOtherConfig,
                                        ColumnExternalIds))
            for (queueRow <- queueRows) {
                var queueOtherConfig: Map[String, Any] = Map()
                var updateQueueRow: Map[String, Any] =
                    objectNodeToMap(queueRow.asInstanceOf[ObjectNode])
                if (!queueMaxRate.isEmpty)
                    queueOtherConfig +=
                        (ColumnMaxRate -> queueMaxRate.get.toString)
                if (!queueMinRate.isEmpty)
                    queueOtherConfig +=
                        (ColumnMinRate -> queueMinRate.get.toString)
                if (!queueBurst.isEmpty)
                    queueOtherConfig += (ColumnBurst -> queueBurst.get.toString)
                if (!queuePriority.isEmpty)
                    queueOtherConfig +=
                        (ColumnPriority -> queuePriority.get.toString)
                if (!queueOtherConfig.isEmpty)
                    updateQueueRow +=
                        (ColumnOtherConfig -> mapToOvsMap(queueOtherConfig))
                if (!queueExternalIds.isEmpty)
                    updateQueueRow +=
                        (ColumnExternalIds -> mapToOvsMap(queueExternalIds.get))
                tx.update(TableQueue, Some(queueUUID), updateQueueRow)
            }
            tx.increment(TableOpenvSwitch, None, List(ColumnNextConfig))
            doJsonRpc(tx)
            this
        }
    }
    
    /**
     * Add a new bridge with the given name.
     *
     * @param name The name of the bridge to add.
     * @return A builder to set optional parameters of the bridge and add it.
     */
    override def addBridge(name: String): BridgeBuilder = {
        val bb = new BridgeBuilderImpl(name)
        return bb
    }

    /**
    * Add a port.
    *
    * @param bridgeUUID      The UUID of the bridge to add the port to.
    * @param ifRow           The interface's attributes.
    * @param ifOptions       The interface's options; may be null.
    * @param portRow         The port's attributes.
    * @param portExternalIds Arbitrary pairs of key-value strings associated
    *                        with the port.
    */
    private def addPort(bridgeUUID: String, ifRow: Map[String, _],
                        portRow: Map[String, _],
                        ifOptions: Option[Map[String, _]],
                        portExternalIds: Option[Map[String, _]]) = {
        log.debug("addPort({}, {}, {}, {}, {})", Array[Object](bridgeUUID,
                  ifRow, portRow, ifOptions, portExternalIds));
        val tx = new Transaction(database)
        val ifUUID: String = generateUUID
        var portRowUpdated: Map[String, Any] = portRow
        var ifRowUpdated: Map[String, Any] = ifRow
        if (!ifOptions.isEmpty)
            ifRowUpdated += ("options" -> mapToOvsMap(ifOptions.get))
        tx.insert(TableInterface, ifUUID, ifRowUpdated)
        val portUUID: String = generateUUID
        if (!portExternalIds.isEmpty)
            portRowUpdated =
                portRow + (ColumnExternalIds -> mapToOvsMap(portExternalIds.get))
        portRowUpdated += (ColumnInterfaces -> getNewRowOvsUUID(ifUUID))
        tx.insert(TablePort, portUUID, portRowUpdated)
        tx.setInsert(TableBridge, Some(bridgeUUID),
                     ColumnPorts, getNewRowOvsUUID(portUUID))
        if (!portExternalIds.isEmpty) {
            val extIds = for ((k, v) <- portExternalIds.get)
                         yield "%s=%s".format(k, v)
            val extIdsStr: String = extIds.mkString(", ")
            tx.addComment(
                "added port %s to bridge %s with external ids %s".format(
                    portRow(ColumnName), bridgeUUID, extIdsStr))
        } else {
            tx.addComment("added port %s to bridge %s".format(
                portRow(ColumnName), bridgeUUID))
        }
        tx.increment(TableOpenvSwitch, None, ColumnNextConfig)
        doJsonRpc(tx)
    }

    /**
     * Create a port and a system interface, and add the port to a bridge.
     *
     * A system interface is for instance a physical Ethernet interface.
     *
     * @param bridgeId The datapath identifier of the bridge to add the port to.
     * @param portName The name of the port and of the interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addSystemPort(bridgeId: Long,
                               portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeSystem, bridgeId, portName)

    /**
     * Create a port and a system interface, and add the port to a bridge.
     *
     * A system interface is for instance a physical Ethernet interface.
     *
     * @param bridgeName The name of the bridge to add the port to.
     * @param portName   The name of the port and of the interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addSystemPort(bridgeName: String,
                               portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeSystem, portName=portName,
                            bridgeName=bridgeName)

    /**
     * Create a port and an internal interface, and add the port to a bridge
     *
     * An internal interface is a virtual physical Ethernet interface usable
     * to exchange packets only with the bridge.
     *
     * @param bridgeId The datapath identifier of the bridge to add the port to.
     * @param portName The name of the port and of the interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addInternalPort(bridgeId: Long,
                        portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeInternal, bridgeId, portName)

    /**
     * Create a port and an internal interface, and add the port to a bridge
     *
     * An internal interface is a virtual physical Ethernet interface usable
     * to exchange packets only with the bridge.
     *
     * @param bridgeName The name of the bridge to add the port to.
     * @param portName   The name of the port and of the interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addInternalPort(bridgeName: String,
                        portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeInternal, portName=portName,
                        bridgeName=bridgeName)

    /**
     * Create a port and a TAP interface, and add the port to a bridge.
     *
     * @param bridgeId The datapath identifier of the bridge to add the port to.
     * @param portName The name of the port and of the TAP interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addTapPort(bridgeId: Long, portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeTap, bridgeId, portName)

    /**
     * Create a port and a TAP interface, and add the port to a bridge.
     *
     * @param bridgeName The name of the bridge to add the port to.
     * @param portName   The name of the port and of the TAP interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addTapPort(bridgeName: String, portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeTap, portName=portName,
                            bridgeName=bridgeName)

    /**
     * Create a port and a GRE interface, and add the port to a bridge.
     *
     * @param bridgeId The datapath identifier of the bridge to add the port to.
     * @param portName The name of the port and of the TAP interface to create.
     * @param remoteIp The tunnel remote endpoint's IP address.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addGrePort(bridgeId: Long,  portName: String,
                   remoteIp: String): GrePortBuilder =
        new GrePortBuilderImpl(bridgeId, portName, remoteIp)

    /**
     * Create a port and a GRE interface, and add the port to a bridge.
     *
     * @param bridgeName The name of the bridge to add the port to.
     * @param portName   The name of the port and of the TAP interface to create.
     * @param remoteIp   The tunnel remote endpoint's IP address.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addGrePort(bridgeName: String,  portName: String,
                   remoteIp: String): GrePortBuilder =
        new GrePortBuilderImpl(bridgeName=bridgeName, portName=portName,
                               remoteIp=remoteIp)

    /**
     * Delete a port with the given port name.
     *
     * @param portName The name of the port to delete.
     */
    override def delPort(portName: String) = {
        val tx: Transaction = new Transaction(database)
        val portRows = select(TablePort, List(List(ColumnName, "==", portName)),
                              List(ColumnUUID, ColumnInterfaces))
        if (portRows.isEmpty)
            throw new NotFoundException("no port with name " + portName)
        for {
            portRow <- portRows
            _uuid = portRow.get(ColumnUUID) if _uuid != null
            uuidVal = _uuid.get(1) if uuidVal != null
        } {
            val portUUID: String = uuidVal.getTextValue
            tx.delete(TablePort, Some(portUUID))

            val ifs: JsonNode = portRow.get(ColumnInterfaces)
            assume(ifs != null, "Invalid JSON object.")
            // ifs could be a single array, ["uuid", "1234"], or
            // a set of array, ["set" [["uuid", "1234"], ["uuid", "5678"]]].
            log.debug("delPort: ifs = {}", ifs)
            val ifUUIDs: List[JsonNode] =
                if (ifs.get(0).getTextValue == "uuid") {
                    List(ifs)
                } else if (ifs.get(0).getTextValue == "set") {
                    assume(ifs.get(1) != null, "Invalid JSON object.")
                    ifs.get(1).getElements.toList
                } else {
                    assume(ifs.get(0) == null, "Invalid JSON object.")
                    List()
                } 
            log.debug("delPort: ifUUIDs = {}", ifUUIDs)
            for {
                uuidArray <- ifUUIDs
                uuidArrayVal = uuidArray.get(1) if uuidArrayVal != null
            } {
                val ifUUID = uuidArrayVal.getTextValue
                tx.delete(TableInterface, Some(ifUUID))
            }
            tx.setDelete(TableBridge, None, ColumnPorts, List("uuid", portUUID))
        }
        tx.addComment("deleted port %s".format(portName))
        tx.increment(TableOpenvSwitch, None, List(ColumnNextConfig))
        doJsonRpc(tx)
    }

    /**
     * Add an OpenFlow controller for a bridge.
     *
     * An OpenFlow controller target may be in any of the following forms for a
     * primary controller (i.e. a normal OpenFlow controller):
     *     'ssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633) on the
     *         host at the given ip, which must be expressed as an IP address
     *         (not a DNS name).
     *     'tcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633) on the
     *         host at the given ip, which must be expressed as an IP address
     *         (not a DNS name).
     *     'discover': The switch discovers the controller by broadcasting DHCP
     *         requests with vendor class identifier 'OpenFlow'.
     *
     * An OpenFlow controller target may be in any of the following forms for a
     * service controller (i.e. a controller that only connects temporarily and
     * doesn't affect the datapath's failMode):
     *     'pssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633) and
     *         ip Open vSwitch listens on for connections from controller; the
     *         given ip must be expressed as an IP address (not a DNS name).
     *     'ptcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633) and
     *         ip Open vSwitch listens on for connections from controllers; the
     *         given ip must be expressed as an IP address (not a DNS name).
     *
     * @param bridgeId The datapath identifier of the bridge to add the
     *                 controller to.
     * @param target   The target to connect to the OpenFlow controller.
     * @return A builder to set optional parameters of the controller
     *         connection and add it.
     */
    override def addBridgeOpenflowController(
        bridgeId: Long, target: String): ControllerBuilder =
            new ControllerBuilderImpl(bridgeId, target)

    /**
     * Add an OpenFlow controller for a bridge.
     *
     * An OpenFlow controller target may be in any of the following forms for a
     * primary controller (i.e. a normal OpenFlow controller):
     *     'ssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633) on the
     *         host at the given ip, which must be expressed as an IP address
     *         (not a DNS name).
     *     'tcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633) on the
     *         host at the given ip, which must be expressed as an IP address
     *         (not a DNS name).
     *     'discover': The switch discovers the controller by broadcasting DHCP
     *         requests with vendor class identifier 'OpenFlow'.
     *
     * An OpenFlow controller target may be in any of the following forms for a
     * service controller (i.e. a controller that only connects temporarily and
     * doesn't affect the datapath's failMode):
     *     'pssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633) and
     *         ip Open vSwitch listens on for connections from controllers; the
     *         given ip must be expressed as an IP address (not a DNS name).
     *     'ptcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633) and
     *         IP Open vSwitch listens on for connections from controllers; the
     *         given IP must be expressed as an IP address (not a DNS name).
     *
     * @param bridgeName The name of the bridge to add the controller to.
     * @param target     The target to connect to the OpenFlow controller.
     * @return A builder to set optional parameters of the controller
     *         connection and add it.
     */
    override def addBridgeOpenflowController(
        bridgeName: String, target: String): ControllerBuilder =
            new ControllerBuilderImpl(bridgeName=bridgeName, target=target)

    private def delBridgeOpenflowControllers(
        bridgeRows: JsonNode, bridge: String) = {
        val tx = new Transaction(database)

        for {
            bridgeRow <- bridgeRows
            _uuid = bridgeRow.get(ColumnUUID) if _uuid != null
            uuidVal = _uuid.get(1) if uuidVal != null
            controllers = bridgeRow.get(ColumnController) if controllers != null
            controllersKey = controllers.get(0) if controllersKey != null
        } {
            val bridgeUUID: String = uuidVal.getTextValue
            val controllerUUIDs: List[JsonNode] =
                if (controllersKey.getTextValue == "uuid") {
                    List(controllers)
                } else if (controllersKey.getTextValue == "set") {
                    assume(controllers.get(1) != null, "Invalid JSON object.")
                    controllers.get(1).getElements.toList
                } else {
                    List()
                }

            for {
                controllerUUID <- controllerUUIDs
                controllerUUIDVal = controllerUUID.get(1)
                if controllerUUIDVal != null
            } {
                val uuid = controllerUUIDVal.getTextValue
                tx.delete(TableController, Some(uuid))
                tx.setDelete(TableBridge, Some(bridgeUUID),
                             ColumnController, List("uuid", uuid))
            }
        }
        tx.addComment("deleted controllers for bridge with id " + bridge)
        tx.increment(TableOpenvSwitch, None, List("next_cfg"))
        doJsonRpc(tx)
    }

    /**
     * Delete all the OpenFlow controller targets for a bridge.
     *
     * @param bridgeId The datapath identifier of the bridge.
     */
    override def delBridgeOpenflowControllers(bridgeId: Long) = {
        val bridgeRows =
            select(TableBridge, whereUUIDEquals(getBridgeUUID(bridgeId)),
                   List(ColumnUUID, ColumnController))
        delBridgeOpenflowControllers(bridgeRows, bridgeId.toString)
    }

    /**
     * Delete all the OpenFlow controller entries in the OVS DB.
     */
    def delAllOpenflowControllers() = {
        val controllerRows = select(TableController, List(), List(ColumnUUID))
        var tx = new Transaction(database)
        for { row <- controllerRows } {
            log.info("delAllOpenflowControllers: {}", row)
            tx.delete(TableController, 
                      Some(row.get(ColumnUUID).get(1).getTextValue))
        }
        doJsonRpc(tx)
    }

    /**
     * Delete all the OpenFlow controller entries of a target.
     *
     * @param target The target to delete.
     */
    def delTargetOpenflowControllers(target: String) = {
        val controllerRows = select(
            TableController, List(List(ColumnTarget, "==", target)),
            List(ColumnUUID))
        var tx = new Transaction(database)
        for { row <- controllerRows } {
            log.info("delTargetOpenflowControllers: {}", row)
            tx.delete(TableController, 
                      Some(row.get(ColumnUUID).get(1).getTextValue))
        }
        doJsonRpc(tx)
    }

    /**
     * Delete all the OpenFlow controller targets for a bridge.
     *
     * @param bridgeName The name of the bridge.
     */
    override def delBridgeOpenflowControllers(bridgeName: String) = {
        val bridgeRows =
            select(TableBridge, whereUUIDEquals(getBridgeUUID(bridgeName)),
                   List(ColumnUUID, ColumnController))
        delBridgeOpenflowControllers(bridgeRows, bridgeName)
    }

    /**
     * Determine whether a bridge with a given ID exists.
     *
     * @param bridgeId The datapath identifier of the bridge.
     * @return Whether a bridge with the given ID exists.
     */
    override def hasBridge(bridgeId: Long) = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeId),
                                List(ColumnUUID))
        !bridgeRows.getElements.isEmpty
    }

    /**
     * Determine whether a bridge with a given name exists.
     *
     * @param bridgeName The name of the bridge.
     * @return Whether a bridge with the given name exists.
     */
    override def hasBridge(bridgeName: String) = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List(ColumnUUID))
        !bridgeRows.getElements.isEmpty
    }

    /**
     * Determine whether a port with a given name exists.
     *
     * @param portName The name of the port.
     * @return Whether a port with the given name exists.
     */
    def hasPort(portName: String) = {
        val portRows = select(TablePort, List(List(ColumnName, "==", portName)),
                                List(ColumnUUID))
        !portRows.getElements.isEmpty
    }

    /**
     * Determine whether an interface with a given name exists.
     *
     * @param interfaceName The name of the interface.
     * @return Whether an interface with the given name exists.
     */
    def hasInterface(interfaceName: String) = {
        val interfaceRows = select(TableInterface, List(List(ColumnName, "==", interfaceName)),
                                List(ColumnUUID))
        !interfaceRows.getElements.isEmpty
    }

    def dumpInterfaceTable(): Iterable[(String, String)] = {
        val interfaceRows = select(TableInterface, List(), List(ColumnUUID, ColumnName));
        return for {
            row <- interfaceRows
            jsonName = row.get(ColumnName)
        } yield (if (jsonName == null) "<null>" else jsonName.getTextValue,
                 row.get(ColumnUUID).get(1).getTextValue)
    }

    def delInterface(interfaceUuid: String) {
        var tx = new Transaction(database)
        tx.delete(TableInterface, Some(interfaceUuid))
        doJsonRpc(tx)
    }

    def dumpQosTable(): Iterable[(String, String, JsonNode)] = {
        val interfaceRows = select(TableQos, List(), List(ColumnUUID, ColumnType, ColumnExternalIds));
        return for {
            row <- interfaceRows
        } yield (row.get(ColumnUUID).get(1).getTextValue,
                 row.get(ColumnType).getTextValue,
                 row.get(ColumnExternalIds))
    }

    /**
     * Determine whether a controller with a given target exists.
     *
     * @param target The target of the controller.
     * @return Whether a bridge with the given name exists.
     */
    def hasController(target: String) = {
        val controllerRows = select(
            TableController, List(List(ColumnTarget, "==", target)),
            List(ColumnUUID))
        !controllerRows.getElements.isEmpty
    }

    /**
     * Determine whether a QoS with a given target exists.
     *
     * @param uuid The UUID of the controller.
     * @return Whether a QoS with the given name exists.
     */
    def hasQos(uuid: String) = {
        val qosRows = select(TableQos, whereUUIDEquals(uuid), List(ColumnUUID))
        !qosRows.getElements.isEmpty
    }

    /**
     * Determine whether a queue with a given target exists.
     *
     * @param uuid The UUID of the queue.
     * @return Whether a queue with the given name exists.
     */
    def hasQueue(uuid: String) = {
        val queueRows = select(TableQueue, whereUUIDEquals(uuid),
                               List(ColumnUUID))
        !queueRows.getElements.isEmpty
    }

    /**
     * Determine whether a queue with a given target exists.
     *
     * @param table The table name to check.
     * @param uuid The UUID or name to check.
     * @param column The column name to check.
     * @return Whether a column value exists.
     */
    def isEmptyColumn(table: String, uuid: String, column: String): Boolean = {
        val where: List[List[_]] = table match {
            case TableBridge | TablePort =>
                List(List(ColumnName, "==", uuid))
            case _ =>
                whereUUIDEquals(uuid)
        }
        val rows = select(table, where, List(ColumnUUID, column))
        for (row <- rows) {
            val isEmpty: Boolean =  row.get(column) match {
                case null => true
                case t: TextNode =>
                    if (t.getTextValue.isEmpty) true else false
                case a: ArrayNode =>
                    if (a.getElements.isEmpty)
                        true
                    else
                        if (a.get(0).getTextValue == "map" || 
                            a.get(0).getTextValue == "set")
                            if (a.get(1).getElements.isEmpty)
                                true
                            else
                                false
                        else false
                case _ => false
            }
            return isEmpty
        }
        return true
    }

    private def delBridge(bridgeRows: Iterator[JsonNode], bridge: String) = {
        val tx = new Transaction(database)

        for {
            bridgeRow <- bridgeRows
            _uuid = bridgeRow.get(ColumnUUID) if _uuid != null
            bridgeUUID = _uuid.get(1) if bridgeUUID != null
        } {
            tx.delete(TableBridge, Some(bridgeUUID.getTextValue))
            // The 'Open_vSwitch' table should contain only one row, so pass
            // None as the UUID to update all the rows in there.  Delete the
            // bridge UUID from the set of activated bridges:
            tx.setDelete(TableOpenvSwitch, None, ColumnBridges,
                         List("uuid", bridgeUUID.getTextValue))

            val ports = bridgeRow.get(ColumnPorts)
            assume(ports != null, "Invalid JSON object.")
            assume(ports.get(0) != null, "Invalid JSON object.")
            val portUUIDs: List[JsonNode] =
                if (ports.get(0).getTextValue == "uuid") {
                    List(ports)
                } else if (ports.get(0).getTextValue == "set") {
                    assume(ports.get(1) != null, "Invalid JSON object.")
                    ports.get(1).getElements.toList
                } else {
                    List()
                }
            for {
                portUUID <- portUUIDs
                portUUIDVal = portUUID.get(1) if portUUIDVal != null
            } {
                tx.delete(TablePort, Some(portUUIDVal.getTextValue))
                val portRow = select(
                    TablePort,
                    whereUUIDEquals(portUUIDVal.getTextValue),
                    List(ColumnUUID, ColumnInterfaces)).get(0)
                val ifs = portRow.get(ColumnInterfaces)
                assume(ifs != null, "Invalid JSON object.")
                assume(ifs.get(0) != null, "Invalid JSON object.")
                val ifUUIDs: List[JsonNode] =
                    if (ifs.get(0).getTextValue == "uuid") {
                        List(ifs)
                    } else {
                        ifs.getElements.toList
                    }
                for {
                    ifUUID <- ifUUIDs
                    ifUUIDVal = ifUUID.get(1) if ifUUIDVal != null
                } tx.delete(TableInterface, Some(ifUUIDVal.getTextValue))
            }
        }

        // Trigger ovswitchd to reload the configuration.
        tx.increment(TableOpenvSwitch, None, ColumnNextConfig)

        tx.addComment("deleted bridge with %s".format(bridge))

        doJsonRpc(tx)
    }

    /**
     * Delete the bridge with the given ID.
     *
     * @param bridgeId The datapath identifier of the bridge to delete.
     */
    override def delBridge(bridgeId: Long) = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeId),
                                List(ColumnUUID, ColumnPorts))
        if (bridgeRows.isEmpty)
            throw new NotFoundException("no bridge with id " + bridgeId)
        delBridge(bridgeRows.getElements, bridgeId.toString)
    }

    /**
     * Delete the bridge with the given name.
     *
     * @param bridgeName The name of the bridge
     */
    override def delBridge(bridgeName: String) = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List(ColumnUUID, ColumnPorts))
        if (bridgeRows.isEmpty)
            throw new NotFoundException("no bridge with name " + bridgeName)
        delBridge(bridgeRows.getElements, bridgeName)
    }

    /**
     * Get the datapath identifier of the bridge.
     *
     * @param bridgeName The name of the bridge.
     * @return The datapath identifier of the bridge.
     */
    def getDatapathId(bridgeName: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List(ColumnDatapathId, ColumnPorts))
        if (bridgeRows.isEmpty)
            throw new NotFoundException("no bridge with name " + bridgeName)
        for {
            bridgeRow <- bridgeRows
            datapathId = bridgeRow.get(ColumnDatapathId) if datapathId != null
        } return datapathId.getValueAsText

        return ""
    }

    /**
     * Get an external ID associated with a bridge given its ID.
     *
     * @param bridgeId The datapath identifier of the bridge.
     * @param externalIdKey The key of the external ID to look up.
     * @return The value of the external id, or null if no bridge with that
     *         datapath ID exists or if the bridge has no external ID with that
     *         key.
     */
    override def getDatapathExternalId(bridgeId: Long,
                                       externalIdKey: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeId),
                                List(ColumnExternalIds))
        if (bridgeRows.isEmpty)
            throw new NotFoundException("no bridge with id " + bridgeId)
        for {
            bridgeRow <- bridgeRows
            ovsMap = bridgeRow.get(ColumnExternalIds) if ovsMap != null
        } {
            val extIds = ovsMapToMap(ovsMap)
            for (externalIdVal <- extIds.get(externalIdKey)) {
                return externalIdVal.getTextValue
            }
        }
        return null
    }

    /**
     * Get an external ID associated with a bridge given its name.
     *
     * @param bridgeName    The name of the bridge.
     * @param externalIdKey The key of the external ID to look up.
     * @return The value of the external id, or null if no bridge with that name
     *         exists or if the bridge has no external ID with that key.
     */
    override def getDatapathExternalId(bridgeName: String,
                                       externalIdKey: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List(ColumnExternalIds))
        if (bridgeRows.isEmpty)
            throw new NotFoundException("no bridge with name " + bridgeName)
        for {
            bridgeRow <- bridgeRows
            ovsMap = bridgeRow.get(ColumnExternalIds) if ovsMap != null
        } {
            val extIds = ovsMapToMap(ovsMap)
            for (externalIdVal <- extIds.get(externalIdKey)) {
                return externalIdVal.getTextValue
            }
        }
        return null
    }

    private def getPortExternalId(
        bridgeRows: Iterator[JsonNode], portNum: Int,
        externalIdKey: String): String = {
        for {
            bridgeRow <- bridgeRows
            ports = bridgeRow.get(ColumnPorts) if ports != null
            portsKey = ports.get(0) if portsKey != null
        } {
            val portUUIDs: List[JsonNode] =
                if (portsKey.getTextValue == "uuid") {
                    List(ports)
                } else if (portsKey.getTextValue == "set") {
                    assume(ports.get(1) != null, "Invalid JSON object.")
                    ports.get(1).getElements.toList
                } else {
                    List()
                }
            for {
                portUUID <- portUUIDs
                portUUIDVal = portUUID.get(1) if portUUIDVal !=  null
            } {
                val portRow = select(
                    TablePort, whereUUIDEquals(portUUID.get(1).getTextValue),
                    List(ColumnUUID, ColumnInterfaces, ColumnExternalIds)
                    ).get(0)
                val ifs = portRow.get(ColumnInterfaces)
                assume(ifs != null, "Invalid JSON object.")
                val ifUUIDs: List[JsonNode] =
                    if (ifs.get(0).getTextValue == "uuid") {
                        List(ifs)
                    } else {
                        assume(ifs.get(1) != null, "Invalid JSON object.")
                        ifs.get(1).getElements.toList
                    }
                for {
                    ifUUID <- ifUUIDs
                    ifUUIDVal = ifUUID.get(1) if ifUUIDVal != null
                } {
                    val ifRow =
                        select(TableInterface,
                               whereUUIDEquals(ifUUIDVal.getTextValue),
                               List(ColumnUUID, ColumnOfPort)).get(0)
                    assume(ifRow.get(ColumnOfPort) != null,
                           "Invalid JSON object.")
                    if (ifRow.get(ColumnOfPort).getValueAsInt == portNum) {
                        assume(portRow.get(ColumnExternalIds) != null,
                               "Invalid JSON object.")
                        val extIds =
                            ovsMapToMap(portRow.get(ColumnExternalIds))
                        for (externalIdVal <- extIds.get(externalIdKey)) {
                            return externalIdVal.getTextValue
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Get an external ID associated with a port given its name.
     *
     * @param portName The name of the port.
     * @param externalIdKey The key of the external ID to look up.
     * @return The value of the external ID, or null if no port with that name
     *         exists or if the port has no external id with that key.
     */
    override def getPortExternalId(portName: String,
                                   externalIdKey: String): String = {
        val portRows = select(TablePort, List(List(ColumnName, "==", portName)),
                              List(ColumnUUID, ColumnExternalIds))
        if (portRows.isEmpty)
            throw new NotFoundException("no port with name " + portName)
        for {
            portRow <- portRows
            ovsMap = portRow.get(ColumnExternalIds) if ovsMap != null
        } {
            val extIds = ovsMapToMap(ovsMap)
            for (externalIdVal <- extIds.get(externalIdKey)) {
                return externalIdVal.getTextValue
            }
        }
        return null
    }

    /**
     * Get an external ID associated with a given OpenFlow port number.
     *
     * @param bridgeId      The datapath identifier of the bridge that contains
     *                      the port.
     * @param portNum       The OpenFlow number of the port.
     * @param externalIdKey The key of the external ID to look up.
     * @return The value of the external ID, or null if no bridge with that
     *         datapath ID exists, or if no port with that number exists in that
     *         bridge, or if the port has no external ID with that key.
     */
    override def getPortExternalId(bridgeId: Long, portNum: Int,
                                    externalIdKey: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeId),
                                List(ColumnUUID, ColumnPorts))
        getPortExternalId(bridgeRows.getElements, portNum, externalIdKey)
    }

    /**
     * Get an external ID associated with a given OpenFlow port number.
     *
     * @param bridgeName    The name of the bridge that contains the port.
     * @param portNum       The OpenFlow number of the port.
     * @param externalIdKey The key of the external ID to look up.
     * @return The value of the external ID, or null if no bridge with that
     *         name exists, or if no port with that number exists in that
     *         bridge, or if the port has no external ID with that key.
     */
    override def getPortExternalId(bridgeName: String, portNum: Int,
                                   externalIdKey: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List(ColumnUUID, ColumnPorts))
        getPortExternalId(bridgeRows.getElements, portNum, externalIdKey)
    }

    /**
     * Create a QoS.
     *
     * @param qosType The type of the QoS such as 'linux-htb' or 'linux-hfcs'.
     *                Defaults to 'linux-htb'.bridgeName The name of the bridge
     *                to add the port to.
     *                
     * @return A builder to set optional parameters of the QoS and add it.
     */
    override def addQos(qosType: String): QosBuilder =
        new QosBuilderImpl(qosType)

    /**
     * Update a QoS's parameters.
     *
     * @param qosUUID The UUID of the QoS to update
     * @param qosType The new QoS type, or null to keep the existing QoS type
     * @return A builder to reset optional parameters of the QoS and update it
     */
    override def updateQos(qosUUID: String, qosType: String): QosBuilder =
        updateQos(qosUUID, Some(qosType), None, None, None)

    /**
     * Update a QoS's parameters.
     *
     * @param qosUUID The UUID of the QoS to update
     * @param qosType The type of queues such as 'linux-htb' or 'linux-hfcs'.
     *                Defaults to None. i.e., the type of the QoS will not be
     *                updated.
     * @param queueUUIDs The key-value pairs which key is the number of the queue
     *                   and value is the list which the first is 'uuid' and the
     *                   second is the UUID of the queue. Defaults to an empty
     *                   dictionary, i.e., no queues to be updated.
     * @return A builder to reset optional parameters of the QoS and update it.
     */
    def updateQos(qosUUID: String, qosType: Option[String] = Some("linux-htb"),
                  queueUUIDs: Option[Map[Long, String]] =  None,
                  maxRate: Option[Int] = None,
                  externalIds: Option[Map[String, String]] = None
              ): QosBuilder = {
        val qb = new QosBuilderImpl(qosType.get)
        if (!queueUUIDs.isEmpty)
            qb.queues(queueUUIDs.get)
        if (!maxRate.isEmpty)
            qb.maxRate(maxRate.get)
        if (!externalIds.isEmpty)
            for ((k, v) <- externalIds.get)
                qb.externalId(k, v)
        qb
    }

    /**
     * Clear the QoS's queues.
     *
     * @param qosUUID The UUID of the QoS to clear its queues.
     */
    override def clearQosQueues(qosUUID: String) = {
        val qosBuilder = updateQos(qosUUID, queueUUIDs = Some(Map()))
        qosBuilder.update(qosUUID)
    }

    /**
     * Delete a QoS.
     *
     * @param qos_uuid The UUID of the QoS to delete.
     */
    override def delQos(qosUUID: String) = {
        delQos(qosUUID, false)
    }

    /**
     * Delete a QoS.
     *
     * The QoS is removed from any port on which it is set. If deleteQueue is
     * True, delete all queues bound to the QoS.
     *
     * @param qos_uuid The UUID of the QoS to delete.
     * @param deleteQueues The flag whether queues the QoS has will be deleted or
     *        not. Defaults to False, i.e., no queues bound to the QoS will be
     *        deleted.
     */
    def delQos(qosUUID: String, deleteQueues: Boolean = false) = {
        val tx = new Transaction(database)
        val qosRows = select(TableQos, whereUUIDEquals(qosUUID),
                             List(ColumnUUID, ColumnQueues))
        if (qosRows.getElements.isEmpty)
          throw new NotFoundException("no QoS with uuid " + qosUUID)
        tx.delete(TableQos, Some(qosUUID))
        tx.addComment("deleted QoS with uuid " + qosUUID)
        for (qosRow <- qosRows) {
            if (deleteQueues) {
                val queues = ovsMapToMap(qosRows.get(0).get(ColumnQueues))
                for {
                    q <- queues.values
                    queueUUIDNode <- q.getElements
                    val queueUUID = queueUUIDNode.get(1).getTextValue
                } tx.delete(TableQueue, Some(queueUUID))
            }
            val portRows = select(TablePort, 
                List(List(ColumnQos, "includes", List("uuid", qosUUID))),
                List(ColumnUUID, ColumnQos))
            for (portRow <- portRows) {
                val portUUID = portRow.get(ColumnUUID).get(1).getTextValue
                val qosValue = portRow.get(ColumnQos).get(1).getTextValue
                tx.setDelete(TablePort, Some(portUUID), ColumnQos, qosValue)
            }
        }
        tx.addComment("deleted QoS with uuid " + qosUUID)
        tx.increment(TableOpenvSwitch, None, List(ColumnNextConfig))
        doJsonRpc(tx)
    }

    /**
     * Set the QoS to the port.
     *
     * @param portName The name of the port to be added.
     * @param qosUUID The UUID of the QoS to add.
     */
    override def setPortQos(portName: String, qosUUID: String) = {
        val tx = new Transaction(database)
        val portRows = select(TablePort, List(List(ColumnName, "==", portName)),
                              List(ColumnUUID))
        if (portRows.getElements.isEmpty)
            throw new NotFoundException("no port with the name " + portName)
        val qosRows = select(TableQos, whereUUIDEquals(qosUUID),
                             List(ColumnUUID))
        if (qosRows.getElements.isEmpty)
            throw new NotFoundException("no QoS with the uuid " + qosUUID)
        for (portRow <- portRows) {
            val portUUID = portRow.get(ColumnUUID).get(1).getTextValue
            val qosUUID = qosRows.get(0).get(ColumnUUID)
            tx.setInsert(TablePort, Some(portUUID), ColumnQos, qosUUID)
        }
        tx.increment(TableOpenvSwitch, None, List(ColumnNextConfig))
        doJsonRpc(tx)
    }
    
    /**
     * Unset the QoS from the port.
     *
     * @param portName The name of the port to be added.
     */
    override def unsetPortQos(portName: String) = clearQos(portName, false)

    
    /**
     * Clear and unset the QoS from the port.
     *
     * @param portName The name of the port to be added.
     * @param deleteQos The boolean whether to delete the qos or not. Defaults
     *                  to false. i.e., Not to delete queues but only
     *                  disassociate.
     */
    def clearQos(portName: String, deleteQos: Boolean = false) = {
        val tx = new Transaction(database)
        val portRows = select(TablePort, List(List(ColumnName, "==", portName)),
                              List(ColumnUUID, ColumnQos))
        if (portRows.getElements.isEmpty)
            throw new NotFoundException("no port with name " + portName)
        for (portRow <- portRows) {
            val portUUID = portRow.get(ColumnUUID).get(1).getTextValue
            if (deleteQos) {
                val qosUUID = portRow.get(ColumnUUID).getTextValue
                tx.delete(TableQos, Some(qosUUID))
                tx.addComment("deleted the QoS with uuid " + qosUUID)
            }
            var updatedPortRow: Map[String, Any] =
                objectNodeToMap(portRow.asInstanceOf[ObjectNode])
            updatedPortRow += (ColumnQos -> List("set", List()))
            tx.update(TablePort, Some(portUUID), updatedPortRow)
        }
        tx.increment(TableOpenvSwitch, None, List(ColumnNextConfig))
        doJsonRpc(tx)
    }

    /**
     * Create a Queue.
     */
    override def addQueue(): QueueBuilder = new QueueBuilderImpl()

    /**
     * Update a queue's parameters.
     *
     * @param queueUUID The UUID of the queue to update
     * @return A builder to reset optional parameters of the queue and update it
     */
    override def updateQueue(queueUUID: String) =
        updateQueue(queueUUID)

    /**
     * Update a queue's parameters.
     *
     * @param queueUUID The UUID of the queue to update
     * @param maxRate The maximum rate for the queue. Defaults to None, i.e.,
     *                maximum rate will not be updated.
     * @param minRate The minimum rate for the queue. Defaults to None, i.e.,
     *                burst will not be updated.
     * @param burst The burst size in bits (for linux-hfsc). Defaults to None,
     *              i.e., burst will not be updated.
     * @param priority The priority of the queue (for linux-hfsc). Defaults to
     *                 None, i.e., priority will not be updated.
     * @param external_ids The key-value pairs for use by external frameworks.
     *                     Defaults to an empty Map. i.e., external ids will not
     *                     be updated.
     * @return A builder to reset optional parameters of the queue and update it
     */
    def updateQueue(queueUUID: String, maxRate: Option[Long] = None,
                    minRate: Option[Long] = None,
                    externalIds: Option[Map[String, String]] = None,
                    burst: Option[Long] = None,
                    priority: Option[Long] = None): QueueBuilder = {
        val queueBuilder = new QueueBuilderImpl
        if (!maxRate.isEmpty)
            queueBuilder.maxRate(maxRate.get)
        if (!minRate.isEmpty)
            queueBuilder.minRate(minRate.get)
        if (!externalIds.isEmpty)
            for ((k, v) <- externalIds.get) queueBuilder.externalId(k, v)
        if (!burst.isEmpty)
            queueBuilder.burst(burst.get)
        if (!priority.isEmpty)
            queueBuilder.priority(priority.get)
        queueBuilder
    }

    
    /**
     * Delete the queue associated with with the UUID.
     *
     * @param queueUUID The uuid of the queue to be deleted.
     */
    override def delQueue(queueUUID: String) = {
        val tx = new Transaction(database)
        val queueRows = select(TableQueue, whereUUIDEquals(queueUUID),
                               List(ColumnUUID))
        if (queueRows.getElements.isEmpty)
            throw new NotFoundException("no Queue with uuid " + queueUUID)
        tx.delete(TableQueue, Some(queueUUID))
        tx.addComment("deleted queue with uuid " + queueUUID)
        tx.increment(TableOpenvSwitch, None, List(ColumnNextConfig))
        doJsonRpc(tx)
    }

    /**
     * Get the set of names of bridges that are associated a given external
     * ID key-value pair.
     *
     * @param key   The external ID key to lookup.
     * @param value The external ID to lookup.
     * @return The set of names of bridges that are associated the external ID.
     */
    override def getBridgeNamesByExternalId(
        key: String, value: String): java.util.Set[String] = {
        val rows = selectByExternalId(TableBridge, key, value, List(ColumnName))
        mutable.Set(
            (for {
                row <- rows
                name = row.get(ColumnName) if name != null
            } yield name.getTextValue).toList: _*)
    }

    /**
     * Get the set of names of ports that are associated a given external
     * ID key-value pair.
     *
     * @param key   The external ID key to lookup.
     * @param value The external ID to lookup.
     * @return The set of names of ports that are associated the external ID.
     */
    override def getPortNamesByExternalId(
        key: String, value: String): java.util.Set[String] = {
        val rows = selectByExternalId(TablePort, key, value, List("name"))
        mutable.Set(
                (for {
                    row <- rows
                    name = row.get("name") if name != null
                } yield name.getTextValue).toList: _*)
    }

    /**
     * Get the set of QoSs' UUIDs that are associated a given external ID
     * key-value pair.
     *
     * @param key   The external ID key to lookup.
     * @param value The external ID to lookup.
     * @return The set of names of ports that are associated the external ID.
     */
    def getQosUUIDsByExternalId(
        key: String, value: String): java.util.Set[String] = {
        val rows = selectByExternalId(TableQos, key, value, List(ColumnUUID))
        Set((for {
            row <- rows
            _uuid = row.get(ColumnUUID) if _uuid != null
            qosUUID = _uuid.get(1) if qosUUID != null
        } yield qosUUID.getTextValue).toList: _*)
    }

    /**
     * Get the set of queues' UUIDs that are associated a given external ID
     * key-value pair.
     *
     * @param key   The external ID key to lookup.
     * @param value The external ID to lookup.
     * @return The set of names of ports that are associated the external ID.
     */
    def getQueueUUIDsByExternalId(
        key: String, value: String): java.util.Set[String] = {
        val rows = selectByExternalId(TableQueue, key, value, List(ColumnUUID))
        Set((for {
            row <- rows
            _uuid = row.get(ColumnUUID) if _uuid != null
            qosUUID = _uuid.get(1) if qosUUID != null
        } yield qosUUID.getTextValue).toList: _*)
    }

   /**
    * Get the row associated with a queue for which given the number of the
    * queue stands in the QoS.
    *
    * @param qosUUID  The UUID of the QoS that contains the queue.
    * @param queueNum The local number of the queue on the port.
    * @return The row of the queue, as a dictionary. None if no QoS with that
    *         UUID exists, or if no queue with number exists in that QoS.
    */
    private def getQueueRowByQueueNum(qosUUID: String,
                                      queueNum: Long): Option[JsonNode] = {
        val qosRows = select(TableQos, whereUUIDEquals(qosUUID),
                             List(ColumnUUID, ColumnQueues))
        for (qosRow <- qosRows) {
            val queueUUIDs = ovsMapToMap(qosRow.get(ColumnQueues))
            try {
                val queueUUID = queueUUIDs(queueNum).get(1).getTextValue
                val queueRows = select(TableQueue, whereUUIDEquals(queueUUID),
                    List(ColumnUUID, ColumnOtherConfig, ColumnExternalIds))
                if (queueRows.isEmpty)
                    throw new NotFoundException("no queue with UUID " + queueUUID)
                return Some(queueRows.get(0))
            } catch {
                case e: Exception => {
                    throw new NotFoundException(
                        "no queues with queue number " + queueNum.toString)
                }
            }
        }
        return None
    }

    /**
     * Get the UUID associated with a queue for which given the number of the
     * queue stands in the QoS.
     *
     * @param qosUUID  The UUID of the QoS that contains the queue.
     * @param queueNum The local number of the queue on the port.
     * @return The Option value of the UUID, as a string. None if no QoS with
     *         that UUID exists, or if no queue with number exists in that QoS.
     */
    def getQueueUUIDByQueueNum(qosUUID: String, queueNum: Long): Option[String] = {
        val queueRow = getQueueRowByQueueNum(qosUUID, queueNum)
        return if (!queueRow.isEmpty)
                   Some(queueRow.get.get(ColumnUUID).get(1).getTextValue)
               else
                   None
    }

    /**
     * Get the external id associated with the queue for which given the number
     * of the queue stands in the QoS.
     *
     * @param qosUUID       The UUID of the QoS that contains the queue.
     * @param queueNum      The local number of the queue on the port.
     * @param externalIdKey The key of the external id to look up, as a string.
     *
     * @return The value of the external id, as a string. None if no QoS with
     *         that UUID exists, or if no queue with number exists in that QoS,
     *         or if the queue has no external id with that key.
     */
    def getQueueExternalIdByQueueNum(qosUUID: String, queueNum: Long,
                                     externalIdKey: String): Option[String] = {
        val queueRow = getQueueRowByQueueNum(qosUUID, queueNum)
        if (!queueRow.isEmpty) {
            val queueExternalIds = ovsMapToMap(
                queueRow.get.get(ColumnExternalIds))
            return if (!queueExternalIds.isEmpty)
                       Some(queueExternalIds(externalIdKey).getTextValue)
                   else
                       None
        }
        return None
    }

    /**
     * Close the connection.
     */
    override def close() = {
        timer.cancel
        this.stop
        try {
            socket.close
        } catch {
            case e: IOException => { log.warn("close", e) }
        }
    }
}

object OpenvSwitchDatabaseConsts {
    final val InterfaceTypeSystem  = "system"
    final val InterfaceTypeInternal = "internal"
    final val InterfaceTypeTap = "tap"
    final val InterfaceTypeGre = "gre"
    final val TableBridge = "Bridge"
    final val TableController = "Controller"
    final val TableInterface = "Interface"
    final val TableOpenvSwitch = "Open_vSwitch"
    final val TablePort = "Port"
    final val TableQos = "QoS"
    final val TableQueue = "Queue"
    final val ColumnUUID = "_uuid"
    final val ColumnBridges = "bridges"
    final val ColumnBurst = "burst"
    final val ColumnConnectionMode = "connection_mode"
    final val ColumnController = "controller"
    final val ColumnControllerRateLimit = "controller_rate_limit"
    final val ColumnControllerBurstLimit = "controller_burst_limit"
    final val ColumnCsum = "csum"
    final val ColumnDatapathId = "datapath_id"
    final val ColumnDatapathType = "datapath_type"
    final val ColumnDiscoverAcceptRegex = "discover_accept_regex"
    final val ColumnDiscoverUpdateResolvConf = "discover_update_resolv_conf"
    final val ColumnExternalIds = "external_ids"
    final val ColumnHeaderCache = "header_cache"
    final val ColumnInKey = "in_key"
    final val ColumnInterfaces = "interfaces"
    final val ColumnKey = "key"
    final val ColumnLocalIp = "local_ip"
    final val ColumnLocalNetMask = "local_netmask"
    final val ColumnLocalGateway = "local_gateway"
    final val ColumnMac = "mac"
    final val ColumnMaxBackoff = "max_backoff"
    final val ColumnInacrivityProbe = "inactivity_probe"
    final val ColumnMaxRate = "max-rate"
    final val ColumnMinRate = "min-rate"
    final val ColumnName = "name"
    final val ColumnNextConfig = "next_cfg"
    final val ColumnOfPort = "ofport"
    final val ColumnOutKey = "out_key"
    final val ColumnOtherConfig = "other_config"
    final val ColumnPmtud = "pmtud"
    final val ColumnPorts = "ports"
    final val ColumnPriority = "priority"
    final val ColumnQos = "qos"
    final val ColumnQueues = "queues"
    final val ColumnRemoteIp = "remote_ip"
    final val ColumnTarget = "target"
    final val ColumnTos = "tos"
    final val ColumnTtl = "ttl"
    final val ColumnType = "type"
}
