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
import java.util.Random

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}
import rx.Observer

import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex.TestAwaitableObserver
import org.midonet.vtep.mock.InMemoryOvsdbVtep
import org.midonet.vtep.schema._

@RunWith(classOf[JUnitRunner])
class OvsdbTableMonitorTest extends FeatureSpec
                                    with Matchers
                                    with BeforeAndAfter {
    val timeout = Duration(5000, TimeUnit.MILLISECONDS)

    val random = new Random()
    val VtepDB = OvsdbTools.DB_HARDWARE_VTEP
    var vtep: InMemoryOvsdbVtep = _
    var client: OvsdbClient = _
    var db: DatabaseSchema = _

    class DummyObserver[T] extends Observer[T] {
        override def onCompleted() = {}
        override def onError(e: Throwable) = {}
        override def onNext(v: T) = {}
    }

    before {
        vtep = new InMemoryOvsdbVtep
        client = vtep.getHandle
        db = OvsdbTools.getDbSchema(client, VtepDB).result(timeout)
    }

    feature("table monitor") {
        scenario("empty table") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t, t.getEntryClass)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]

            mon.observable.subscribe(obs)

            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(VtepTableReady[VtepEntry]())
        }

        scenario("pre-seeded table") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t, t.getEntryClass)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]

            val data = Set(
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random))
            )
            data.foreach(u => vtep.putEntry(t, u.newValue, u.newValue.getClass))

            mon.observable.subscribe(obs)

            obs.awaitOnNext(data.size + 1, timeout)
            obs.getOnNextEvents.toSet shouldBe
                (data ++ Set(VtepTableReady[VtepEntry]()))
        }

        scenario("additions on empty table") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t, t.getEntryClass)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]

            mon.observable.subscribe(obs)

            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(VtepTableReady[VtepEntry]())

            val chgs = Set(
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random))
            )
            chgs.foreach(u => vtep.putEntry(t, u.newValue, u.newValue.getClass))

            mon.observable.subscribe(obs)

            obs.awaitOnNext(chgs.size + 1, timeout)
            obs.getOnNextEvents.toSet shouldBe
                (chgs ++ Set(VtepTableReady[VtepEntry]()))
        }

        scenario("additions on pre-seeded table") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t, t.getEntryClass)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]

            val data = Set(
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random))
            )
            data.foreach(u => vtep.putEntry(t, u.newValue, u.newValue.getClass))

            mon.observable.subscribe(obs)

            obs.awaitOnNext(data.size + 1, timeout)
            obs.getOnNextEvents.toSet shouldBe
                (data ++ Set(VtepTableReady[VtepEntry]()))

            val chgs = Set(
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.addition(PhysicalLocator(IPv4Addr.random))
            )
            chgs.foreach(u => vtep.putEntry(t, u.newValue, u.newValue.getClass))

            mon.observable.subscribe(obs)

            obs.awaitOnNext(data.size + chgs.size + 1, timeout)
            obs.getOnNextEvents.toSet shouldBe
                (data ++ chgs ++ Set(VtepTableReady[VtepEntry]()))
        }

        scenario("removals on empty table") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t, t.getEntryClass)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]

            mon.observable.subscribe(obs)

            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(VtepTableReady[VtepEntry]())

            val chgs = Set(
                VtepEntryUpdate.removal(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.removal(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.removal(PhysicalLocator(IPv4Addr.random)),
                VtepEntryUpdate.removal(PhysicalLocator(IPv4Addr.random))
            )
            chgs.foreach(u => vtep.removeEntry(t, u.oldValue.uuid,
                                               u.oldValue.getClass))

            mon.observable.subscribe(obs)

            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe
                Set(VtepTableReady[VtepEntry]())
        }
    }

    feature("operations") {
        scenario("removal") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t, t.getEntryClass)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]
            val e1 = PhysicalLocator(IPv4Addr.random)

            mon.observable.subscribe(obs)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(VtepTableReady[VtepEntry]())

            vtep.putEntry(t, e1, e1.getClass)
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(
                VtepTableReady[VtepEntry](),
                VtepEntryUpdate.addition(e1)
            )

            vtep.removeEntry(t, e1.uuid, e1.getClass)
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(
                VtepTableReady[VtepEntry](),
                VtepEntryUpdate.addition(e1),
                VtepEntryUpdate.removal(e1)
            )
        }
        scenario("update") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t, t.getEntryClass)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]
            val e1 = PhysicalLocator(IPv4Addr.random)
            val e2 =
                new PhysicalLocator(e1.uuid, IPv4Addr.random, e1.encapsulation)

            mon.observable.subscribe(obs)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(VtepTableReady[VtepEntry]())

            vtep.putEntry(t, e1, e1.getClass)
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(
                VtepTableReady[VtepEntry](),
                VtepEntryUpdate.addition(e1)
            )

            vtep.putEntry(t, e2, e2.getClass)
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(
                VtepTableReady[VtepEntry](),
                VtepEntryUpdate.addition(e1),
                VtepEntryUpdate(e1, e2)
            )
        }
    }
}
