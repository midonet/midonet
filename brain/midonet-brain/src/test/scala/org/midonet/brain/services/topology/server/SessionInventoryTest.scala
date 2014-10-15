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

import java.util.{ArrayList, UUID}

import scala.collection.JavaConversions

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import rx.Subscriber

import org.midonet.cluster.data.storage.{InMemoryStorage, Storage}
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.rpc.Commands.Response
import org.midonet.cluster.rpc.Commands.Response.{Ack, NAck}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.util.eventloop.CallingThreadReactor
import org.midonet.util.reactivex.HermitObservable.HermitOversubscribedException

/** Tests the Topology API protocol implementation. */
@RunWith(classOf[JUnitRunner])
class SessionInventoryTest extends FeatureSpec
                                   with Matchers
                                   with BeforeAndAfter {

    var store: Storage = _
    var inv: SessionInventory = _

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

    class Collector[T] extends Subscriber[T] {
        private val accum = new ArrayList[T]()
        private var finished = false
        override def onError(exc: Throwable) = throw exc
        override def onCompleted() = finished = true
        override def onNext(ev: T) = accum.add(ev)

        def isCompleted = finished
        def values: Array[Response] =
            accum.toArray(new Array[Response](accum.size()))
    }

    before
    {
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
            val collector = new Collector[Response]
            val subs = session.observable.subscribe(collector)
            subs should not be (null)
            collector.unsubscribe()
        }

        scenario("re-subscribing to an active session")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector1 = new Collector[Response]
            val collector2 = new Collector[Response]
            val subs1 = session.observable.subscribe(collector1)
            subs1 should not be (null)
            an [HermitOversubscribedException] should be
                thrownBy({session.observable.subscribe(collector2)})
            collector1.unsubscribe()
            collector2.unsubscribe()
        }

        scenario("re-subscribing after unsubscribing to a session")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector1 = new Collector[Response]
            val collector2 = new Collector[Response]

            val subs1 = session.observable.subscribe(collector1)
            subs1 should not be (null)
            collector1.unsubscribe()

            val subs2 = session.observable.subscribe(collector2)
            subs2 should not be (null)
            collector2.unsubscribe()
        }
    }

    feature("session commands")
    {
        scenario("get non-existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()
            session.get(Uuid(oId), classOf[Network], nack(req))

            // wait for futures to be completed
            Thread.sleep(1000)

            collector.unsubscribe()
            collector.values.size should be (1)
            isNAck(collector.values(0), req) should be (true)
        }

        scenario("get existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()

            store.create(bridge(oId, "bridge1"))

            session.get(Uuid(oId), classOf[Network], nack(req))

            // wait for fures to be completed
            Thread.sleep(1000)

            collector.unsubscribe()
            collector.values.size should be (1)
            isBridge(collector.values(0), oId, "bridge1") should be (true)
        }

        scenario("watch non-existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()
            session.watch(Uuid(oId), classOf[Network], nack(req))

            // wait for futures to complete
            Thread.sleep(1000)

            collector.unsubscribe()
            collector.values.size should be (1)
            isNAck(collector.values(0), req) should be (true)
        }

        scenario("watch existent")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

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
            Thread.sleep(1000)

            // check results
            collector.unsubscribe()
            collector.isCompleted should be (false)
            collector.values.size should be (3)
            isBridge(collector.values(0), oId, "bridge") should be (true)
            isBridge(collector.values(1), oId, "bridge-update") should be (true)
            isDeletion(collector.values(2), oId) should be (true)

        }

        scenario("unwatch non-registered")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()

            // Note: it should return ack even if not registered
            session.unwatch(Uuid(oId), classOf[Network], ack(req), nack(req))

            // wait for futures to complete
            Thread.sleep(1000)

            // check results
            collector.unsubscribe()
            collector.isCompleted should be (false)
            collector.values.size should be (1)
            isAck(collector.values(0), req) should be (true)
        }

        scenario("unwatch registered")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

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
            Thread.sleep(1000)

            // check results
            collector.unsubscribe()
            collector.isCompleted should be (false)
            collector.values.size should be (3)
            isBridge(collector.values(0), oId, "bridge") should be (true)
            isBridge(collector.values(1), oId, "bridge-1") should be (true)
            isAck(collector.values(2), req2) should be (true)
        }

        scenario("watch-all empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

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

            Thread.sleep(1000)
            collector.unsubscribe()
            collector.isCompleted should be (false)
            collector.values.size should be (6)
            isAck(collector.values(0), req) should be (true)
            isBridge(collector.values(1), b1, "bridge1") should be (true)
            isBridge(collector.values(2), b2, "bridge2") should be (true)
            isBridge(collector.values(3), b1, "bridge1-update") should be (true)
            isBridge(collector.values(4), b3, "bridge3") should be (true)
            isDeletion(collector.values(5), b2)
        }

        scenario("watch-all non-empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

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

            Thread.sleep(1000)
            collector.unsubscribe()
            collector.isCompleted should be (false)
            collector.values.size should be (5)
            collector.values.exists(rsp => isAck(rsp, req)) should be (true)
            collector.values.exists(rsp => isBridge(rsp, b1, "bridge1-update")) should be (true)
            collector.values.exists(rsp => isBridge(rsp, b2, "bridge2")) should be (true)
            collector.values.exists(rsp => isBridge(rsp, b3, "bridge3")) should be (true)
            collector.values.exists(rsp => isDeletion(rsp, b2)) should be (true)
        }

        scenario("unwatch-all empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

            val req = UUID.randomUUID()

            session.unwatchAll(classOf[Network], ack(req), nack(req))

            Thread.sleep(1000)
            collector.unsubscribe()
            collector.isCompleted should be (false)
            collector.values.size should be (1)
            isAck(collector.values(0), req) should be (true)
        }

        scenario("unwatch-all non-empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

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

            Thread.sleep(1000)
            collector.unsubscribe()
            collector.isCompleted should be (false)
            collector.values.size should be (4)
            collector.values.exists(rsp => isAck(rsp, req1)) should be (true)
            collector.values.exists(rsp => isBridge(rsp, b1, "bridge1")) should be (true)
            collector.values.exists(rsp => isBridge(rsp, b2, "bridge2")) should be (true)
            collector.values.exists(rsp => isAck(rsp, req2)) should be (true)
        }

        scenario("terminate empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

            val req = UUID.randomUUID()
            session.terminate(ack(req))

            Thread.sleep(1000)

            collector.unsubscribe()
            collector.isCompleted should be (true)
            collector.values.size should be (1)
            isAck(collector.values(0), req) should be (true)
        }

        scenario("terminate non-empty")
        {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

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
            Thread.sleep(1000)

            // check results
            collector.unsubscribe()
            collector.isCompleted should be (true)
            collector.values.size should be (3)
            isBridge(collector.values(0), oId, "bridge") should be (true)
            isBridge(collector.values(1), oId, "bridge-1") should be (true)
            isAck(collector.values(2), req2) should be (true)
        }
    }
}
