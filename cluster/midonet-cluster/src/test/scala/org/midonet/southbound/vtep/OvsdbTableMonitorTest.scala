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

import java.util.UUID
import java.util.concurrent.{Executor, TimeUnit}

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
import org.midonet.southbound.vtep.OvsdbVtepBuilder._
import org.midonet.southbound.vtep.VtepEntryUpdate.{addition, removal}
import org.midonet.southbound.vtep.mock.InMemoryOvsdbVtep
import org.midonet.southbound.vtep.schema.PhysicalLocatorTable
import org.midonet.util.concurrent.{CallingThreadExecutionContext, _}
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class OvsdbTableMonitorTest extends FeatureSpec
                                    with Matchers
                                    with BeforeAndAfter {
    private val timeout = Duration(5000, TimeUnit.MILLISECONDS)

    private val vtepDb = OvsdbOperations.DbHardwareVtep
    private var vtep: InMemoryOvsdbVtep = _
    private var client: OvsdbClient = _
    private var db: DatabaseSchema = _
    private implicit val executor =
        CallingThreadExecutionContext.asInstanceOf[Executor]

    class DummyObserver[T] extends Observer[T] {
        override def onCompleted() = {}
        override def onError(e: Throwable) = {}
        override def onNext(v: T) = {}
    }

    before {
        vtep = new InMemoryOvsdbVtep
        client = vtep.getHandle.get.client
        db = OvsdbOperations.getDbSchema(client, vtepDb).await(timeout)
    }

    feature("table monitor") {
        scenario("empty table") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]

            mon.observable.subscribe(obs)

            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(VtepTableReady[VtepEntry]())
        }

        scenario("pre-seeded table") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]

            val data = randPhysLocatorAdditions(4)
            data.foreach(u => vtep.putEntry(t, u.newValue))

            mon.observable.subscribe(obs)

            obs.awaitOnNext(data.size + 1, timeout)
            obs.getOnNextEvents.toSet shouldBe
                (data ++ Set(VtepTableReady[VtepEntry]()))
        }

        scenario("additions on empty table") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]

            mon.observable.subscribe(obs)

            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(VtepTableReady[VtepEntry]())

            val chgs = randPhysLocatorAdditions(4)
            chgs.foreach(u => vtep.putEntry(t, u.newValue))

            mon.observable.subscribe(obs)

            obs.awaitOnNext(chgs.size + 1, timeout)
            obs.getOnNextEvents.toSet shouldBe
                (chgs ++ Set(VtepTableReady[VtepEntry]()))
        }

        scenario("additions on pre-seeded table") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]

            val data = randPhysLocatorAdditions(4)
            data.foreach(u => vtep.putEntry(t, u.newValue))

            mon.observable.subscribe(obs)

            obs.awaitOnNext(data.size + 1, timeout)
            obs.getOnNextEvents.toSet shouldBe
                (data ++ Set(VtepTableReady[VtepEntry]()))

            val chgs = randPhysLocatorAdditions(4)
            chgs.foreach(u => vtep.putEntry(t, u.newValue))

            mon.observable.subscribe(obs)

            obs.awaitOnNext(data.size + chgs.size + 1, timeout)
            obs.getOnNextEvents.toSet shouldBe
                (data ++ chgs ++ Set(VtepTableReady[VtepEntry]()))
        }

        scenario("removals on empty table") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]

            mon.observable.subscribe(obs)

            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(VtepTableReady[VtepEntry]())

            val chgs = randPhysLocatorRemovals(4)
            chgs.foreach { u =>
                             vtep.removeEntry(t, u.oldValue.uuid)
            }

            mon.observable.subscribe(obs)

            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe
                Set(VtepTableReady[VtepEntry]())
        }
    }

    feature("operations") {
        scenario("removal") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]
            val e1 = PhysicalLocator(UUID.randomUUID(), IPv4Addr.random)

            mon.observable.subscribe(obs)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(VtepTableReady[VtepEntry]())

            vtep.putEntry(t, e1)
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(
                VtepTableReady[VtepEntry](),
                addition(e1)
            )

            vtep.removeEntry(t, e1.uuid)
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(
                VtepTableReady[VtepEntry](),
                addition(e1),
                removal(e1)
            )
        }
        scenario("update") {
            val t = new PhysicalLocatorTable(db)
            val mon = new OvsdbTableMonitor(client, t)
            val obs = new TestAwaitableObserver[VtepTableUpdate[VtepEntry]]
            val e1 = PhysicalLocator(UUID.randomUUID(), IPv4Addr.random)
            val e2 = new PhysicalLocator(e1.uuid, IPv4Addr.random)

            mon.observable.subscribe(obs)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(VtepTableReady[VtepEntry]())

            vtep.putEntry(t, e1)
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(
                VtepTableReady[VtepEntry](),
                addition(e1)
            )

            vtep.putEntry(t, e2)
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents.toSet shouldBe Set(
                VtepTableReady[VtepEntry](),
                addition(e1),
                VtepEntryUpdate(e1, e2)
            )
        }
    }
}
