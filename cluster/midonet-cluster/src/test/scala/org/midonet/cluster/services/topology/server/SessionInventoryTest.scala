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

package org.midonet.cluster.services.topology.server

import java.util.UUID

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

import com.google.protobuf.Message

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.{InMemoryStorage, Storage}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.rpc.Commands.{ResponseType, Response}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.AwaitableObserver
import org.midonet.util.reactivex.HermitObservable.HermitOversubscribedException

/** Tests the session management of the Topology API service. */
@RunWith(classOf[JUnitRunner])
class SessionInventoryTest extends FeatureSpec
                                   with Matchers
                                   with BeforeAndAfter
                                   with MidonetEventually {
    var store: Storage = _
    var inv: SessionInventory = _

    val WAIT_TIME = 5 seconds
    val LONG_WAIT_TIME = 60 seconds

    def ack(id: UUID): Response = ServerState.makeAck(UUIDUtil.toProto(id))

    def nack(id: UUID): Response = ServerState.makeNAck(UUIDUtil.toProto(id))

    def bridge(id: UUID, name: String) = Network.newBuilder()
        .setId(UUIDUtil.toProto(id))
        .setName(name)
        .build()

    def isAck(rsp: Response, id: UUID) =
        rsp.getType == ResponseType.ACK &&
            UUIDUtil.fromProto(rsp.getReqId) == id

    def isNAck(rsp: Response, id: UUID) =
        rsp.getType == ResponseType.NACK &&
            UUIDUtil.fromProto(rsp.getReqId) == id

    def isError(rsp: Response, id: UUID) =
        rsp.getType == ResponseType.ERROR &&
            UUIDUtil.fromProto(rsp.getReqId) == id

    def isBridge(rsp: Response, id: UUID, name: String) =
        rsp.getType == ResponseType.UPDATE &&
        rsp.hasUpdate && rsp.getUpdate.hasNetwork &&
        UUIDUtil.fromProto(rsp.getUpdate.getNetwork.getId) == id &&
        rsp.getUpdate.getNetwork.getName == name

    def isDeletion(rsp: Response, id: UUID) =
        rsp.getType == ResponseType.DELETION &&
        UUIDUtil.fromProto(rsp.getObjId) == id

    def isSnapshot(rsp: Response, ids: Set[UUID]) =
        rsp.getType == ResponseType.SNAPSHOT &&
        rsp.hasSnapshot &&
            (rsp.getSnapshot.getObjIdsList.toSet.map(UUIDUtil.fromProto) == ids)

    before
    {
        store = new InMemoryStorage
        store.registerClass(classOf[Network])
        store.build()
        inv = new SessionInventory(store)
    }

    feature("responses can handle payload for all defined types") {
        scenario("generate updates") {
            val topologyClasses = classOf[Topology].getClasses.toSet[Class[_]]
                .filterNot(_.getSimpleName.matches("(.*)OrBuilder"))
                .filterNot(_.getSimpleName == "Type")
            val testObjects: Set[Message] = topologyClasses.map(
                _.getDeclaredMethod("getDefaultInstance")
                    .invoke(null)
                    .asInstanceOf[Message])

            testObjects.size shouldBe Type.values().size

            var testTypes: Set[Type] = Set()
            for (obj <- testObjects) {
                val resp = SessionInventory.updateBuilder(obj)
                testTypes = testTypes + resp.getObjType
            }
            testTypes.size shouldBe Type.values().size
        }
    }

    feature("session inventory")
    {
        scenario("claim new session id")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            session shouldNot be (null)
        }

        scenario("claim an existing session id")
        {
            val sId = UUID.randomUUID()
            val previous = inv.claim(sId)
            val session = inv.claim(sId)
            session shouldNot be (null)
            session shouldBe previous
        }

        scenario("claim a different session id")
        {
            val sId1 = UUID.randomUUID()
            val sId2 = UUID.randomUUID()
            val previous = inv.claim(sId1)
            val next = inv.claim(sId2)
            next shouldNot be (null)
            next shouldNot be (previous)
        }
    }

    feature("session subscription limits")
    {
        scenario("subscribing to session for the first time")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response]
            val subs = session.observable().subscribe(collector)
            subs shouldNot be (null)
            subs.unsubscribe()
        }

        scenario("re-subscribing to an active session")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector1 = new TestObserver[Response]
            val collector2 = new TestObserver[Response]
            val subs1 = session.observable().subscribe(collector1)
            subs1 shouldNot be (null)
            an [HermitOversubscribedException] should be
                thrownBy({session.observable().subscribe(collector2)})
            subs1.unsubscribe()
        }

        scenario("re-subscribing after unsubscribing to a session")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector1 = new TestObserver[Response]
            val collector2 = new TestObserver[Response]

            val subs1 = session.observable().subscribe(collector1)
            subs1 shouldNot be (null)
            subs1.unsubscribe()

            val subs2 = session.observable().subscribe(collector2)
            subs2 shouldNot be (null)
            subs2.unsubscribe()
        }

        scenario("abandoned session expiration")
        {
            inv = new SessionInventory(store, gracePeriod = 1000)
            val collector1 = new TestObserver[Response]
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            Thread.sleep(2000)
            a [SessionInventory.SessionExpirationException] should be
                thrownBy({session.observable().subscribe(collector1)})
        }

        scenario("disconnected session expiration")
        {
            inv = new SessionInventory(store, gracePeriod = 1000)
            val collector1 = new TestObserver[Response]()
            val collector2 = new TestObserver[Response]()
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)

            val subs1 = session.observable().subscribe(collector1)
            subs1 shouldNot be (null)
            subs1.unsubscribe()

            Thread.sleep(2000)

            a [SessionInventory.SessionExpirationException] should be
                thrownBy({session.observable().subscribe(collector2)})
        }

        scenario("invalid session recovery")
        {
            val collector1 = new TestObserver[Response]()
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)

            a [NoSuchElementException] should be
                thrownBy({session.observable(1).subscribe(collector1)})
        }
    }

    feature("session commands")
    {
        scenario("get non-existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()
            session.get(oId, classOf[Network], req)

            // wait for futures to be completed
            collector.awaitOnNext(1, WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 1
            isNAck(events(0), req) shouldBe true
        }

        scenario("get existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()

            store.create(bridge(oId, "bridge1"))

            session.get(oId, classOf[Network], req)

            // wait for futures to be completed
            collector.awaitOnNext(1, WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 1
            isBridge(events(0), oId, "bridge1") shouldBe true
        }

        scenario("getAll non-existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            session.getAll(classOf[Network], req)

            // wait for futures to be completed
            collector.awaitOnNext(1, WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 1
            isSnapshot(events(0), Set()) shouldBe true
        }

        scenario("getAll existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId1 = UUID.randomUUID()
            val oId2 = UUID.randomUUID()

            store.create(bridge(oId1, "bridge1"))
            store.create(bridge(oId2, "bridge2"))

            session.getAll(classOf[Network], req)

            // wait for futures to be completed
            collector.awaitOnNext(1, WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 1
            isSnapshot(events(0), Set(oId1, oId2)) shouldBe true
        }

        scenario("watch non-existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()
            session.watch(oId, classOf[Network], req)

            // wait for futures to complete
            collector.awaitOnNext(2, WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 2
            events.exists(rsp => isAck(rsp, req)) shouldBe true
            events.exists(rsp => isError(rsp, req)) shouldBe true
        }

        scenario("watch pre-created entities")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()

            // pre-create bridge
            store.create(bridge(oId, "bridge"))

            // subscribe to changes
            session.watch(oId, classOf[Network], req)

            // update bridge
            store.update(bridge(oId, "bridge-update"))

            // delete bridge
            store.delete(classOf[Network], UUIDUtil.toProto(oId))

            // wait for futures to complete
            collector.awaitOnNext(4, WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 4
            events.exists(rsp => isAck(rsp, req)) shouldBe true
            events.exists(rsp => isBridge(rsp, oId, "bridge")) shouldBe true
            events.exists(rsp => isBridge(rsp, oId, "bridge-update")) shouldBe true
            events.exists(rsp => isDeletion(rsp, oId)) shouldBe true

        }

        scenario("unwatch non-registered")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()

            // Note: it should return ack even if not registered
            session.unwatch(oId, classOf[Network], req)

            // wait for futures to complete
            collector.awaitOnNext(1, WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 1
            isAck(events(0), req) shouldBe true
        }

        scenario("unwatch registered")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req1 = UUID.randomUUID()
            val req2 = UUID.randomUUID()
            val oId = UUID.randomUUID()

            store.create(bridge(oId, "bridge"))
            session.watch(oId, classOf[Network], req1)
            store.update(bridge(oId, "bridge-1"))
            session.unwatch(oId, classOf[Network], req2)
            store.update(bridge(oId, "bridge-2"))
            store.delete(classOf[Network], UUIDUtil.toProto(oId))

            // wait for futures to complete
            collector.awaitOnNext(4, WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 4
            events.exists(rsp => isAck(rsp, req1)) shouldBe true
            events.exists(rsp => isBridge(rsp, oId, "bridge")) shouldBe true
            events.exists(rsp => isBridge(rsp, oId, "bridge-1")) shouldBe true
            events.exists(rsp => isAck(rsp, req2)) shouldBe true
        }

        scenario("watch-all empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()

            session.watchAll(classOf[Network], req)

            val b1 = UUID.randomUUID()
            val b2 = UUID.randomUUID()
            val b3 = UUID.randomUUID()

            store.create(bridge(b1, "bridge1"))
            store.create(bridge(b2, "bridge2"))
            store.update(bridge(b1, "bridge1-update"))
            store.create(bridge(b3, "bridge3"))
            store.delete(classOf[Network], UUIDUtil.toProto(b2))

            collector.awaitOnNext(6, WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 6
            events.exists(rsp => isAck(rsp, req)) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1")) shouldBe true
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update")) shouldBe true
            events.exists(rsp => isBridge(rsp, b3, "bridge3")) shouldBe true
            events.exists(rsp => isDeletion(rsp, b2)) shouldBe true
        }

        scenario("watch-all non-empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()

            val b1 = UUID.randomUUID()
            val b2 = UUID.randomUUID()
            val b3 = UUID.randomUUID()

            store.create(bridge(b1, "bridge1"))
            store.create(bridge(b2, "bridge2"))
            store.update(bridge(b1, "bridge1-update"))

            session.watchAll(classOf[Network], req)

            store.create(bridge(b3, "bridge3"))
            store.delete(classOf[Network], UUIDUtil.toProto(b2))

            collector.awaitOnNext(5, WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 5
            events.exists(rsp => isAck(rsp, req)) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update")) shouldBe true
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) shouldBe true
            events.exists(rsp => isBridge(rsp, b3, "bridge3")) shouldBe true
            events.exists(rsp => isDeletion(rsp, b2)) shouldBe true
        }

        scenario("watch-all large set of objects")
        {
            inv = new SessionInventory(store, backpressureSize = 8192)
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()

            val amount = 8192
            val bridges = for (i <- 1 to amount)
                yield bridge(UUID.randomUUID(), "bridge" + i)
            bridges.foreach(store.create)

            eventually {
                Await.result(store.getAll(
                    classOf[Network]), LONG_WAIT_TIME).length shouldBe amount
            }

            session.watchAll(classOf[Network], req)

            collector.awaitOnNext(amount + 1, LONG_WAIT_TIME) shouldBe true
            subs.unsubscribe()

            collector.isCompleted shouldBe false
            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents)
            events.size shouldBe amount + 1
            events.exists(rsp => isAck(rsp, req)) shouldBe true
            events
                .filterNot(rsp => isAck(rsp, req))
                .map(rsp => rsp.getUpdate.getNetwork.getName).toSet shouldBe
                bridges.map(b => b.getName).toSet
        }

        scenario("watch-all overflow")
        {
            inv = new SessionInventory(store, bufferSize = 8, backpressureSize = 1)
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()

            val amount = 8192
            val bridges = for (i <- 1 to amount)
                yield bridge(UUID.randomUUID(), "bridge" + i)
            bridges.foreach(store.create)

            eventually {
                Await.result(store.getAll(
                    classOf[Network]), LONG_WAIT_TIME).length shouldBe amount
            }

            session.watchAll(classOf[Network], req)

            collector.awaitOnNext(amount + 1, LONG_WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents)
            events.exists(rsp => isAck(rsp, req)) shouldBe true
            if (collector.isCompleted) {
                collector.getOnErrorEvents should have size 1
            } else {
                events.size shouldBe amount + 1
            }
        }

        scenario("unwatch-all empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()

            session.unwatchAll(classOf[Network], req)

            collector.awaitOnNext(1, WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 1
            isAck(events(0), req) shouldBe true
        }

        scenario("unwatch-all non-empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req1 = UUID.randomUUID()
            val req2 = UUID.randomUUID()

            session.watchAll(classOf[Network], req1)

            val b1 = UUID.randomUUID()
            val b2 = UUID.randomUUID()
            val b3 = UUID.randomUUID()

            store.create(bridge(b1, "bridge1"))
            store.create(bridge(b2, "bridge2"))

            collector.awaitOnNext(3, WAIT_TIME)
            session.unwatchAll(classOf[Network], req2)
            collector.awaitOnNext(4, WAIT_TIME)

            store.update(bridge(b1, "bridge1-update"))
            store.create(bridge(b3, "bridge3"))
            store.delete(classOf[Network], UUIDUtil.toProto(b2))

            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collector.getOnNextEvents
            events.length shouldBe 4
            events.exists(rsp => isAck(rsp, req1)) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1")) shouldBe true
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) shouldBe true
            events.exists(rsp => isAck(rsp, req2)) shouldBe true
        }

        scenario("terminate empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            session.terminate()

            collector.awaitCompletion(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 1
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 0
        }

        scenario("terminate non-empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs = session.observable().subscribe(collector)

            val req1 = UUID.randomUUID()
            val oId = UUID.randomUUID()

            store.create(bridge(oId, "bridge"))
            session.watch(oId, classOf[Network], req1)
            store.update(bridge(oId, "bridge-1"))
            collector.awaitOnNext(3, WAIT_TIME) shouldBe true
            session.terminate()
            store.update(bridge(oId, "bridge-2"))
            store.delete(classOf[Network], UUIDUtil.toProto(oId))

            // wait for futures to complete
            collector.awaitCompletion(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 1
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.length shouldBe 3
            events.exists(rsp => isAck(rsp, req1)) shouldBe true
            events.exists(rsp => isBridge(rsp, oId, "bridge")) shouldBe true
            events.exists(rsp => isBridge(rsp, oId, "bridge-1")) shouldBe true
        }
    }
    feature("disconnection management")
    {
        scenario("retrieve events occurring during disconnect")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val partial = new TestObserver[Response] with AwaitableObserver[Response]
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs1 = session.observable().subscribe(partial)

            val req = UUID.randomUUID()

            session.watchAll(classOf[Network], req)

            val b1 = UUID.randomUUID()
            val b2 = UUID.randomUUID()
            val b3 = UUID.randomUUID()
            val b4 = UUID.randomUUID()

            store.create(bridge(b1, "bridge1"))
            store.create(bridge(b2, "bridge2"))
            store.update(bridge(b1, "bridge1-update1"))

            partial.awaitOnNext(4, WAIT_TIME)
            subs1.unsubscribe()

            store.create(bridge(b3, "bridge3"))
            store.update(bridge(b2, "bridge2-update1"))
            store.update(bridge(b1, "bridge1-update2"))

            val lastSeen = partial.getOnNextEvents.last.getSeqno
            val subs2 = session.observable(lastSeen).subscribe(collector)

            store.create(bridge(b4, "bridge4"))
            store.update(bridge(b1, "bridge1-update3"))
            store.update(bridge(b3, "bridge3-update1"))
            store.delete(classOf[Network], UUIDUtil.toProto(b2))

            // await before unsubscribe, so that all events have arrived
            collector.awaitOnNext(8, WAIT_TIME)
            subs2.unsubscribe()

            collector.getOnCompletedEvents.isEmpty shouldBe true
            val events =
                collectionAsScalaIterable(partial.getOnNextEvents).toArray[Response] ++
                collectionAsScalaIterable(collector.getOnNextEvents).toArray[Response]

            events.length shouldBe 12
            events.exists(rsp => isAck(rsp, req)) shouldBe true

            events.exists(rsp => isBridge(rsp, b1, "bridge1")) shouldBe true
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update1")) shouldBe true

            events.exists(rsp => isBridge(rsp, b3, "bridge3")) shouldBe true
            events.exists(rsp => isBridge(rsp, b2, "bridge2-update1")) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update2")) shouldBe true

            events.exists(rsp => isBridge(rsp, b4, "bridge4")) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update3")) shouldBe true
            events.exists(rsp => isBridge(rsp, b3, "bridge3-update1")) shouldBe true
            events.exists(rsp => isDeletion(rsp, b2)) shouldBe true
        }

        scenario("replay events")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val initial = new TestObserver[Response] with AwaitableObserver[Response]
            val collector = new TestObserver[Response] with AwaitableObserver[Response]
            val subs1 = session.observable().subscribe(initial)

            val req = UUID.randomUUID()

            session.watchAll(classOf[Network], req)

            val b1 = UUID.randomUUID()
            val b2 = UUID.randomUUID()

            store.create(bridge(b1, "bridge1"))
            store.create(bridge(b2, "bridge2"))
            store.update(bridge(b1, "bridge1-update1"))

            initial.awaitOnNext(3, WAIT_TIME)
            subs1.unsubscribe()

            val subs2 = session.observable().subscribe(collector)

            collector.awaitOnNext(4, WAIT_TIME)
            subs2.unsubscribe()

            collector.getOnCompletedEvents.isEmpty shouldBe true
            val events =
                collectionAsScalaIterable(collector.getOnNextEvents).toArray[Response]

            events.length shouldBe 4
            events.exists(rsp => isAck(rsp, req)) shouldBe true

            events.exists(rsp => isBridge(rsp, b1, "bridge1")) shouldBe true
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update1")) shouldBe true
        }
    }
}
