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

package org.midonet.brain.services.topology.server

import java.util.concurrent.TimeUnit
import java.util.UUID

import com.google.protobuf.Message

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.{InMemoryStorage, Storage}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.rpc.Commands.{ResponseType, Response}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.reactivex.AwaitableObserver
import org.midonet.util.reactivex.HermitObservable.HermitOversubscribedException

/** Tests the session management of the Topology API service. */
@RunWith(classOf[JUnitRunner])
class SessionInventoryTest extends FeatureSpec
                                   with Matchers
                                   with BeforeAndAfter {
    var store: Storage = _
    var inv: SessionInventory = _

    val WAIT_TIME = Duration.create(5000, TimeUnit.MILLISECONDS)

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
            val testObjects: Set[Message] = Set(
                Chain.getDefaultInstance,
                Host.getDefaultInstance,
                IPAddrGroup.getDefaultInstance,
                Network.getDefaultInstance,
                Port.getDefaultInstance,
                PortGroup.getDefaultInstance,
                Route.getDefaultInstance,
                Router.getDefaultInstance,
                Rule.getDefaultInstance,
                TunnelZone.getDefaultInstance,
                Vtep.getDefaultInstance,
                VtepBinding.getDefaultInstance,
                Dhcp.getDefaultInstance
            )
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
            val collector = new AwaitableObserver[Response](0)
            val subs = session.observable().subscribe(collector)
            subs shouldNot be (null)
            subs.unsubscribe()
        }

        scenario("re-subscribing to an active session")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector1 = new AwaitableObserver[Response](0)
            val collector2 = new AwaitableObserver[Response](0)
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
            val collector1 = new AwaitableObserver[Response](0)
            val collector2 = new AwaitableObserver[Response](0)

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
            val collector1 = new AwaitableObserver[Response](0)
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            Thread.sleep(2000)
            a [SessionInventory.SessionExpirationException] should be
                thrownBy({session.observable().subscribe(collector1)})
        }

        scenario("disconnected session expiration")
        {
            inv = new SessionInventory(store, gracePeriod = 1000)
            val collector1 = new AwaitableObserver[Response](0)
            val collector2 = new AwaitableObserver[Response](0)
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
            val collector1 = new AwaitableObserver[Response](0)
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
            val collector = new AwaitableObserver[Response](1)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()
            session.get(oId, classOf[Network], req)

            // wait for futures to be completed
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 1
            isNAck(events(0), req) shouldBe true
        }

        scenario("get existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](1)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()

            store.create(bridge(oId, "bridge1"))

            session.get(oId, classOf[Network], req)

            // wait for futures to be completed
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 1
            isBridge(events(0), oId, "bridge1") shouldBe true
        }

        scenario("getAll non-existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](1)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            session.getAll(classOf[Network], req)

            // wait for futures to be completed
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 1
            isSnapshot(events(0), Set()) shouldBe true
        }

        scenario("getAll existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](1)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId1 = UUID.randomUUID()
            val oId2 = UUID.randomUUID()

            store.create(bridge(oId1, "bridge1"))
            store.create(bridge(oId2, "bridge2"))

            session.getAll(classOf[Network], req)

            // wait for fures to be completed
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 1
            isSnapshot(events(0), Set(oId1, oId2)) shouldBe true
        }

        scenario("watch non-existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](2)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()
            session.watch(oId, classOf[Network], req)

            // wait for futures to complete
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 2
            events.exists(rsp => isAck(rsp, req)) shouldBe true
            events.exists(rsp => isError(rsp, req)) shouldBe true
        }

        scenario("watch pre-created entities")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](4)
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
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 4
            events.exists(rsp => isAck(rsp, req)) shouldBe true
            events.exists(rsp => isBridge(rsp, oId, "bridge")) shouldBe true
            events.exists(rsp => isBridge(rsp, oId, "bridge-update")) shouldBe true
            events.exists(rsp => isDeletion(rsp, oId)) shouldBe true

        }

        scenario("unwatch non-registered")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](1)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()

            // Note: it should return ack even if not registered
            session.unwatch(oId, classOf[Network], req)

            // wait for futures to complete
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 1
            isAck(events(0), req) shouldBe true
        }

        scenario("unwatch registered")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](4)
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
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 4
            events.exists(rsp => isAck(rsp, req1)) shouldBe true
            events.exists(rsp => isBridge(rsp, oId, "bridge")) shouldBe true
            events.exists(rsp => isBridge(rsp, oId, "bridge-1")) shouldBe true
            events.exists(rsp => isAck(rsp, req2)) shouldBe true
        }

        scenario("watch-all empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](6)
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

            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 6
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
            val collector = new AwaitableObserver[Response](5)
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

            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 5
            events.exists(rsp => isAck(rsp, req)) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update")) shouldBe true
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) shouldBe true
            events.exists(rsp => isBridge(rsp, b3, "bridge3")) shouldBe true
            events.exists(rsp => isDeletion(rsp, b2)) shouldBe true
        }

        scenario("unwatch-all empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](1)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()

            session.unwatchAll(classOf[Network], req)

            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 1
            isAck(events(0), req) shouldBe true
        }

        scenario("unwatch-all non-empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](3)
            val subs = session.observable().subscribe(collector)

            val req1 = UUID.randomUUID()
            val req2 = UUID.randomUUID()

            session.watchAll(classOf[Network], req1)

            val b1 = UUID.randomUUID()
            val b2 = UUID.randomUUID()
            val b3 = UUID.randomUUID()

            store.create(bridge(b1, "bridge1"))
            store.create(bridge(b2, "bridge2"))

            collector.await(WAIT_TIME)
            collector.reset(1)
            session.unwatchAll(classOf[Network], req2)

            store.update(bridge(b1, "bridge1-update"))
            store.create(bridge(b3, "bridge3"))
            store.delete(classOf[Network], UUIDUtil.toProto(b2))

            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 0
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            //events.size shouldBe 4
            events.exists(rsp => isAck(rsp, req1)) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1")) shouldBe true
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) shouldBe true
            events.exists(rsp => isAck(rsp, req2)) shouldBe true
        }

        scenario("terminate empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](1)
            val subs = session.observable().subscribe(collector)

            session.terminate()

            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 1
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 0
        }

        scenario("terminate non-empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](3)
            val subs = session.observable().subscribe(collector)

            val req1 = UUID.randomUUID()
            val oId = UUID.randomUUID()

            store.create(bridge(oId, "bridge"))
            session.watch(oId, classOf[Network], req1)
            store.update(bridge(oId, "bridge-1"))
            collector.await(WAIT_TIME)
            collector.reset(1)
            session.terminate()
            store.update(bridge(oId, "bridge-2"))
            store.delete(classOf[Network], UUIDUtil.toProto(oId))

            // wait for futures to complete
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() shouldBe 1
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size shouldBe 3
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
            val partial = new AwaitableObserver[Response](4)
            val collector = new AwaitableObserver[Response](8)
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

            partial.await(WAIT_TIME)
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
            collector.await(WAIT_TIME)
            subs2.unsubscribe()

            collector.getOnCompletedEvents.isEmpty shouldBe true
            val events =
                collectionAsScalaIterable(partial.getOnNextEvents).toArray[Response] ++
                collectionAsScalaIterable(collector.getOnNextEvents).toArray[Response]

            events.size shouldBe 12
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
            val initial = new AwaitableObserver[Response](4)
            val collector = new AwaitableObserver[Response](4)
            val subs1 = session.observable().subscribe(initial)

            val req = UUID.randomUUID()

            session.watchAll(classOf[Network], req)

            val b1 = UUID.randomUUID()
            val b2 = UUID.randomUUID()

            store.create(bridge(b1, "bridge1"))
            store.create(bridge(b2, "bridge2"))
            store.update(bridge(b1, "bridge1-update1"))

            initial.await(WAIT_TIME)
            subs1.unsubscribe()

            val subs2 = session.observable().subscribe(collector)

            collector.await(WAIT_TIME)
            subs2.unsubscribe()

            collector.getOnCompletedEvents.isEmpty shouldBe true
            val events =
                collectionAsScalaIterable(collector.getOnNextEvents).toArray[Response]

            events.size shouldBe 4
            events.exists(rsp => isAck(rsp, req)) shouldBe true

            events.exists(rsp => isBridge(rsp, b1, "bridge1")) shouldBe true
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) shouldBe true
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update1")) shouldBe true
        }
    }
}
