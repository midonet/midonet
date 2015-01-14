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

import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

import com.google.common.collect.Lists
import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.message.TableUpdate
import org.opendaylight.ovsdb.lib.operations.{Delete, Insert, Operation}
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}
import rx.Observer

import org.midonet.cluster.data.vtep.model.LogicalSwitch
import org.midonet.util.reactivex.TestAwaitableObserver
import org.midonet.vtep.mock.ObservableOvsdbVtep
import org.midonet.vtep.schema._

@RunWith(classOf[JUnitRunner])
class OvsdbToolsTest extends FeatureSpec with Matchers with BeforeAndAfter {
    val timeout = Duration(5000, TimeUnit.MILLISECONDS)

    val VtepDB = OvsdbTools.DB_HARDWARE_VTEP
    var vtep: ObservableOvsdbVtep = _
    var client: OvsdbClient = _

    class DummyObserver[T] extends Observer[T] {
        override def onCompleted() = {}
        override def onError(e: Throwable) = {}
        override def onNext(v: T) = {}
    }

    before {
        vtep = new ObservableOvsdbVtep
        client = vtep.getHandle
    }

    feature("basic operations") {
        scenario("retrieve endpoint") {
            val ep = OvsdbTools.endPointFromOvsdbClient(client)
            ep.mgmtIpString shouldBe "127.0.0.1"
            ep.mgmtPort shouldBe 6632
        }
    }

    feature("schema operations") {
        scenario("retrieve db schema") {
            val result = OvsdbTools.getDbSchema(client, VtepDB)
            val db = result.result(timeout)
            db.getName shouldBe VtepDB
        }
        scenario("obtain logical switch table") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val t = new LogicalSwitchTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain mcast macs local table") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val t = new McastMacsLocalTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain mcast macs remote table") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val t = new McastMacsRemoteTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical locator set table") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val t = new PhysicalLocatorSetTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical locator table") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val t = new PhysicalLocatorTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical port table") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val t = new PhysicalPortTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain physical switch table") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val t = new PhysicalSwitchTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain ucast macs local table") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val t = new UcastMacsLocalTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
        scenario("obtain ucast macs remote table") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val t = new UcastMacsRemoteTable(db)
            t.getColumnSchemas.map(_.getName).contains("_uuid") shouldBe true
        }
    }

    feature("data monitor") {
        scenario("vtep receives monitor requests") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
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

    feature("data operations") {
        scenario("vtep receives single operation transactions") {
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val vtepMonitor = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepMonitor)

            val t = new LogicalSwitchTable(db)
            val ls = LogicalSwitch("lsName", 42, "lsDesc")

            val ins = t.insert(ls)
            val del = t.deleteByName("lsName")

            val r1 = OvsdbTools.singleOp(client, db, ins)
            val r2 = OvsdbTools.singleOp(client, db, del)

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
            val db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
            val vtepMonitor = new TestAwaitableObserver[Operation[_]]()
            vtep.operationRequests.subscribe(vtepMonitor)

            val t = new LogicalSwitchTable(db)
            val ls = LogicalSwitch("lsName", 42, "lsDesc")

            val ins = t.insert(ls)
            val del = t.deleteByName("lsName")

            val r = OvsdbTools.multiOp(client, db, Lists.newArrayList(ins, del))

            vtepMonitor.awaitOnNext(2, timeout) shouldBe true
            val events = vtepMonitor.getOnNextEvents.toSet
            events.collect({
                case op: Insert[_] => "INSERT"
                case op: Delete[_] => "DELETE"
            }) shouldBe Set("INSERT", "DELETE")

            val results = r.result(timeout)
            results.size() shouldBe 2
            results.forall(_.getError == null) shouldBe true
        }
    }
}
