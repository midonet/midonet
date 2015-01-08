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

import java.util.UUID

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.brain.services.topology.server.ServerState.CnxnFactory
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.rpc.Commands.Request._
import org.midonet.cluster.rpc.Commands.Response._
import org.midonet.cluster.rpc.Commands.{ID, Request, Response}
import org.midonet.cluster.services.topology.common.{TopologyMappings, Interruption, State}
import org.midonet.cluster.util.UUIDUtil
import rx.Subscription

/** Tests the Topology API protocol implementation. */
@RunWith(classOf[JUnitRunner])
class ServerStateTest extends FlatSpec with Matchers {

    val log = LoggerFactory.getLogger(classOf[ServerStateTest])
    val resp = Response.newBuilder()
    val req = Request.newBuilder()

    def randUuid = UUIDUtil.toProto(UUID.randomUUID())
    def randId = ID.newBuilder()
        .setUuid(UUIDUtil.toProto(UUID.randomUUID()))
        .build()

    def genHandshake(reqId: Commons.UUID = randUuid) = req.clear().setHandshake(
        Handshake.newBuilder().setCnxnId(randUuid).setReqId(reqId).build())
        .build()
    def genBye(reqId: Commons.UUID = randUuid) = req.clear().setBye(
        Bye.newBuilder().setReqId(reqId).build())
        .build()
    def genAck(reqId: Commons.UUID) = resp.setAck(
        Ack.newBuilder().setReqId(reqId).build())
        .build()
    def genNAck(reqId: Commons.UUID) = resp.setNack(
        NAck.newBuilder().setReqId(reqId).build())
        .build()
    def genGet(reqId: Commons.UUID = randUuid, subscribe: Boolean = false) =
        req.clear().setGet(Get.newBuilder().setReqId(reqId)
                   .setSubscribe(subscribe)
                   .setId(randId)
                   .setType(Topology.Type.NETWORK)
                   .build()).build()
    def genSub(reqId: Commons.UUID = randUuid) = genGet(reqId, subscribe = true)
    def genSubAll(reqId: Commons.UUID = randUuid) =
        req.clear().setGet(Get.newBuilder().setReqId(reqId)
                               .setSubscribe(true)
                               .setType(Topology.Type.NETWORK)
                               .build()).build()
    def genUnsub(reqId: Commons.UUID = randUuid) =
        req.clear().setUnsubscribe(Unsubscribe.newBuilder().setReqId(reqId)
                   .setType(Topology.Type.NETWORK)
                   .setId(randId)
                   .build()).build()
    def genUnsubAll(reqId: Commons.UUID = randUuid) =
        req.clear().setUnsubscribe(Unsubscribe.newBuilder().setReqId(reqId)
                                       .setType(Topology.Type.NETWORK)
                                       .build()).build()

    val someId = randId
    val cnxnId = randUuid
    val handshake = genHandshake(cnxnId)

    implicit def inMatcher[T <: State](s: T): StateMatcher[T] = new StateMatcher(s)
    class StateMatcher[S <: State](var s: S, var msg: Any = null) {
        def after(useMsg: Any): StateMatcher[S] = {
            this.msg = useMsg
            this
        }

        def remains: StateMatcher[S] = {
            this becomes s ; this
        }
        def becomes[N <: State](nextState: N): StateMatcher[N] = {
            val nxt = s process msg
            nextState.getClass shouldEqual nxt.getClass
            inMatcher(nxt.asInstanceOf[N])
        }
    }

    var lastSession: Session = _
    def session: Session = {
        lastSession = Mockito.mock(classOf[Session])
        lastSession
    }
    var lastSubscription: Subscription = _
    def subscribe: Subscription = {
        lastSubscription = Mockito.mock(classOf[Subscription])
        lastSubscription
    }
    val sfactory: CnxnFactory = (id, seq, ack) => Some(session, subscribe)

