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

import com.google.protobuf.Message
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory
import rx.{Observer, Subscription}

import org.midonet.cluster.services.topology.server.ServerState.SessionInfo
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.rpc.Commands.Request._
import org.midonet.cluster.rpc.Commands.{Request, Response}
import org.midonet.cluster.services.topology.common.{TopologyMappings, Interruption}
import org.midonet.cluster.services.topology.common.ProtocolFactory.State
import org.midonet.cluster.util.UUIDUtil

/** Tests the protocol states for the topology API servicde implementation. */
@RunWith(classOf[JUnitRunner])
class ServerStateTest extends FlatSpec with Matchers {

    val log = LoggerFactory.getLogger(classOf[ServerStateTest])
    val resp = Response.newBuilder()
    val req = Request.newBuilder()

    def randUuid = UUIDUtil.toProto(UUID.randomUUID())
    def randId = UUIDUtil.randomUuidProto

    def genHandshake(reqId: Commons.UUID = randUuid, seqno: Long = 0) =
        req.clear().setHandshake(Handshake.newBuilder()
                   .setCnxnId(randUuid).setReqId(reqId).setSeqno(seqno)
        ).build()
    def genBye(reqId: Commons.UUID = randUuid) =
        req.clear().setBye(Bye.newBuilder().setReqId(reqId)).build()
    def genAck(reqId: Commons.UUID) = ServerState.makeAck(reqId)
    def genNAck(reqId: Commons.UUID) = ServerState.makeNAck(reqId)
    def genGet(reqId: Commons.UUID = randUuid, subscribe: Boolean = false) =
        req.clear().setGet(Get.newBuilder()
                   .setReqId(reqId)
                   .setSubscribe(subscribe)
                   .setId(randId)
                   .setType(Topology.Type.NETWORK)
        ).build()
    def genGetAll(reqId: Commons.UUID = randUuid) =
        req.clear().setGet(Get.newBuilder()
                   .setReqId(reqId)
                   .setType(Topology.Type.NETWORK)
        ).build()
    def genSub(reqId: Commons.UUID = randUuid) = genGet(reqId, subscribe = true)
    def genSubAll(reqId: Commons.UUID = randUuid) =
        req.clear().setGet(Get.newBuilder()
                   .setReqId(reqId)
                   .setSubscribe(true)
                   .setType(Topology.Type.NETWORK)
        ).build()
    def genUnsub(reqId: Commons.UUID = randUuid) =
        req.clear().setUnsubscribe(Unsubscribe.newBuilder()
                   .setReqId(reqId)
                   .setType(Topology.Type.NETWORK)
                   .setId(randId)
        ).build()
    def genUnsubAll(reqId: Commons.UUID = randUuid) =
        req.clear().setUnsubscribe(Unsubscribe.newBuilder()
                   .setReqId(reqId)
                   .setType(Topology.Type.NETWORK)
        ).build()

    val someId = randId
    val cnxnId = randUuid

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
    def newSession: Session = {
        lastSession = Mockito.mock(classOf[Session])
        lastSession
    }
    var lastSubscription: Subscription = _
    def newSubscribe: Subscription = {
        lastSubscription = Mockito.mock(classOf[Subscription])
        lastSubscription
    }
    def sfactory = new SessionInfo {
        private val sess = newSession
        private val subs = newSubscribe
        override def subscription: Option[Subscription] = Some(subs)
        override def session: Option[Session] = Some(sess)
        override def output: Option[Observer[Message]] = None
        override def handshake(cnxnId: UUID, start: Long): Boolean = true
    }
    def badfactory = new SessionInfo {
        override def subscription: Option[Subscription] = None
        override def session: Option[Session] = None
        override def output: Option[Observer[Message]] = None
        override def handshake(cnxnId: UUID, start: Long): Boolean = false
    }

