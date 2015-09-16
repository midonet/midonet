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

import java.util.{UUID, Collections}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.message.{MonitorRequest, TableUpdates}
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.opendaylight.ovsdb.lib.{MonitorCallBack, OvsdbClient}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.vtep.model.{PhysicalLocator, VtepEntry}
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.OvsdbUtil.toOvsdb
import org.midonet.southbound.vtep.schema._
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class MockOvsdbVtepTest extends FeatureSpec with Matchers {

    val timeout = Duration(5000, TimeUnit.MILLISECONDS)

    private def getTableMap(db: DatabaseSchema) = Map[String,
                                                      Table[_ <: VtepEntry]](
        (LogicalSwitchTable.TB_NAME, new LogicalSwitchTable(db)),
        (McastMacsLocalTable.TB_NAME, new McastMacsLocalTable(db)),
        (McastMacsRemoteTable.TB_NAME, new McastMacsRemoteTable(db)),
        (PhysicalLocatorSetTable.TB_NAME, new PhysicalLocatorSetTable(db)),
        (PhysicalLocatorTable.TB_NAME, new PhysicalLocatorTable(db)),
        (PhysicalPortTable.TB_NAME, new PhysicalPortTable(db)),
        (PhysicalSwitchTable.TB_NAME, new PhysicalSwitchTable(db)),
        (UcastMacsLocalTable.TB_NAME, new  UcastMacsLocalTable(db)),
        (UcastMacsRemoteTable.TB_NAME, new UcastMacsRemoteTable(db))
    )

    private class TestMonitor(val client: OvsdbClient,
                              val table: Table[_ <: VtepEntry])
        extends TestAwaitableObserver[TableUpdates] {
        private val req = new MonitorRequest()
        req.setTableName(table.getName)
        client.monitor(table.getDbSchema,
                       Collections.singletonList(req),
                       new MonitorCallBack {
                           override def update(updates: TableUpdates,
                                               db: DatabaseSchema): Unit =
                               onNext(updates)
                           override def exception(throwable: Throwable): Unit =
                               onError(throwable)
                       })
    }

    feature("vtep interface") {
        scenario("returns a usable handle") {
            val vtep = new InMemoryOvsdbVtep
            val client = vtep.getHandle.get.client

            client.isActive shouldBe true
            client.getDatabases.get().toSet shouldBe
                Set(MockOvsdbVtep.DB_HARDWARE_VTEP)
            client.getSchema(MockOvsdbVtep.DB_HARDWARE_VTEP).get()
                  .getName shouldBe MockOvsdbVtep.DB_HARDWARE_VTEP
        }
    }
    feature("direct data manipulation") {
        scenario("get database schema") {
            val vtep = new InMemoryOvsdbVtep
            val db = vtep.getDbSchema(MockOvsdbVtep.DB_HARDWARE_VTEP)
            db  shouldNot be (null)
            db.getTables.toSet shouldBe Set(
                LogicalSwitchTable.TB_NAME,
                McastMacsLocalTable.TB_NAME,
                McastMacsRemoteTable.TB_NAME,
                PhysicalLocatorSetTable.TB_NAME,
                PhysicalLocatorTable.TB_NAME,
                PhysicalPortTable.TB_NAME,
                PhysicalSwitchTable.TB_NAME,
                UcastMacsLocalTable.TB_NAME,
                UcastMacsRemoteTable.TB_NAME
            )
        }
        scenario("data tables are accessible") {
            val vtep = new InMemoryOvsdbVtep
            val db = vtep.getDbSchema(MockOvsdbVtep.DB_HARDWARE_VTEP)
            getTableMap(db).values.forall(vtep.getTable(_) != null) shouldBe true
        }
        scenario("data insertion and removal") {
            val vtep = new InMemoryOvsdbVtep
            val db = vtep.getDbSchema(MockOvsdbVtep.DB_HARDWARE_VTEP)
            val t = new PhysicalLocatorTable(db)
            vtep.getTable(t).isEmpty shouldBe true

            val entry = PhysicalLocator(UUID.randomUUID(), IPv4Addr.random)
            vtep.putEntry(t, entry)
            vtep.getTable(t).isEmpty shouldBe false
            vtep.getTable(t).get(entry.uuid) shouldBe Some(entry)

            vtep.removeEntry(t, entry.uuid)
            vtep.getTable(t).isEmpty shouldBe true
        }
    }
    feature("table monitoring") {
        scenario("inserted data") {
            val vtep = new InMemoryOvsdbVtep
            val client = vtep.getHandle.get.client
            val db = vtep.getDbSchema(MockOvsdbVtep.DB_HARDWARE_VTEP)
            val t = new PhysicalLocatorTable(db)
            val monitor = new TestMonitor(client, t)

            val entry = PhysicalLocator(UUID.randomUUID(), IPv4Addr.random)
            vtep.putEntry(t, entry)

            monitor.awaitOnNext(1, timeout) shouldBe true
            val rows = monitor.getOnNextEvents.head
                .getUpdate(t.getSchema)
                .getRows
                .toSeq
            rows.length shouldBe 1
            rows.get(0)._1 shouldBe toOvsdb(entry.uuid)
            rows.get(0)._2.getUuid shouldBe toOvsdb(entry.uuid)
            rows.get(0)._2.getOld shouldBe null
            t.parseEntry(rows.get(0)._2.getNew) shouldBe entry
        }
        scenario("updated data") {
            val vtep = new InMemoryOvsdbVtep
            val client = vtep.getHandle.get.client
            val db = vtep.getDbSchema(MockOvsdbVtep.DB_HARDWARE_VTEP)
            val t = new PhysicalLocatorTable(db)
            val monitor = new TestMonitor(client, t)

            val entry = PhysicalLocator(UUID.randomUUID(), IPv4Addr.random)
            vtep.putEntry(t, entry)

            val updated = PhysicalLocator(entry.uuid, IPv4Addr.random)
            vtep.putEntry(t, updated)

            monitor.awaitOnNext(2, timeout) shouldBe true
            val rows = monitor.getOnNextEvents.last
                .getUpdate(t.getSchema)
                .getRows
                .toSeq
            rows.length shouldBe 1

            rows.get(0)._1 shouldBe toOvsdb(entry.uuid)
            rows.get(0)._2.getUuid shouldBe toOvsdb(entry.uuid)
            t.parseEntry(rows.get(0)._2.getOld) shouldBe entry
            t.parseEntry(rows.get(0)._2.getNew) shouldBe updated
        }
        scenario("removed data") {
            val vtep = new InMemoryOvsdbVtep
            val client = vtep.getHandle.get.client
            val db = vtep.getDbSchema(MockOvsdbVtep.DB_HARDWARE_VTEP)
            val t = new PhysicalLocatorTable(db)
            val monitor = new TestMonitor(client, t)

            val entry = PhysicalLocator(UUID.randomUUID(), IPv4Addr.random)
            vtep.putEntry(t, entry)
            vtep.removeEntry(t, entry.uuid)

            monitor.awaitOnNext(2, timeout) shouldBe true
            val rows = monitor.getOnNextEvents.last
                .getUpdate(t.getSchema)
                .getRows
                .toSeq
            rows.length shouldBe 1

            rows.get(0)._1 shouldBe toOvsdb(entry.uuid)
            rows.get(0)._2.getUuid shouldBe toOvsdb(entry.uuid)
            t.parseEntry(rows.get(0)._2.getOld) shouldBe entry
            rows.get(0)._2.getNew shouldBe null
        }
    }
}
