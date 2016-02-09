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

package org.midonet.southbound.vtep

import java.util.concurrent.{Executor, TimeoutException}
import java.util.{Random, UUID}

import scala.collection.JavaConversions._
import scala.concurrent.duration._

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
import org.midonet.southbound.vtep.OvsdbOperations._
import org.midonet.southbound.vtep.mock.InMemoryOvsdbVtep
import org.midonet.southbound.vtep.schema._
import org.midonet.util.concurrent.{CallingThreadExecutionContext, _}
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class OvsdbOperationsTest extends FeatureSpec with Matchers with BeforeAndAfter {

    private val timeout = 5 seconds
    private val shortTimeout = 500 millis

    private val random = new Random()
    private val vtepDb = DbHardwareVtep
    private var vtep: InMemoryOvsdbVtep = _
    private var client: OvsdbClient = _
    private implicit val executor =
        CallingThreadExecutionContext.asInstanceOf[Executor]

    class DummyObserver[T] extends Observer[T] {
        override def onCompleted() = {}
        override def onError(e: Throwable) = {}
        override def onNext(v: T) = {}
    }

    var mgmtIp: IPv4Addr = _
    var mgmtPort: Int = _

    before {
        mgmtIp = IPv4Addr.random
        mgmtPort = 6632
        vtep = new InMemoryOvsdbVtep(mgmtIp, mgmtPort)
        client = vtep.getHandle.get.client
    }

    def randomUcast = UcastMac(ls = UUID.randomUUID(),
                               mac = VtepMAC(MAC.random()),
                               ip = IPv4Addr.random,
                               loc = UUID.randomUUID().toString)

    feature("basic operations") {
        scenario("retrieve endpoint") {
            val ep = OvsdbTools.endPointFromOvsdbClient(client)
            ep.mgmtIp shouldBe mgmtIp
            ep.mgmtPort shouldBe mgmtPort
        }
    }

    feature("schema operations") {
        scenario("retrieve db schema") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            db.getName shouldBe vtepDb
        }
        scenario("obtain logical switch table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val t = new LogicalSwitchTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain mcast macs local table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val t = new McastMacsLocalTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain mcast macs remote table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val t = new McastMacsRemoteTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical locator set table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val t = new PhysicalLocatorSetTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical locator table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val t = new PhysicalLocatorTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical port table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val t = new PhysicalPortTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical switch table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val t = new PhysicalSwitchTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain ucast macs local table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val t = new UcastMacsLocalTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain ucast macs remote table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val t = new UcastMacsRemoteTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
    }

    feature("data monitor") {
        scenario("vtep receives monitor requests") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val vtepMonitor = new TestAwaitableObserver[String]()
            vtep.monitorRequests.subscribe(vtepMonitor)

            val localMcast = new McastMacsLocalTable(db)
            val localUcast = new UcastMacsLocalTable(db)

            // request updates
            val obs1 = tableUpdates(client, db, localMcast.getSchema,
                                               localMcast.getColumnSchemas)
            val obs2 = tableUpdates(client, db, localUcast.getSchema,
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
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val vtepMonitor = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepMonitor)

            val t = new LogicalSwitchTable(db)
            val ls = LogicalSwitch("lsName", 42, "lsDesc")

            val ins = t.insert(ls, null)
            val del = t.deleteById(UUID.randomUUID()) // TODO: amend this

            val r1 = singleOp(client, db, ins)
            val r2 = singleOp(client, db, del)

            vtepMonitor.awaitOnNext(2, timeout) shouldBe true
            val events = vtepMonitor.getOnNextEvents.toSet
            events.collect({
                case op: Insert[_] => "INSERT"
                case op: Delete[_] => "DELETE"
            }) shouldBe Set("INSERT", "DELETE")

            r1.await(timeout).getError shouldBe null
            r2.await(timeout).getError shouldBe null
        }
        scenario("vtep receives multi operation transactions") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new LogicalSwitchTable(db)
            val ls = LogicalSwitch("lsName", 42, "lsDesc")

            val ins: Table.OvsdbOperation = t.insert(ls, null)
            // TODO: ammend this
            val del: Table.OvsdbOperation = t.deleteById(UUID.randomUUID())

            val r = multiOp(client, db,
                                            Lists.newArrayList(ins, del))

            vtepOperations.awaitOnNext(2, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events.collect({
                case op: Insert[_] => "INSERT"
                case op: Delete[_] => "DELETE"
            }) shouldBe Set("INSERT", "DELETE")

            val results = r.await(timeout)
            results should have size 2
            results.forall(_.getError == null) shouldBe true
        }
    }
    feature("insert") {
        scenario("vtep processes an insert") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new UcastMacsLocalTable(db)
            val mac = randomUcast

            val op = singleOp(client, t.getDbSchema, t.insert(mac, null))
                .await(timeout)
            val insertedMac = new UcastMacsLocalTable(db)
            insertedMac.parseEntry(op.getRows.head)

            vtepOperations.awaitOnNext(1, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events.map(_.getOp) shouldBe Set("insert")
        }
    }
    feature("delete") {
        scenario("vtep processes a delete") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new UcastMacsLocalTable(db)
            val mac = randomUcast

            val op = singleOp(client, t.getDbSchema, t.insert(mac, null))
                .await(timeout)
            op.getUuid shouldNot be (null)

            val del = singleOp(client, t.getDbSchema, t.delete(mac))
            del.await(timeout).getCount shouldBe 1

            vtepOperations.awaitOnNext(2, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events.map(_.getOp) shouldBe Set("insert", "delete")
        }
    }

    feature("query") {
        scenario("query on an empty table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new UcastMacsLocalTable(db)
            val op = tableEntries(client, db, t.getSchema, t.getColumnSchemas, null)

            op.await(timeout) should have size 0

            vtepOperations.awaitOnNext(1, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events should have size 1
        }
        scenario("query on a table with a single row") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new UcastMacsLocalTable(db)
            val mac = randomUcast

            val ins = singleOp(client, t.getDbSchema, t.insert(mac, null))
            ins.await(timeout) shouldNot be (null)

            val op = tableEntries(client, db, t.getSchema, t.getColumnSchemas, null)

            val rows = op.await(timeout)
            rows should have size 1
            t.parseEntry(rows.toSet.head) shouldBe mac

            vtepOperations.awaitOnNext(2, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events should have size 2
        }
        scenario("query on a table with multiple rows") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val vtepOperations = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepOperations)

            val t = new UcastMacsLocalTable(db)
            val mac1 = randomUcast
            val mac2 = randomUcast

            val ins1 = singleOp(client, t.getDbSchema, t.insert(mac1, null))
            val ins2 = singleOp(client, t.getDbSchema, t.insert(mac2, null))
            ins1.await(timeout) shouldNot be (null)
            ins2.await(timeout) shouldNot be (null)

            val op = tableEntries(client, db, t.getSchema,
                                                  t.getColumnSchemas, null)

            val rows = op.await(timeout)
            rows should have size 2
            rows.toSet[Row[GenericTableSchema]]
                .map(t.parseEntry) shouldBe
                Set(mac1, mac2)

            vtepOperations.awaitOnNext(3, timeout) shouldBe true
            val events = vtepOperations.getOnNextEvents.toSet
            events should have size 3
        }
    }
    feature("monitor") {
        scenario("subscription on an empty table") {
            val db = getDbSchema(client, vtepDb)
                                    .await(timeout)
            val vtepMonitor = new TestAwaitableObserver[String]()
            vtep.monitorRequests.subscribe(vtepMonitor)

            val t = new UcastMacsLocalTable(db)
            val stream = tableUpdates(client, db, t.getSchema,
                                                      t.getColumnSchemas)

            val monitor = new TestAwaitableObserver[TableUpdate[GenericTableSchema]]()
            stream.subscribe(monitor)

            vtepMonitor.awaitOnNext(1, timeout) shouldBe true
            monitor.getOnNextEvents.length shouldBe 0

            // insert
            val mac1 = randomUcast
            val ins1 = singleOp(client, t.getDbSchema, t.insert(mac1, null))
            ins1.await(timeout) shouldNot be (null)

            monitor.awaitOnNext(1, timeout) shouldBe true
            val data = monitor.getOnNextEvents.toSet
            data should have size 1
            data.head.getRows should have size 1
            val update = data.head.getRows.values.iterator().next()
            update.getUuid shouldNot be (null)
            update.getOld shouldBe null
            t.parseEntry(update.getNew) shouldBe mac1
        }
        scenario("subscription on a non-empty table") {
            val db = getDbSchema(client, vtepDb).await(timeout)
            val vtepMonitor = new TestAwaitableObserver[String]()
            vtep.monitorRequests.subscribe(vtepMonitor)

            val t = new UcastMacsLocalTable(db)
            val mac1 = randomUcast
            val ins1 = singleOp(client, t.getDbSchema, t.insert(mac1, null))
            ins1.await(timeout) shouldNot be (null)


            val stream = tableUpdates(client, db, t.getSchema, t.getColumnSchemas)

            val monitor = new TestAwaitableObserver[TableUpdate[GenericTableSchema]]()
            stream.subscribe(monitor)

            vtepMonitor.awaitOnNext(1, timeout) shouldBe true
            // no events received
            monitor.getOnNextEvents.length shouldBe 0
            a [TimeoutException] shouldBe
                thrownBy(monitor.awaitOnNext(1, shortTimeout))
        }
    }
}
