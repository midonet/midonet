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

package org.midonet.vtep

import java.util.concurrent.{Executor, TimeUnit, TimeoutException}
import java.util.{Random, UUID}

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

import com.google.common.collect.Lists

import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.message.TableUpdate
import org.opendaylight.ovsdb.lib.notation.Row
import org.opendaylight.ovsdb.lib.operations.{Delete, Insert, Operation}
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import rx.Observer

import org.midonet.cluster.data.vtep.model.{LogicalSwitch, UcastMac, VtepMAC}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.CallingThreadExecutionContext
import org.midonet.util.reactivex.TestAwaitableObserver
import org.midonet.vtep.mock.InMemoryOvsdbVtep
import org.midonet.vtep.schema._

@RunWith(classOf[JUnitRunner])
class OvsdbToolsTest extends FeatureSpec with Matchers with BeforeAndAfter {
    val timeout = Duration(5000, TimeUnit.MILLISECONDS)
    val shortTimeout = Duration(500, TimeUnit.MILLISECONDS)

    val random = new Random()
    val vtepDb = OvsdbTools.DB_HARDWARE_VTEP
    var vtep: InMemoryOvsdbVtep = _
    var client: OvsdbClient = _
    val executor = CallingThreadExecutionContext.asInstanceOf[Executor]

    class DummyObserver[T] extends Observer[T] {
        override def onCompleted() = {}
        override def onError(e: Throwable) = {}
        override def onNext(v: T) = {}
    }

    before {
        vtep = new InMemoryOvsdbVtep
        client = vtep.getClient
    }

    def randomUcast = UcastMac(ls = UUID.randomUUID(),
                               mac = VtepMAC(MAC.random()),
                               ip = IPv4Addr.random,
                               loc = UUID.randomUUID())

    feature("basic operations") {
        scenario("retrieve endpoint") {
            val ep = OvsdbTools.endPointFromOvsdbClient(client)
            ep.mgmtIpString shouldBe "127.0.0.1"
            ep.mgmtPort shouldBe 6632
        }
    }