    behavior of "Ready"
    it should "ignore bye" in { Ready(sfactory) after genBye() remains }
    it should "ignore get" in { Ready(sfactory) after genGet() remains }
    it should "ignore sub" in { Ready(sfactory) after genSub() remains }
    it should "ignore subAll" in { Ready(sfactory) after genSubAll() remains }
    it should "ignore unsub" in { Ready(sfactory) after genUnsub() remains }
    it should "ignore unsubAll" in { Ready(sfactory) after genUnsubAll() remains }
    it should "ignore unknown" in { Ready(sfactory) after 1 remains }
    it should "close on interrupt" in {
        Ready(sfactory) after Interruption becomes Closed
    }
    it should "activate" in {
        val hs = genHandshake()
        Ready(sfactory) after hs becomes Active(lastSession, lastSubscription)
        Mockito.verify(lastSession).noOp(genAck(hs.getHandshake.getReqId))
    }

    behavior of "Closed"
    it should "ignore bye" in { Closed after genBye() remains }
    it should "ignore get" in { Closed after genGet() remains }
    it should "ignore sub" in { Closed after genSub() remains }
    it should "ignore subAll" in { Closed after genSubAll() remains }
    it should "ignore unsub" in { Closed after genUnsub() remains }
    it should "ignore unsubAll" in { Closed after genUnsubAll() remains }
    it should "ignore interrupt" in { Closed after Interruption remains }
    it should "ignore handshake " in { Closed after genHandshake() remains }
    it should "ignore unknown" in { Closed after 1 remains }

    behavior of "Active"
    it should "ignore handshake " in {
        Active(session, subscribe).after(genHandshake()).remains
        Mockito.verifyZeroInteractions(lastSession)
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "ignore unknown" in {
        Active(session, subscribe).after(1).remains
        Mockito.verifyZeroInteractions(lastSession)
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process interrupt" in {
        Active(session, subscribe) after Interruption becomes Closed
        Mockito.verifyZeroInteractions(lastSession)
        Mockito.verify(lastSubscription).unsubscribe()
    }
    it should "process get" in {
        val get = genGet()
        Active(session, subscribe).after(get).remains
        Mockito.verify(lastSession).get(
            Id.fromProto(get.getGet.getId),
            TopologyMappings.klassOf(get.getGet.getType).get,
            genNAck(get.getGet.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process subscribe" in {
        val sub = genSub()
        Active(session, subscribe).after(sub).remains
        Mockito.verify(lastSession).watch(
            Id.fromProto(sub.getGet.getId),
            TopologyMappings.klassOf(sub.getGet.getType).get,
            genNAck(sub.getGet.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process subscribe all" in {
        val sub = genSubAll()
        Active(session, subscribe).after(sub).remains
        Mockito.verify(lastSession).watchAll(
            TopologyMappings.klassOf(sub.getGet.getType).get,
            genAck(sub.getGet.getReqId),
            genNAck(sub.getGet.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process unsubscribe" in {
        val unsub = genUnsub()
        Active(session, subscribe).after(unsub).remains
        Mockito.verify(lastSession).unwatch(
            Id.fromProto(unsub.getUnsubscribe.getId),
            TopologyMappings.klassOf(unsub.getUnsubscribe.getType).get,
            genAck(unsub.getUnsubscribe.getReqId),
            genNAck(unsub.getUnsubscribe.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process unsubscribe all" in {
        val unsub = genUnsubAll()
        Active(session, subscribe).after(unsub).remains
        Mockito.verify(lastSession).unwatchAll(
            TopologyMappings.klassOf(unsub.getUnsubscribe.getType).get,
            genAck(unsub.getUnsubscribe.getReqId),
            genNAck(unsub.getUnsubscribe.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process bye " in {
        val bye = genBye()
        Active(session, subscribe).after(bye).becomes(Closed)
        Mockito.verify(lastSession).terminate(genAck(bye.getBye.getReqId))
        Mockito.verify(lastSubscription).unsubscribe()
    }
}
