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

package org.midonet.vtep.mock

import java.lang.{Long => JavaLong}
import java.util
import java.util.{Objects, Collections}

import scala.collection.JavaConversions.{mapAsJavaMap, seqAsJavaList, iterableAsScalaIterable}
import scala.collection.concurrent.TrieMap

import com.google.common.collect.{Maps, Lists}
import com.google.common.util.concurrent.ListenableFuture
import org.opendaylight.ovsdb.lib._
import org.opendaylight.ovsdb.lib.message.{TableUpdates, TableUpdate, TransactBuilder}
import org.opendaylight.ovsdb.lib.notation.{UUID => OvsdbUUID, Condition, Function => OvsdbFunction, Row}
import org.opendaylight.ovsdb.lib.operations._
import org.opendaylight.ovsdb.lib.schema.{DatabaseSchema, GenericTableSchema, ColumnSchema}
import rx.subjects.PublishSubject

import org.midonet.cluster.data.vtep.model._
import org.midonet.vtep.OvsdbTools
import org.midonet.vtep.OvsdbTranslator.toOvsdb
import org.midonet.vtep.mock.MockOvsdbClient.{TransactionEngine, MonitorRegistration}
import org.midonet.vtep.mock.MockOvsdbColumn.{mkColumnSchema, mkMapColumnSchema, mkSetColumnSchema}
import org.midonet.vtep.schema._

/**
 * An In-memory mocked vtep for use in tests
 */
abstract class MockOvsdbVtep {

    type MockColumnSchema = ColumnSchema[GenericTableSchema, _]
    type MockTableSchema = Map[String, MockColumnSchema]

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

    protected case class MockTable(data: TrieMap[util.UUID, VtepEntry],
                                   schema: MockTableSchema,
                                   clazz: Class[_ <: VtepEntry])

    protected val tables = Map[String, MockTable](
        (LogicalSwitchTable.TB_NAME,
            MockTable(TrieMap(), lsSchema, classOf[LogicalSwitch])),
        (McastMacsLocalTable.TB_NAME,
            MockTable(TrieMap(), mLocalSchema, classOf[McastMac])),
        (McastMacsRemoteTable.TB_NAME,
            MockTable(TrieMap(), mRemoteSchema, classOf[McastMac])),
        (PhysicalLocatorSetTable.TB_NAME,
            MockTable(TrieMap(), locSetSchema, classOf[PhysicalLocatorSet])),
        (PhysicalLocatorTable.TB_NAME,
            MockTable(TrieMap(), locSchema, classOf[PhysicalLocator])),
        (PhysicalPortTable.TB_NAME,
            MockTable(TrieMap(), portSchema, classOf[PhysicalPort])),
        (PhysicalSwitchTable.TB_NAME,
            MockTable(TrieMap(), psSchema, classOf[PhysicalSwitch])),
        (UcastMacsLocalTable.TB_NAME,
            MockTable(TrieMap(), uLocalSchema, classOf[UcastMac])),
        (UcastMacsRemoteTable.TB_NAME,
            MockTable(TrieMap(), uRemoteSchema, classOf[UcastMac]))
    )

    protected val databaseSchema = MockOvsdbDatabase.get(
        OvsdbTools.DB_HARDWARE_VTEP,
        tables.map(e => (e._1, new MockOvsdbTable(e._1, e._2.schema))))

    protected def getDbSchema(name: String): DatabaseSchema =
        if (name != OvsdbTools.DB_HARDWARE_VTEP) null else databaseSchema

    /** Get a client handle */
    def getHandle: OvsdbClient

    /** get a snapshot of a internal data table */
    def getTable(t: Table): Map[util.UUID, _ <: VtepEntry] =
        tables.synchronized[Map[util.UUID, _ <: VtepEntry]] {
            tables.get(t.getSchema.getName) match {
                case None => null
                case Some(MockTable(data, _, _)) =>
                    data.toMap
            }
        }

}

class InMemoryOvsdbVtep extends MockOvsdbVtep {
    private val monitorSubject = PublishSubject.create[String]()
    private val operationSubject = PublishSubject.create[Operation[_]]()

    /** publish the monitored tables */
    def monitorRequests = monitorSubject.asObservable()

    /** publish the requested operations */
    def operationRequests = operationSubject.asObservable()

    override def getHandle: OvsdbClient =
        new MockOvsdbClient(databaseSchema, monitorRegistration,
                            transactionEngine)

    private val tableParsers = Map[String, Table](
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

    private val tableMonitors = TrieMap[String, List[MonitorCallBack]]()
    tableParsers.keys.foreach(tableMonitors.put(_, List()))

    private val monitorRegistration = new MonitorRegistration {
        override def register(table: String, columns: util.Set[String],
                              cb: MonitorCallBack): Unit = tables.synchronized {
            monitorSubject.onNext(table)
            tableMonitors.get(table) match {
                case Some(list) => tableMonitors.put(table, cb :: list)
                case None =>
            }
        }
    }

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

    private def filterRow(row: Row[GenericTableSchema], cond: Condition): Boolean = {
        row.getColumns.toIterable.find(_.getSchema.getName == cond.getColumn) match {
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
    }

    // Note: calls to this method must be synchronized
    private def doOperation(op: Operation[_]): OperationResult = {
        val update = new TableUpdate[GenericTableSchema]()
        val result = new OperationResult
        result.setCount(0)
        result.setError(null)
        result.setDetails(null)
        tables.get(op.getTable) match {
            case None =>
                result.setCount(0)
                result.setError("unknown table")
                result.setDetails("unknown table " + op.getTable)
            case Some(MockTable(data, schema, clazz)) => op match {
                case ins: Insert[GenericTableSchema] =>
                    val t = tableParsers(op.getTable)
                    val row = t.generateRow(ins.getRow)
                    val entry = t.parseEntry(row, clazz)
                    if (data.contains(entry.uuid)) {
                        val old = data(entry.uuid)
                        if (old != entry)
                            update.addRow(toOvsdb(entry.uuid),
                                          t.generateRow(old, clazz), row)
                    } else {
                        update.addRow(toOvsdb(entry.uuid),
                                      null, row)
                    }
                    data.put(entry.uuid, entry)
                    result.setCount(1)
                    result.setRows(Lists.newArrayList(row))
                case del: Delete[GenericTableSchema] =>
                case upd: Update[GenericTableSchema] =>
                case sel: Select[GenericTableSchema] =>
                    // inefficient, but kept simple for testing purposes
                    // Note that transactions are synchronized
                    val t = tableParsers(op.getTable)
                    val conditions = sel.getWhere.toIterable
                    val out = data.values
                        .map(t.generateRow(_, clazz)).toSeq
                        .filter(r => conditions.forall(filterRow(r, _)))
                    val uuids = out.map(t.parseEntry(_, clazz).uuid.toString)
                    result.setCount(out.length)
                    result.setRows(Lists.newArrayList(seqAsJavaList(out)))
            }
        }

        // send updates to monitors
        if (update.getRows.size() > 0) {
            tableMonitors.getOrElse(op.getTable, List()).foreach(_.update(
                OvsdbTools.newTableUpdates(op.getTable, update),
                databaseSchema))
        }

        result
    }
}
