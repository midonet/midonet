/*
 * Copyright 2014 Midokura SARL
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

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.{InMemoryStorage, Storage}
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.rpc.Commands.Response
import org.midonet.cluster.rpc.Commands.Response.{Ack, NAck}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.eventloop.CallingThreadReactor
import org.midonet.util.reactivex.AwaitableObserver
import org.midonet.util.reactivex.HermitObservable.HermitOversubscribedException

/** Tests the Topology API protocol implementation. */
@RunWith(classOf[JUnitRunner])
class SessionInventoryTest extends FeatureSpec
                                   with Matchers
                                   with BeforeAndAfter {
    var store: Storage = _
    var inv: SessionInventory = _
    var originalTimeout = SessionInventory.SESSION_TIMEOUT

    val WAIT_TIME = Duration.create(5000, TimeUnit.MILLISECONDS)

    def ack(id: UUID): Response = Response.newBuilder()
        .setAck(Ack.newBuilder().setReqId(UUIDUtil.toProto(id)).build())
        .build()

    def nack(id: UUID): Response = Response.newBuilder()
        .setNack(NAck.newBuilder().setReqId(UUIDUtil.toProto(id)).build())
        .build()

    def bridge(id: UUID, name: String) = Network.newBuilder()
        .setId(UUIDUtil.toProto(id))
        .setName(name)
        .build()

    def isAck(rsp: Response, id: UUID) =
        rsp.hasAck && UUIDUtil.fromProto(rsp.getAck.getReqId) == id

    def isNAck(rsp: Response, id: UUID) =
        rsp.hasNack && UUIDUtil.fromProto(rsp.getNack.getReqId) == id

    def isBridge(rsp: Response, id: UUID, name: String) =
        rsp.hasUpdate && rsp.getUpdate.hasNetwork &&
        UUIDUtil.fromProto(rsp.getUpdate.getNetwork.getId) == id &&
        rsp.getUpdate.getNetwork.getName == name

    def isDeletion(rsp: Response, id: UUID) =
        rsp.hasDeletion && rsp.getDeletion.getId.hasUuid &&
        UUIDUtil.fromProto(rsp.getDeletion.getId.getUuid) == id

    before
    {
        SessionInventory.SESSION_TIMEOUT = originalTimeout
        store = new InMemoryStorage(new CallingThreadReactor)
        store.registerClass(classOf[Network])
        store.build()
        inv = new SessionInventory(store)
    }

    feature("session inventory")
    {
        scenario("claim new session id")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            session should not be (null)
        }

        scenario("claim an existing session id")
        {
            val sId = UUID.randomUUID()
            val previous = inv.claim(sId)
            val session = inv.claim(sId)
            session should not be (null)
            session should be (previous)
        }

        scenario("claim a different session id")
        {
            val sId1 = UUID.randomUUID()
            val sId2 = UUID.randomUUID()
            val previous = inv.claim(sId1)
            val next = inv.claim(sId2)
            next should not be (null)
            next should not be (previous)
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
            subs should not be (null)
            subs.unsubscribe()
        }

        scenario("re-subscribing to an active session")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector1 = new AwaitableObserver[Response](0)
            val collector2 = new AwaitableObserver[Response](0)
            val subs1 = session.observable().subscribe(collector1)
            subs1 should not be (null)
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
            subs1 should not be (null)
            subs1.unsubscribe()

            val subs2 = session.observable().subscribe(collector2)
            subs2 should not be (null)
            subs2.unsubscribe()
        }

        scenario("abandoned session expiration")
        {
            SessionInventory.SESSION_TIMEOUT = 1000
            val collector1 = new AwaitableObserver[Response](0)
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            Thread.sleep(2000)
            a [SessionInventory.SessionExpirationException] should be
                thrownBy({session.observable().subscribe(collector1)})
        }

        scenario("disconnected session expiration")
        {
            SessionInventory.SESSION_TIMEOUT = 1000
            val collector1 = new AwaitableObserver[Response](0)
            val collector2 = new AwaitableObserver[Response](0)
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)

            val subs1 = session.observable().subscribe(collector1)
            subs1 should not be (null)
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
            session.get(Uuid(oId), classOf[Network], nack(req))

            // wait for futures to be completed
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (1)
            isNAck(events(0), req) should be (true)
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

            session.get(Uuid(oId), classOf[Network], nack(req))

            // wait for fures to be completed
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (1)
            isBridge(events(0), oId, "bridge1") should be (true)
        }

        scenario("watch non-existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](1)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()
            session.watch(Uuid(oId), classOf[Network], nack(req))

            // wait for futures to complete
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (1)
            isNAck(events(0), req) should be (true)
        }

        scenario("watch existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](3)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()

            // pre-create bridge
            store.create(bridge(oId, "bridge"))

            // subscribe to changes
            session.watch(Uuid(oId), classOf[Network], nack(req))

            // update bridge
            store.update(bridge(oId, "bridge-update"))

            // delete bridge
            store.delete(classOf[Network], UUIDUtil.toProto(oId))

            // wait for futures to complete
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() should be (0)
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (3)
            isBridge(events(0), oId, "bridge") should be (true)
            isBridge(events(1), oId, "bridge-update") should be (true)
            isDeletion(events(2), oId) should be (true)

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
            session.unwatch(Uuid(oId), classOf[Network], ack(req), nack(req))

            // wait for futures to complete
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() should be (0)
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (1)
            isAck(events(0), req) should be (true)
        }

        scenario("unwatch registered")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](3)
            val subs = session.observable().subscribe(collector)

            val req1 = UUID.randomUUID()
            val req2 = UUID.randomUUID()
            val oId = UUID.randomUUID()

            store.create(bridge(oId, "bridge"))
            session.watch(Uuid(oId), classOf[Network], nack(req1))
            store.update(bridge(oId, "bridge-1"))
            session.unwatch(Uuid(oId), classOf[Network], ack(req2), nack(req2))
            store.update(bridge(oId, "bridge-2"))
            store.delete(classOf[Network], UUIDUtil.toProto(oId))

            // wait for futures to complete
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() should be (0)
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (3)
            isBridge(events(0), oId, "bridge") should be (true)
            isBridge(events(1), oId, "bridge-1") should be (true)
            isAck(events(2), req2) should be (true)
        }

        scenario("watch-all empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](6)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()

            session.watchAll(classOf[Network], ack(req), nack(req))

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

            collector.getOnCompletedEvents.size() should be (0)
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (6)
            isAck(events(0), req) should be (true)
            isBridge(events(1), b1, "bridge1") should be (true)
            isBridge(events(2), b2, "bridge2") should be (true)
            isBridge(events(3), b1, "bridge1-update") should be (true)
            isBridge(events(4), b3, "bridge3") should be (true)
            isDeletion(events(5), b2)
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

            session.watchAll(classOf[Network], ack(req), nack(req))

            store.create(bridge(b3, "bridge3"))
            store.delete(classOf[Network], UUIDUtil.toProto(b2))

            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() should be (0)
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (5)
            events.exists(rsp => isAck(rsp, req)) should be (true)
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update")) should be (true)
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) should be (true)
            events.exists(rsp => isBridge(rsp, b3, "bridge3")) should be (true)
            events.exists(rsp => isDeletion(rsp, b2)) should be (true)
        }

        scenario("unwatch-all empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](1)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()

            session.unwatchAll(classOf[Network], ack(req), nack(req))

            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() should be (0)
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (1)
            isAck(events(0), req) should be (true)
        }

        scenario("unwatch-all non-empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](4)
            val subs = session.observable().subscribe(collector)

            val req1 = UUID.randomUUID()
            val req2 = UUID.randomUUID()

            session.watchAll(classOf[Network], ack(req1), nack(req1))

            val b1 = UUID.randomUUID()
            val b2 = UUID.randomUUID()
            val b3 = UUID.randomUUID()

            store.create(bridge(b1, "bridge1"))
            store.create(bridge(b2, "bridge2"))

            session.unwatchAll(classOf[Network], ack(req2), nack(req2))

            store.update(bridge(b1, "bridge1-update"))
            store.create(bridge(b3, "bridge3"))
            store.delete(classOf[Network], UUIDUtil.toProto(b2))

            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() should be (0)
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (4)
            events.exists(rsp => isAck(rsp, req1)) should be (true)
            events.exists(rsp => isBridge(rsp, b1, "bridge1")) should be (true)
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) should be (true)
            events.exists(rsp => isAck(rsp, req2)) should be (true)
        }

        scenario("terminate empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](2)
            val subs = session.observable().subscribe(collector)

            val req = UUID.randomUUID()
            session.terminate(ack(req))

            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() should be (1)
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (1)
            isAck(events(0), req) should be (true)
        }

        scenario("terminate non-empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new AwaitableObserver[Response](4)
            val subs = session.observable().subscribe(collector)

            val req1 = UUID.randomUUID()
            val req2 = UUID.randomUUID()
            val oId = UUID.randomUUID()

            store.create(bridge(oId, "bridge"))
            session.watch(Uuid(oId), classOf[Network], nack(req1))
            store.update(bridge(oId, "bridge-1"))
            session.terminate(ack(req2))
            store.update(bridge(oId, "bridge-2"))
            store.delete(classOf[Network], UUIDUtil.toProto(oId))

            // wait for futures to complete
            collector.await(WAIT_TIME)
            subs.unsubscribe()

            collector.getOnCompletedEvents.size() should be (1)
            val events = collectionAsScalaIterable(collector.getOnNextEvents).toArray
            events.size should be (3)
            isBridge(events(0), oId, "bridge") should be (true)
            isBridge(events(1), oId, "bridge-1") should be (true)
            isAck(events(2), req2) should be (true)
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

            session.watchAll(classOf[Network], ack(req), nack(req))

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

            collector.getOnCompletedEvents.isEmpty should be (true)
            val events =
                collectionAsScalaIterable(partial.getOnNextEvents).toArray[Response] ++
                collectionAsScalaIterable(collector.getOnNextEvents).toArray[Response]

            events.size should be (12)
            events.exists(rsp => isAck(rsp, req)) should be (true)

            events.exists(rsp => isBridge(rsp, b1, "bridge1")) should be (true)
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) should be (true)
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update1")) should be (true)

            events.exists(rsp => isBridge(rsp, b3, "bridge3")) should be (true)
            events.exists(rsp => isBridge(rsp, b2, "bridge2-update1")) should be (true)
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update2")) should be (true)

            events.exists(rsp => isBridge(rsp, b4, "bridge4")) should be (true)
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update3")) should be (true)
            events.exists(rsp => isBridge(rsp, b3, "bridge3-update1")) should be (true)
            events.exists(rsp => isDeletion(rsp, b2)) should be (true)
        }

        scenario("replay events")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val initial = new AwaitableObserver[Response](4)
            val collector = new AwaitableObserver[Response](4)
            val subs1 = session.observable().subscribe(initial)

            val req = UUID.randomUUID()

            session.watchAll(classOf[Network], ack(req), nack(req))

            val b1 = UUID.randomUUID()
            val b2 = UUID.randomUUID()
            val b3 = UUID.randomUUID()
            val b4 = UUID.randomUUID()

            store.create(bridge(b1, "bridge1"))
            store.create(bridge(b2, "bridge2"))
            store.update(bridge(b1, "bridge1-update1"))

            initial.await(WAIT_TIME)
            subs1.unsubscribe()

            val subs2 = session.observable().subscribe(collector)

            collector.await(WAIT_TIME)
            subs2.unsubscribe()

            collector.getOnCompletedEvents.isEmpty should be (true)
            val events =
                collectionAsScalaIterable(collector.getOnNextEvents).toArray[Response]

            events.size should be (4)
            events.exists(rsp => isAck(rsp, req)) should be (true)

            events.exists(rsp => isBridge(rsp, b1, "bridge1")) should be (true)
            events.exists(rsp => isBridge(rsp, b2, "bridge2")) should be (true)
            events.exists(rsp => isBridge(rsp, b1, "bridge1-update1")) should be (true)
        }
    }
}
