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
import org.midonet.util.reactivex.HermitObservable.HermitException

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

    before {
        store = new InMemoryStorage(new CallingThreadReactor)
        store.registerClass(classOf[Network])
        store.build()
        inv = new SessionInventory(store)
    }

    feature("session inventory") {
        scenario("claim new session id") {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            session should not be (null)
        }

        scenario("claim an existing session id") {
            val sId = UUID.randomUUID()
            val previous = inv.claim(sId)
            val session = inv.claim(sId)
            session should not be (null)
            session should be (previous)
        }

        scenario("claim a different session id") {
            val sId1 = UUID.randomUUID()
            val sId2 = UUID.randomUUID()
            val previous = inv.claim(sId1)
            val next = inv.claim(sId2)
            next should not be (null)
            next should not be (previous)
        }
    }

    feature("session subscription limits") {
        scenario("subscribing to session for the first time") {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            val subs = session.observable.subscribe(collector)
            subs should not be (null)
        }

        scenario("re-subscribing to an active session") {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector1 = new Collector[Response]
            val collector2 = new Collector[Response]
            val subs1 = session.observable.subscribe(collector1)
            subs1 should not be (null)
            an [HermitException] should be
                thrownBy({session.observable.subscribe(collector2)})
        }

        scenario("re-subscribing after unsubscribing to a session") {
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

    feature("session commands") {
        scenario("get non-existent") {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()
            session.get(Uuid(oId), classOf[Network], nack(req))

            // wait for futures to be completed
            Thread.sleep(1000)

            collector.values.size should be (1)
            isNAck(collector.values(0), req) should be (true)
        }

        scenario("get existent") {
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

            collector.values.size should be (1)
            isBridge(collector.values(0), oId, "bridge1") should be (true)
        }

        scenario("watch non-existent") {
            val sId = UUID.randomUUID()
            val session = inv.claim(sId)
            val collector = new Collector[Response]
            session.observable.subscribe(collector)

            val req = UUID.randomUUID()
            val oId = UUID.randomUUID()
            session.watch(Uuid(oId), classOf[Network], nack(req))

            // wait for futures to complete
            Thread.sleep(1000)

            collector.values.size should be (1)
            isNAck(collector.values(0), req) should be (true)
        }

    }
}
