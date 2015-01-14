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
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConversions.{mapAsJavaMap, asScalaSet}

import com.google.common.util.concurrent.ListenableFuture
import org.opendaylight.ovsdb.lib._
import org.opendaylight.ovsdb.lib.message.TransactBuilder
import org.opendaylight.ovsdb.lib.notation.{UUID => OvsdbUUID}
import org.opendaylight.ovsdb.lib.operations.OperationResult
import org.opendaylight.ovsdb.lib.schema.{DatabaseSchema, GenericTableSchema, ColumnSchema}

import org.midonet.vtep.OvsdbTools
import org.midonet.vtep.mock.MockOvsdbClient.{TransactionEngine, MonitorRegistration}
import org.midonet.vtep.mock.MockOvsdbColumn.{mkColumnSchema, mkMapColumnSchema, mkSetColumnSchema}
import org.midonet.vtep.schema._

/**
 * An In-memory mocked vtep for use in tests
 */
class MockOvsdbVtep {
    type MockColumnSchema = ColumnSchema[GenericTableSchema, _]

    private val lsSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("name", mkColumnSchema("name", classOf[String])),
        ("description", mkColumnSchema("description", classOf[String])),
        ("tunnel_key", mkSetColumnSchema("description", classOf[Long]))
    )
    private val uLocalSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("MAC", mkColumnSchema("MAC", classOf[String])),
        ("logical_switch", mkColumnSchema("logical_switch", classOf[OvsdbUUID])),
        ("locator", mkColumnSchema("locator", classOf[OvsdbUUID])),
        ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
    )
    private val uRemoteSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("MAC", mkColumnSchema("MAC", classOf[String])),
        ("logical_switch", mkColumnSchema("logical_switch", classOf[OvsdbUUID])),
        ("locator", mkColumnSchema("locator", classOf[OvsdbUUID])),
        ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
    )
    private val mLocalSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("MAC", mkColumnSchema("MAC", classOf[String])),
        ("logical_switch", mkColumnSchema("logical_switch", classOf[OvsdbUUID])),
        ("locator_set", mkColumnSchema("locator_set", classOf[OvsdbUUID])),
        ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
    )
    private val mRemoteSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("MAC", mkColumnSchema("MAC", classOf[String])),
        ("logical_switch", mkColumnSchema("logical_switch", classOf[OvsdbUUID])),
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
        ("encapsulation_type", mkColumnSchema("encapsulation_type", classOf[String])),
        ("dst_ip", mkColumnSchema("dst_ip", classOf[String])),
        ("bfd", mkMapColumnSchema("bfd", classOf[String], classOf[String])),
        ("bfd_status", mkMapColumnSchema("bfd_status", classOf[String], classOf[String]))
    )
    private val portSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("name", mkColumnSchema("name", classOf[String])),
        ("description", mkColumnSchema("description", classOf[String])),
        ("vlan_bindings", mkMapColumnSchema("vlan_bindings", classOf[JavaLong], classOf[OvsdbUUID])),
        ("vlan_stats", mkMapColumnSchema("vlan_stats", classOf[JavaLong], classOf[OvsdbUUID])),
        ("port_fault_status", mkSetColumnSchema("port_fault_status", classOf[String]))
    )
    private val psSchema = Map[String, MockColumnSchema](
        ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
        ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
        ("name", mkColumnSchema("name", classOf[String])),
        ("ports", mkSetColumnSchema("ports", classOf[OvsdbUUID])),
        ("management_ips", mkSetColumnSchema("management_ips", classOf[String])),
        ("tunnel_ips", mkSetColumnSchema("tunnel_ips", classOf[String])),
        ("tunnels", mkSetColumnSchema("tunnels", classOf[OvsdbUUID])),
        ("switch_fault_status", mkSetColumnSchema("switch_fault_status", classOf[String]))
    )

    private val tables = Map[String, Map[String, MockColumnSchema]](
        (LogicalSwitchTable.TB_NAME, lsSchema),
        (McastMacsLocalTable.TB_NAME, mLocalSchema),
        (McastMacsRemoteTable.TB_NAME, mRemoteSchema),
        (PhysicalLocatorSetTable.TB_NAME, locSetSchema),
        (PhysicalLocatorTable.TB_NAME, locSchema),
        (PhysicalPortTable.TB_NAME, portSchema),
        (PhysicalSwitchTable.TB_NAME, psSchema),
        (UcastMacsLocalTable.TB_NAME, uLocalSchema),
        (UcastMacsRemoteTable.TB_NAME, uRemoteSchema)
    )

    private val databaseSchema = MockOvsdbDatabase.get(
        OvsdbTools.DB_HARDWARE_VTEP,
        tables.map(e => (e._1, new MockOvsdbTable(e._1, e._2))))

    private def getDbSchema(name: String): DatabaseSchema =
        if (name != OvsdbTools.DB_HARDWARE_VTEP) null else databaseSchema

    private case class MonitorContext(table: String, columns: Set[String],
                                      cb: MonitorCallBack)

    private val monitorCallbacks
    : Map[String, ConcurrentLinkedQueue[MonitorContext]] = tables.keys
        .map(t => (t, new ConcurrentLinkedQueue[MonitorContext]())).toMap

    private val monitorRegistration = new MonitorRegistration {
        override def register(table: String, columns: util.Set[String],
                              cb: MonitorCallBack): Unit =
            monitorCallbacks.get(table) match {
                case Some(list) =>
                    list.add(MonitorContext(table, columns.toSet, cb))
            }
    }

    private val transactionEngine = new TransactionEngine {
        override def execute(trans: TransactBuilder)
        : ListenableFuture[util.List[OperationResult]] = ???
    }

    /** Get a client handle */
    def getHandle: OvsdbClient = new MockOvsdbClient(databaseSchema,
                                                     monitorRegistration,
                                                     transactionEngine)
}