    feature("schema operations") {
        scenario("retrieve db schema") {
            val result = OvsdbTools.getDbSchema(client, vtepDb, executor)
            val db = result.result(timeout)
            db.getName shouldBe vtepDb
        }
        scenario("obtain logical switch table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val t = new LogicalSwitchTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain mcast macs local table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val t = new McastMacsLocalTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain mcast macs remote table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val t = new McastMacsRemoteTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical locator set table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val t = new PhysicalLocatorSetTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical locator table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val t = new PhysicalLocatorTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical port table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val t = new PhysicalPortTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical switch table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val t = new PhysicalSwitchTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain ucast macs local table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val t = new UcastMacsLocalTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain ucast macs remote table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val t = new UcastMacsRemoteTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
    }

    feature("data monitor") {
        scenario("vtep receives monitor requests") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor).result(timeout)
            val vtepMonitor = new TestAwaitableObserver[String]()
            vtep.monitorRequests.subscribe(vtepMonitor)

            val localMcast = new McastMacsLocalTable(db)
            val localUcast = new UcastMacsLocalTable(db)

            // request updates
            val obs1 = OvsdbTools.tableUpdates(client, db, localMcast.getSchema,
                                               localMcast.getColumnSchemas)
            val obs2 = OvsdbTools.tableUpdates(client, db, localUcast.getSchema,
                                               localMcast.getColumnSchemas)

            // monitoring starts on subscription
            obs1.subscribe(new DummyObserver[TableUpdate[GenericTableSchema]])
            obs2.subscribe(new DummyObserver[TableUpdate[GenericTableSchema]])

            vtepMonitor.awaitOnNext(2, timeout) shouldBe true
            vtepMonitor.getOnNextEvents.toSet shouldBe
                Set(McastMacsLocalTable.TB_NAME, UcastMacsLocalTable.TB_NAME)
        }
    }

    feature("data transactions") {
        scenario("vtep receives single operation transactions") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val vtepMonitor = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepMonitor)

            val t = new LogicalSwitchTable(db)
            val ls = LogicalSwitch("lsName", 42, "lsDesc")

            val ins = t.insert(ls)
            val del = t.deleteByName("lsName")

            val r1 = OvsdbTools.singleOp(client, db, ins, executor)
            val r2 = OvsdbTools.singleOp(client, db, del, executor)

            vtepMonitor.awaitOnNext(2, timeout) shouldBe true
            val events = vtepMonitor.getOnNextEvents.toSet
            events.collect({
                case op: Insert[_] => "INSERT"
                case op: Delete[_] => "DELETE"
            }) shouldBe Set("INSERT", "DELETE")

            r1.result(timeout).getError shouldBe null
            r2.result(timeout).getError shouldBe null
        }
        scenario("vtep receives multi operation transactions") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new LogicalSwitchTable(db)
            val ls = LogicalSwitch("lsName", 42, "lsDesc")

            val ins: Table.OvsdbOperation = t.insert(ls)
            val del: Table.OvsdbOperation = t.deleteByName("lsName")

            val r = OvsdbTools.multiOp(client, db, Lists.newArrayList(ins, del),
                                       executor)

            vtepOperations.awaitOnNext(2, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events.collect({
                case op: Insert[_] => "INSERT"
                case op: Delete[_] => "DELETE"
            }) shouldBe Set("INSERT", "DELETE")

            val results = r.result(timeout)
            results should have size 2
            results.forall(_.getError == null) shouldBe true
        }
    }
    feature("insert") {
        scenario("vtep processes an insert") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new UcastMacsLocalTable(db)
            val mac = randomUcast

            val op = OvsdbTools.insert(client, t, t.insert(mac), executor)
            op.result(timeout) shouldBe mac.uuid

            vtepOperations.awaitOnNext(1, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events.map(_.getOp) shouldBe Set("insert")
        }
    }
    feature("delete") {
        scenario("vtep processes a delete") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new UcastMacsLocalTable(db)
            val mac = randomUcast

            val op = OvsdbTools.insert(client, t, t.insert(mac), executor)
            op.result(timeout) shouldBe mac.uuid

            val del = OvsdbTools.delete(client, t, t.delete(mac), executor)
            del.result(timeout) shouldBe 1

            vtepOperations.awaitOnNext(2, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events.map(_.getOp) shouldBe Set("insert", "delete")
        }
    }

    feature("query") {
        scenario("query on an empty table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new UcastMacsLocalTable(db)
            val op = OvsdbTools.tableEntries(client, db, t.getSchema,
                                             t.getColumnSchemas, null, executor)

            val rows = op.result(timeout)
            rows should have size 0

            vtepOperations.awaitOnNext(1, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events should have size 1
        }
        scenario("query on a table with a single row") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new UcastMacsLocalTable(db)
            val mac = randomUcast

            val ins = OvsdbTools.insert(client, t, t.insert(mac), executor)
            ins.result(timeout) shouldBe mac.uuid

            val op = OvsdbTools.tableEntries(client, db, t.getSchema,
                                             t.getColumnSchemas, null, executor)

            val rows = op.result(timeout)
            rows should have size 1
            t.parseEntry(rows.toSet.head, classOf[UcastMac]) shouldBe mac

            vtepOperations.awaitOnNext(2, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events should have size 2
        }
        scenario("query on a table with multiple rows") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new UcastMacsLocalTable(db)
            val mac1 = randomUcast
            val mac2 = randomUcast

            val ins1 = OvsdbTools.insert(client, t, t.insert(mac1), executor)
            val ins2 = OvsdbTools.insert(client, t, t.insert(mac2), executor)
            ins1.result(timeout) shouldBe mac1.uuid
            ins2.result(timeout) shouldBe mac2.uuid

            val op = OvsdbTools.tableEntries(client, db, t.getSchema,
                                             t.getColumnSchemas, null, executor)

            val rows = op.result(timeout)
            rows should have size 2
            rows.toSet[Row[GenericTableSchema]]
                .map(t.parseEntry(_, classOf[UcastMac])) shouldBe
                Set(mac1, mac2)

            vtepOperations.awaitOnNext(3, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events should have size 3
        }
    }
    feature("monitor") {
        scenario("subscription on an empty table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val vtepMonitor = new TestAwaitableObserver[String]()
            vtep.monitorRequests.subscribe(vtepMonitor)

            val t = new UcastMacsLocalTable(db)
            val stream = OvsdbTools.tableUpdates(client, db, t.getSchema,
                                                 t.getColumnSchemas)

            val monitor = new TestAwaitableObserver[TableUpdate[GenericTableSchema]]()
            stream.subscribe(monitor)

            vtepMonitor.awaitOnNext(1, timeout) shouldBe true
            monitor.getOnNextEvents.length shouldBe 0

            // insert
            val mac1 = randomUcast
            val ins1 = OvsdbTools.insert(client, t, t.insert(mac1), executor)
            ins1.result(timeout) shouldBe mac1.uuid

            monitor.awaitOnNext(1, timeout) shouldBe true
            val data = monitor.getOnNextEvents.toSet
            data should have size 1
            data.head.getRows should have size 1
            val update = data.head.getRows.values.iterator().next()
            update.getUuid.toString shouldBe mac1.uuid.toString
            update.getOld shouldBe null
            t.parseEntry(update.getNew, classOf[UcastMac]) shouldBe mac1
        }
        scenario("subscription on a non-empty table") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val vtepMonitor = new TestAwaitableObserver[String]()
            vtep.monitorRequests.subscribe(vtepMonitor)

            val t = new UcastMacsLocalTable(db)
            val mac1 = randomUcast
            val ins1 = OvsdbTools.insert(client, t, t.insert(mac1), executor)
            ins1.result(timeout) shouldBe mac1.uuid


            val stream = OvsdbTools.tableUpdates(client, db, t.getSchema,
                                                 t.getColumnSchemas)

            val monitor = new TestAwaitableObserver[TableUpdate[GenericTableSchema]]()
            stream.subscribe(monitor)

            vtepMonitor.awaitOnNext(1, timeout) shouldBe true
            // no events received
            monitor.getOnNextEvents.length shouldBe 0
            a [TimeoutException] shouldBe
                thrownBy(monitor.awaitOnNext(1, shortTimeout))
        }
        scenario("row replacement") {
            val db = OvsdbTools.getDbSchema(client, vtepDb, executor)
                               .result(timeout)
            val vtepMonitor = new TestAwaitableObserver[String]()
            vtep.monitorRequests.subscribe(vtepMonitor)

            val t = new LogicalSwitchTable(db)
            val stream = OvsdbTools.tableUpdates(client, db, t.getSchema,
                                                 t.getColumnSchemas)

            val monitor = new TestAwaitableObserver[TableUpdate[GenericTableSchema]]()
            stream.subscribe(monitor)

            vtepMonitor.awaitOnNext(1, timeout) shouldBe true
            monitor.getOnNextEvents.length shouldBe 0

            // insert
            val ls1 = LogicalSwitch(id = UUID.randomUUID(),
                                    name = "lsName",
                                    vni = random.nextInt(4096),
                                    desc = "lsDesc")
            val ins1 = OvsdbTools.insert(client, t, t.insert(ls1), executor)
            ins1.result(timeout) shouldBe ls1.uuid

            monitor.awaitOnNext(1, timeout) shouldBe true

            val ls2 = LogicalSwitch(id = ls1.uuid,
                                    name = "lsName",
                                    vni = random.nextInt(4096),
                                    desc = "lsChanged")
            val ins2 = OvsdbTools.insert(client, t, t.insert(ls2), executor)
            ins2.result(timeout) shouldBe ls2.uuid

            ls1.uuid shouldBe ls2.uuid

            monitor.awaitOnNext(2, timeout) shouldBe true

            val data = monitor.getOnNextEvents.toSeq
            data.length shouldBe 2
            val update1 = data.get(0).getRows.values().head
            val update2 = data.get(1).getRows.values().head

            update1.getUuid.toString shouldBe ls1.uuid.toString
            update1.getOld shouldBe null
            t.parseEntry(update1.getNew, classOf[LogicalSwitch]) shouldBe ls1

            update2.getUuid.toString shouldBe ls1.uuid.toString
            t.parseEntry(update2.getOld, classOf[LogicalSwitch]) shouldBe ls1
            t.parseEntry(update2.getNew, classOf[LogicalSwitch]) shouldBe ls2
        }
    }
}
