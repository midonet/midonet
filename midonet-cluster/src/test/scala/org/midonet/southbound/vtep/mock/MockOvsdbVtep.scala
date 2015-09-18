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

package org.midonet.southbound.vtep.mock

import java.lang.{Long => JavaLong}
import java.util
import java.util.{Objects, UUID}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

import com.google.common.collect.Lists
import com.google.common.util.concurrent.ListenableFuture
import org.opendaylight.ovsdb.lib.MonitorCallBack
import org.opendaylight.ovsdb.lib.message.{TableUpdate, TransactBuilder}
import org.opendaylight.ovsdb.lib.notation.{Column, Condition, Function => OvsdbFunction, Row, UUID => OvsdbUUID}
import org.opendaylight.ovsdb.lib.operations._
import org.opendaylight.ovsdb.lib.schema.{ColumnSchema, DatabaseSchema, GenericTableSchema}
import rx.Observable
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.OvsdbUtil.toOvsdb
import org.midonet.southbound.vtep.OvsdbVtepConnection.OvsdbHandle
import org.midonet.southbound.vtep.ConnectionState
import org.midonet.southbound.vtep.ConnectionState.State
import org.midonet.southbound.vtep.mock.MockOvsdbClient.{MonitorRegistration, TransactionEngine}
import org.midonet.southbound.vtep.mock.MockOvsdbColumn.{mkColumnSchema, mkMapColumnSchema, mkSetColumnSchema}
import org.midonet.southbound.vtep.schema._
import org.midonet.southbound.vtep.{OvsdbTools, VtepConnection}

object MockOvsdbVtep {
    /** The only supported ovsdb database in the mock vtep */
    val DB_HARDWARE_VTEP = "hardware_vtep"

    /** Some real port names we've seen in VTEPs.  All InMemoryOvsdbVtep
      * instances will have them, for your convenience.
      */
    val physPortNames: util.List[String] =
        List("eth0", "swp1_2", "Te 0/2", "Ten-GigabitEthernet1/0/5:3/0").asJava
}

/**
 * A simple in-memory vtep database implementation for use in testing.
 *
 * NOTE: This implementation does NOT support column selection in queries:
 * all defined columns are always returned in the results. Likewise, monitoring
 * requests do not honor column specification: any update to the entry is
 * reported.
 *
 * Also, transaction support is very limited. In particular, there is no
 * rollback if some operations in a transaction cannot be successfully
 * completed.
 */
