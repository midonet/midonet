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

import java.util.concurrent.{Executors, TimeUnit}
import java.util.{Random, UUID}

import scala.concurrent.Await
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FeatureSpec, Matchers}
import rx.Observer

import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.OvsdbVtepBuilder._
import org.midonet.southbound.vtep.mock.InMemoryOvsdbVtep
import org.midonet.southbound.vtep.schema._
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class OvsdbCachedTableTest extends FeatureSpec
                           with Matchers
                           with BeforeAndAfter
                           with BeforeAndAfterAll
                           with MidonetEventually {
    val timeout = 5 seconds

    val random = new Random()
    val vtepDb = OvsdbOperations.DbHardwareVtep
    var vtep: InMemoryOvsdbVtep = _
    var client: OvsdbClient = _
    var db: DatabaseSchema = _
    var exec = Executors.newSingleThreadExecutor()

    class DummyObserver[T] extends Observer[T] {
        override def onCompleted() = {}
        override def onError(e: Throwable) = {}
        override def onNext(v: T) = {}
    }

    override def afterAll() = {
        exec.shutdown()
        if (!exec.awaitTermination(timeout.toMillis, TimeUnit.MILLISECONDS)) {
            exec.shutdownNow()
            exec.awaitTermination(timeout.toMillis, TimeUnit.MILLISECONDS)
        }
    }

    before {
        vtep = new InMemoryOvsdbVtep
        client = vtep.getHandle.get.client
        db = OvsdbOperations.getDbSchema(client, vtepDb)(exec).await(timeout)
    }

    feature("table monitor") {
        scenario("empty table") {
            val t = new PhysicalLocatorTable(db)
            val ct = new OvsdbCachedTable(client, t, exec, exec)

            Await.result(ct.ready, timeout) shouldBe true
            ct.getAll.isEmpty shouldBe true
        }

        scenario("pre-seeded table") {
            val t = new PhysicalLocatorTable(db)
            val data = randPhysLocators(4)
            data.foreach(e => vtep.putEntry(t, e))

            val ct = new OvsdbCachedTable(client, t, exec, exec)

            Await.result(ct.ready, timeout) shouldBe true
            ct.getAll.size shouldBe data.size
            data.forall(e => ct.get(e.uuid).contains(e)) shouldBe true
        }

        scenario("additions on empty table") {
            val t = new PhysicalLocatorTable(db)
            val ct = new OvsdbCachedTable(client, t, exec, exec)

            Await.result(ct.ready, timeout) shouldBe true
            ct.getAll.isEmpty shouldBe true

            val chgs = randPhysLocators(4)
            chgs.foreach(e => vtep.putEntry(t, e))

            eventually {
                ct.getAll.size shouldBe chgs.size
                chgs.forall(e => ct.get(e.uuid).contains(e)) shouldBe true
            }
        }
    }

    feature("operations") {
        scenario("explicit insertion") {
            val t = new PhysicalLocatorTable(db)
            val ct = new OvsdbCachedTable(client, t, exec, exec)

            Await.result(ct.ready, timeout) shouldBe true
            ct.getAll.isEmpty shouldBe true

            val e1 = PhysicalLocator(IPv4Addr.random)
            val id = ct.insert(e1).await(timeout)

            eventually {
                ct.getAll.size shouldBe 1
                ct.get(id) shouldBe Some(e1)
                vtep.getTable(t).get(id) shouldBe Some(e1)
            }
        }
        scenario("background insertion") {
            val t = new PhysicalLocatorTable(db)
            val ct = new OvsdbCachedTable(client, t, exec, exec)

            Await.result(ct.ready, timeout) shouldBe true
            ct.getAll.isEmpty shouldBe true

            val e1 = PhysicalLocator(UUID.randomUUID, IPv4Addr.random)
            vtep.putEntry(t, e1)

            eventually {
                ct.getAll.size shouldBe 1
                ct.get(e1.uuid) shouldBe Some(e1)
                vtep.getTable(t).get(e1.uuid) shouldBe Some(e1)
            }
        }

        scenario("background removal") {
            val t = new PhysicalLocatorTable(db)
            val ct = new OvsdbCachedTable(client, t, exec, exec)

            Await.result(ct.ready, timeout) shouldBe true
            ct.getAll.isEmpty shouldBe true

            val e1 = PhysicalLocator(IPv4Addr.random)

            val id = ct.insert(e1).await(timeout)
            eventually {
                ct.getAll.size shouldBe 1
                ct.get(id).get.dstIp shouldBe e1.dstIp
                vtep.getTable(t).get(id).get.dstIp shouldBe e1.dstIp
            }

            vtep.removeEntry(t, id)
            eventually {
                ct.getAll.isEmpty shouldBe true
                ct.get(id) shouldBe None
                vtep.getTable(t).get(e1.uuid) shouldBe None
            }
        }
    }
}