    behavior of "Ready"
    it should "ignore bye" in { Ready(sfactory) after genBye() remains }
    it should "ignore get" in { Ready(sfactory) after genGet() remains }
    it should "ignore sub" in { Ready(sfactory) after genSub() remains }
    it should "ignore subAll" in { Ready(sfactory) after genSubAll() remains }
    it should "ignore unsub" in { Ready(sfactory) after genUnsub() remains }
    it should "ignore unsubAll" in { Ready(sfactory) after genUnsubAll() remains }
    it should "ignore unknown" in { Ready(sfactory) after 1 remains }
    it should "close on interrupt" in {
        val factory = sfactory
        Ready(factory) after Interruption becomes Closed(factory)
    }
    it should "activate" in {
        val hs = genHandshake()
        val factory = sfactory
        Ready(factory) after hs becomes Active(factory)
        Mockito.verifyZeroInteractions(lastSession)
    }
    it should "fail" in {
        val hs = genHandshake(randUuid, 42)
        val factory = badfactory
        Ready(factory) after hs becomes Closed(factory)
        Mockito.verifyZeroInteractions(lastSession)
    }

    behavior of "Closed"
    it should "ignore bye" in { Closed(sfactory) after genBye() remains }
    it should "ignore get" in { Closed(sfactory) after genGet() remains }
    it should "ignore sub" in { Closed(sfactory) after genSub() remains }
    it should "ignore subAll" in { Closed(sfactory) after genSubAll() remains }
    it should "ignore unsub" in { Closed(sfactory) after genUnsub() remains }
    it should "ignore unsubAll" in { Closed(sfactory) after genUnsubAll() remains }
    it should "ignore interrupt" in { Closed(sfactory) after Interruption remains }
    it should "ignore handshake " in { Closed(sfactory) after genHandshake() remains }
    it should "ignore unknown" in { Closed(sfactory) after 1 remains }

    behavior of "Active"
    it should "ignore handshake " in {
        Active(sfactory).after(genHandshake()).remains
        Mockito.verifyZeroInteractions(lastSession)
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "ignore unknown" in {
        Active(sfactory).after(1).remains
        Mockito.verifyZeroInteractions(lastSession)
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process interrupt" in {
        val factory = sfactory
        Active(factory) after Interruption becomes Closed(factory)
        Mockito.verifyZeroInteractions(lastSession)
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process get" in {
        val get = genGet()
        Active(sfactory).after(get).remains
        Mockito.verify(lastSession).get(
            UUIDUtil.fromProto(get.getGet.getId),
            TopologyMappings.klassOf(get.getGet.getType).get,
            UUIDUtil.fromProto(get.getGet.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process getAll" in {
        val getAll = genGetAll()
        Active(sfactory).after(getAll).remains
        Mockito.verify(lastSession).getAll(
            TopologyMappings.klassOf(getAll.getGet.getType).get,
            UUIDUtil.fromProto(getAll.getGet.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process subscribe" in {
        val sub = genSub()
        Active(sfactory).after(sub).remains
        Mockito.verify(lastSession).watch(
            UUIDUtil.fromProto(sub.getGet.getId),
            TopologyMappings.klassOf(sub.getGet.getType).get,
            UUIDUtil.fromProto(sub.getGet.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process subscribe all" in {
        val subAll = genSubAll()
        Active(sfactory).after(subAll).remains
        Mockito.verify(lastSession).watchAll(
            TopologyMappings.klassOf(subAll.getGet.getType).get,
            UUIDUtil.fromProto(subAll.getGet.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process unsubscribe" in {
        val unsub = genUnsub()
        Active(sfactory).after(unsub).remains
        Mockito.verify(lastSession).unwatch(
            UUIDUtil.fromProto(unsub.getUnsubscribe.getId),
            TopologyMappings.klassOf(unsub.getUnsubscribe.getType).get,
            UUIDUtil.fromProto(unsub.getUnsubscribe.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process unsubscribe all" in {
        val unsubAll = genUnsubAll()
        Active(sfactory).after(unsubAll).remains
        Mockito.verify(lastSession).unwatchAll(
            TopologyMappings.klassOf(unsubAll.getUnsubscribe.getType).get,
            UUIDUtil.fromProto(unsubAll.getUnsubscribe.getReqId))
        Mockito.verifyZeroInteractions(lastSubscription)
    }
    it should "process bye " in {
        val bye = genBye()
        val factory = sfactory
        Active(factory).after(bye).becomes(Closed(factory))
        Mockito.verify(lastSession).terminate()
        Mockito.verifyZeroInteractions(lastSubscription)
    }
}