class InMemoryOvsdbVtep(mgmtIp: IPv4Addr = IPv4Addr.random,
                        mgmtPort: Int = 6632) extends VtepConnection {

    protected type MockColumnSchema = ColumnSchema[GenericTableSchema, _]
    protected type MockTableSchema = Map[String, MockColumnSchema]

    // A subject to publish the names of tables requested for monitoring
    private val monitorSubject = PublishSubject.create[String]()

    // A Subject to publish the operations requested on the vtep. Note that
    // transaction information is not reported.
    private val operationSubject = PublishSubject.create[Operation[_]]()

    private val lsSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("name", mkColumnSchema("name", classOf[String])),
        ("description", mkColumnSchema("description", classOf[String])),
        ("tunnel_key", mkSetColumnSchema("tunnel_key", classOf[JavaLong]))
    )
    private val uLocalSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("MAC", mkColumnSchema("MAC", classOf[String])),
        ("logical_switch", mkColumnSchema("logical_switch",
                                          classOf[OvsdbUUID])),
        ("locator", mkColumnSchema("locator", classOf[OvsdbUUID])),
        ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
    )
    private val uRemoteSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("MAC", mkColumnSchema("MAC", classOf[String])),
        ("logical_switch", mkColumnSchema("logical_switch",
                                          classOf[OvsdbUUID])),
        ("locator", mkColumnSchema("locator", classOf[OvsdbUUID])),
        ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
    )
    private val mLocalSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("MAC", mkColumnSchema("MAC", classOf[String])),
        ("logical_switch", mkColumnSchema("logical_switch",
                                          classOf[OvsdbUUID])),
        ("locator_set", mkColumnSchema("locator_set", classOf[OvsdbUUID])),
        ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
    )
    private val mRemoteSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("MAC", mkColumnSchema("MAC", classOf[String])),
        ("logical_switch", mkColumnSchema("logical_switch",
                                          classOf[OvsdbUUID])),
        ("locator_set", mkColumnSchema("locator_set", classOf[OvsdbUUID])),
        ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
    )
    private val locSetSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("locators", mkSetColumnSchema("locators", classOf[OvsdbUUID]))
    )
    private val locSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("encapsulation_type", mkColumnSchema("encapsulation_type",
                                              classOf[String])),
        ("dst_ip", mkColumnSchema("dst_ip", classOf[String])),
        ("bfd", mkMapColumnSchema("bfd", classOf[String], classOf[String])),
        ("bfd_status", mkMapColumnSchema("bfd_status", classOf[String],
                                         classOf[String]))
    )
    private val portSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("name", mkColumnSchema("name", classOf[String])),
        ("description", mkColumnSchema("description", classOf[String])),
        ("vlan_bindings", mkMapColumnSchema("vlan_bindings", classOf[JavaLong],
                                            classOf[OvsdbUUID])),
        ("vlan_stats", mkMapColumnSchema("vlan_stats", classOf[JavaLong],
                                         classOf[OvsdbUUID])),
        ("port_fault_status", mkSetColumnSchema("port_fault_status",
                                                classOf[String]))
    )
    private val psSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("name", mkColumnSchema("name", classOf[String])),
        ("ports", mkSetColumnSchema("ports", classOf[OvsdbUUID])),
        ("description", mkColumnSchema("description", classOf[String])),
        ("management_ips", mkSetColumnSchema("management_ips",
                                             classOf[String])),
        ("tunnel_ips", mkSetColumnSchema("tunnel_ips", classOf[String])),
        ("tunnels", mkSetColumnSchema("tunnels", classOf[OvsdbUUID])),
        ("switch_fault_status", mkSetColumnSchema("switch_fault_status",
                                                  classOf[String]))
    )

    /** A class containing the column schema and the target vtep entry model */
    protected case class MockTable[E <: VtepEntry](schema: MockTableSchema)

    /** A map with the descriptions of the supported tables */
    protected val tables = Map[String, MockTable[_ <: VtepEntry]] (
        (LogicalSwitchTable.TB_NAME, MockTable[LogicalSwitch](lsSchema)),
        (McastMacsLocalTable.TB_NAME, MockTable[McastMac](mLocalSchema)),
        (McastMacsRemoteTable.TB_NAME, MockTable[McastMac](mRemoteSchema)),
        (PhysicalLocatorSetTable.TB_NAME, MockTable[PhysicalLocatorSet](locSetSchema)),
        (PhysicalLocatorTable.TB_NAME, MockTable[PhysicalLocator](locSchema)),
        (PhysicalPortTable.TB_NAME, MockTable[PhysicalPort](portSchema)),
        (PhysicalSwitchTable.TB_NAME, MockTable[PhysicalSwitch](psSchema)),
        (UcastMacsLocalTable.TB_NAME, MockTable[UcastMac](uLocalSchema)),
        (UcastMacsRemoteTable.TB_NAME, MockTable[UcastMac](uRemoteSchema))
    )

    /** Ovsdb-compliant database schema */
    protected val databaseSchema = MockOvsdbDatabase.get(
        MockOvsdbVtep.DB_HARDWARE_VTEP,
        tables.map(e => (e._1, new MockOvsdbTable(e._1, e._2.schema))))

    // Table schemas
    private val tableParsers = Map[String, Table[_ <: VtepEntry]](
        (LogicalSwitchTable.TB_NAME, new LogicalSwitchTable(databaseSchema)),
        (McastMacsLocalTable.TB_NAME, new McastMacsLocalTable(databaseSchema)),
        (McastMacsRemoteTable.TB_NAME, new McastMacsRemoteTable(databaseSchema)),
        (PhysicalLocatorSetTable.TB_NAME, new PhysicalLocatorSetTable(databaseSchema)),
        (PhysicalLocatorTable.TB_NAME, new PhysicalLocatorTable(databaseSchema)),
        (PhysicalPortTable.TB_NAME, new PhysicalPortTable(databaseSchema)),
        (PhysicalSwitchTable.TB_NAME, new PhysicalSwitchTable(databaseSchema)),
        (UcastMacsLocalTable.TB_NAME, new UcastMacsLocalTable(databaseSchema)),
        (UcastMacsRemoteTable.TB_NAME, new UcastMacsRemoteTable(databaseSchema))
    )

    // Table data
    private case class MockData[E <: VtepEntry](data: TrieMap[util.UUID, E])
    private val tableData = Map[String, MockData[_ <: VtepEntry]] (
        (LogicalSwitchTable.TB_NAME, MockData(TrieMap.empty[util.UUID, LogicalSwitch])),
        (McastMacsLocalTable.TB_NAME, MockData(TrieMap.empty[util.UUID, McastMac])),
        (McastMacsRemoteTable.TB_NAME, MockData(TrieMap.empty[util.UUID, McastMac])),
        (PhysicalLocatorSetTable.TB_NAME, MockData(TrieMap.empty[util.UUID, PhysicalLocatorSet])),
        (PhysicalLocatorTable.TB_NAME, MockData(TrieMap.empty[util.UUID, PhysicalLocator])),
        (PhysicalPortTable.TB_NAME, MockData(TrieMap.empty[util.UUID, PhysicalPort])),
        (PhysicalSwitchTable.TB_NAME, MockData(TrieMap.empty[util.UUID, PhysicalSwitch])),
        (UcastMacsLocalTable.TB_NAME, MockData(TrieMap.empty[util.UUID, UcastMac])),
        (UcastMacsRemoteTable.TB_NAME, MockData(TrieMap.empty[util.UUID, UcastMac]))
    )

    if (tableData.keys.size != tables.keys.size) {
        throw new IllegalStateException("TableData and Tables don't have the " +
                                        "same size.  This is a bug")
    }

    // Prime the VTEP with some configuration as the real one

    private val physPortIds = MockOvsdbVtep.physPortNames map { name =>
        val port = new PhysicalPort(UUID.randomUUID(), name, s"$name-desc",
                                    Map.empty, Map.empty, Set.empty)
        tableData.get(PhysicalPortTable.TB_NAME).get
                 .data.asInstanceOf[TrieMap[UUID, PhysicalPort]]
                 .put(port.uuid, port)
        port.uuid
    }

    private val myPhysSwitchId = UUID.randomUUID()
    tableData.get(PhysicalSwitchTable.TB_NAME).get
        .data.asInstanceOf[TrieMap[UUID, PhysicalSwitch]]
        .put(myPhysSwitchId,
             new PhysicalSwitch(myPhysSwitchId, "testy-test", "testy-desc",
                                physPortIds.toSet, Set(mgmtIp), Set(mgmtIp))
        )

    /** publish the monitored tables */
    def monitorRequests = monitorSubject.asObservable()

    /** publish the requested operations */
    def operationRequests = operationSubject.asObservable()

    /** Retrieve a supported database schema */
    def getDbSchema(name: String): DatabaseSchema =
        if (name != MockOvsdbVtep.DB_HARDWARE_VTEP) null else databaseSchema

    /** get an inmutable snapshot of a internal table data */
    def getTable[E <: VtepEntry](t: Table[E]): Map[util.UUID, E] =
        tableData.synchronized[Map[util.UUID, E]] {
            tableData.get(t.getName) match {
                case Some(MockData(data)) =>
                    data.toMap.asInstanceOf[Map[util.UUID, E]]
                case _ => null
            }
        }

    /** Insert or replace an entry in the internal table data */
    def putEntry[E <: VtepEntry](t: Table[E], e: E): Unit =
        tableData.synchronized {
            val update = new TableUpdate[GenericTableSchema]()
            tableData.get(t.getName) match {
                case Some(MockData(data)) =>
                    data.put(e.uuid, e) match {
                        case Some(old) =>
                            update.addRow(toOvsdb(e.uuid),
                                          t.generateRow(old.asInstanceOf[E]),
                                          t.generateRow(e))
                        case None =>
                            update.addRow(toOvsdb(e.uuid), null,
                                          t.generateRow(e))
                    }
                case _ => // do nothing
            }
            // send updates to monitors
            if (update.getRows.size() > 0) {
                tableMonitors.getOrElse(t.getName, List()).foreach(
                    _.update(t.makeTableUpdates(update), databaseSchema))
            }
    }

    /** Remove an entry in the internal table data */
    def removeEntry[E <: VtepEntry](t: Table[E], id: util.UUID): Unit =
        tableData.synchronized {
            val update = new TableUpdate[GenericTableSchema]()
            tableData.get(t.getName) match {
                case Some(MockData(data)) => data.remove(id) match {
                    case Some(old) =>
                        update.addRow(toOvsdb(id),
                                      t.generateRow(old.asInstanceOf[E]), null)
                    case _ => // do nothing
                }
                case _ => // do nothing
            }
            // send updates to monitors
            if (update.getRows.size() > 0) {
                tableMonitors.getOrElse(t.getName, List()).foreach(
                    _.update(t.makeTableUpdates(update), databaseSchema))
            }
        }

    // Monitor support
    private val tableMonitors = TrieMap[String, List[MonitorCallBack]]()
    tableParsers.keys.foreach(tableMonitors.put(_, List.empty))

    private val monitorRegistration = new MonitorRegistration {
        override def register(table: String, columns: util.Set[String],
                              cb: MonitorCallBack): Unit = tables.synchronized {
            monitorSubject.onNext(table)
            tableMonitors.get(table) match {
                case Some(list) => tableMonitors.put(table, cb :: list)
                case _ => // ignore request on non-existing table
            }
        }
    }

    // Transaction support
    private val transactionEngine = new TransactionEngine {
        override def execute(trans: TransactBuilder)
        : ListenableFuture[util.List[OperationResult]] =
            tables.synchronized[ListenableFuture[util.List[OperationResult]]] {
                val results = new util.ArrayList[OperationResult]()
                for (op <- trans.getRequests.toIterable) {
                    operationSubject.onNext(op)
                    results.add(doOperation(op))
                }
                new MockListenableFuture[util.List[OperationResult]](results)
            }
    }

    /** Evaluate if a row satisfies a given condition. Note that not all
      * possible condition definitions are implmemented (only those required
      * for Midonet vtep support) */
    private def filterRow(row: Row[GenericTableSchema], cond: Condition): Boolean =
        row.getColumns.toIterable
            .find(_.getSchema.getName == cond.getColumn) match {
            case None => false
            case Some(col) => cond.getFunction match {
                case OvsdbFunction.EQUALS =>
                    Objects.equals(col.getData, cond.getValue)
                case OvsdbFunction.INCLUDES => col.getData match {
                    case s: util.Set[_] => s.contains(cond.getValue)
                    case _ =>
                        throw new UnsupportedOperationException(
                            "unsupported where filter")
                }
                case _ =>
                    throw new UnsupportedOperationException(
                        "unsupported where filter")
            }
        }

    private def tableParserFor[E <: VtepEntry](op: Operation[_]): Table[E] = {
        tableParsers(op.getTable).asInstanceOf[Table[E]]
    }

    /** Implement the ovsdb database operations.
      * NOTE: calls to this method must be synchronized. */
    private def doOperation[E <: VtepEntry](op: Operation[_])
    : OperationResult = {
        // Collect changes for monitor support
        val update = new TableUpdate[GenericTableSchema]()
        // Collect results for operations response
        val result = new OperationResult
        result.setCount(0)
        result.setError(null)
        result.setDetails(null)

        tableData.get(op.getTable).map(_.asInstanceOf[MockData[E]]) match {
            case Some(MockData(data)) => op match {
                case ins: Insert[_] =>
                    val t = tableParserFor[E](op)
                    val uuidSchema = t.getSchema.column("_uuid",
                                                        classOf[OvsdbUUID])
                    val row = t.generateRow(ins.getRow)
                    val newId = UUID.randomUUID()
                    row.addColumn("_uuid",
                                  new Column(uuidSchema, toOvsdb(newId)))
                    val entry = t.parseEntry(row)
                    if (data.contains(entry.uuid)) {
                        val old = data(entry.uuid)
                        if (old != entry)
                            update.addRow(toOvsdb(entry.uuid),
                                          t.generateRow(old), row)
                    } else {
                        update.addRow(toOvsdb(entry.uuid),
                                      null, row)
                    }
                    data.put(entry.uuid, entry)
                    result.setUuid(Lists.newArrayList(null, entry.uuid.toString))
                    result.setCount(1)
                    result.setRows(Lists.newArrayList(row))
                case del: Delete[_] =>
                    val t = tableParserFor[E](op)
                    val conditions = del.getWhere.toIterable
                    val removed = new util.ArrayList[Row[GenericTableSchema]]()
                    data.values
                        .map(e => (e, t.generateRow(e))).toSeq
                        .filter(p => conditions.forall(filterRow(p._2, _)))
                        .foreach({case (e, r) =>
                            data.remove(e.uuid)
                            removed.add(r)
                            update.addRow(toOvsdb(e.uuid), r, null)
                        })
                    result.setCount(removed.size())
                    result.setRows(removed)
                case upd: Update[_] =>
                    val t = tableParserFor[E](op)
                    val conditions = upd.getWhere.toIterable
                    val updated = new util.ArrayList[Row[GenericTableSchema]]()
                    data.values
                        .map(e => (e, t.generateRow(e))).toSeq
                        .filter(p => conditions.forall(filterRow(p._2, _)))
                        .foreach({case (e, r) =>
                            val newRow = t.updateRow(r, upd.getRow)
                            val newEntry = t.parseEntry(newRow)
                            if (e != newEntry) {
                                data.put(e.uuid, newEntry)
                                updated.add(newRow)
                                update.addRow(toOvsdb(e.uuid), r, newRow)
                            }
                    })
                    result.setCount(updated.size())
                    result.setRows(updated)
                case sel: Select[_] =>
                    val t = tableParserFor[E](op)
                    val conditions = sel.getWhere
                    val out = data.values
                                  .map { e => t.generateRow(e) }
                                  .toSeq
                                  .filter { r =>
                                      conditions.forall(filterRow(r, _))
                                  }
                    result.setCount(out.length)
                    result.setRows(Lists.newArrayList(seqAsJavaList(out)))
            }
            case _ =>
                result.setCount(0)
                result.setError("unknown table")
                result.setDetails("unknown table " + op.getTable)
        }

        // send updates to monitors
        if (update.getRows.size() > 0) {
            tableMonitors.getOrElse(op.getTable, List()).foreach(_.update(
                tableParsers(op.getTable).makeTableUpdates(update),
                databaseSchema))
        }

        result
    }

    private val client = new MockOvsdbClient(databaseSchema, monitorRegistration,
                                             transactionEngine, mgmtIp,
                                             mgmtPort)

    private val stateSubject =
        BehaviorSubject.create[State](ConnectionState.Disconnected)

    override def endPoint: VtepEndPoint = OvsdbTools.endPointFromOvsdbClient(client)
    override def getHandle = Some(OvsdbHandle(client, databaseSchema))
    override def getState: State = stateSubject.toBlocking.first()
    override def close()(implicit ex: ExecutionContext): Future[State] = {
        stateSubject.onNext(ConnectionState.Disconnected)
        Future.successful(ConnectionState.Disconnected)
    }
    override def connect(): Future[State] = {
        stateSubject.onNext(ConnectionState.Connected)
        stateSubject.onNext(ConnectionState.Ready)
        Future.successful(ConnectionState.Ready)
    }
    override def disconnect(): Future[State] = {
        stateSubject.onNext(ConnectionState.Disconnected)
        Future.successful(ConnectionState.Disconnected)
    }
    override def observable: Observable[State] = stateSubject
    def goBroken(): Unit = stateSubject.onNext(ConnectionState.Broken)
    def goConnecting(): Unit = stateSubject.onNext(ConnectionState.Connecting)
    def goReady(): Unit = stateSubject.onNext(ConnectionState.Ready)
}
