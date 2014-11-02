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

package org.midonet.brain.services.topology

import java.util.UUID

import org.mockito.Mockito
import org.scalatest._
import org.slf4j.LoggerFactory

import org.midonet.brain.services.topology.State.SessionFactory
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.rpc.Commands.Request._
import org.midonet.cluster.rpc.Commands.Response._
import org.midonet.cluster.util.UUIDUtil

import scala.collection.JavaConversions._

/** Tests the Topology API protocol implementation. */
class TopologyApiTest extends FlatSpec with Matchers {

    val log = LoggerFactory.getLogger(classOf[TopologyApiTest])

    def randId = UUIDUtil.toProto(UUID.randomUUID())

    def genHandshake(reqId: Commons.UUID = randId) = Handshake.newBuilder()
                                                     .setCnxnId(randId)
                                                     .setReqId(reqId).build()
    def genBye(reqId: Commons.UUID = randId) = Bye.newBuilder()
                                                  .setReqId(reqId)
                                                  .build()
    def genAck(reqId: Commons.UUID) = Ack.newBuilder().setReqId(reqId).build()
    def genNAck(reqId: Commons.UUID) = NAck.newBuilder().setReqId(reqId).build()
    def genGet(reqId: Commons.UUID = randId, subscribe: Boolean = false) =
        Get.newBuilder().setReqId(reqId)
                        .setSubscribe(subscribe)
                        .addIds(randId)
                        .addIds(randId)
                        .setType(Topology.Type.NETWORK)
                        .build()
    def genSub(reqId: Commons.UUID = randId) = genGet(reqId, subscribe = true)
    def genUnsub(reqId: Commons.UUID = randId) =
        Unsubscribe.newBuilder().setReqId(reqId)
                                .setType(Topology.Type.NETWORK)
                                .setId(randId)
                                .build()

    val someId = randId
    val cnxnId = randId
    val handshake = genHandshake(cnxnId)

    implicit def inMatcher[T <: State](s: T) = new StateMatcher(s)
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
    val sessionFac: SessionFactory = (id, m) => Some(session)
    def session: Session = {
        lastSession = Mockito.mock(classOf[Session]); lastSession
    }

    behavior of "Ready"
    it should "ignore bye" in { Ready(sessionFac) after genBye() remains }
    it should "ignore get" in { Ready(sessionFac) after genGet() remains }
    it should "ignore sub" in { Ready(sessionFac) after genSub() remains }
    it should "ignore unknown" in { Ready(sessionFac) after 1 remains }
    it should "close on interrupt" in {
        Ready(sessionFac) after Interruption becomes Closed
    }
    it should "activate" in {
        Ready(sessionFac) after genHandshake() becomes Active(lastSession)
        Mockito.verifyZeroInteractions(lastSession)
    }

    behavior of "Closed"
    it should "ignore bye" in { Closed after genBye() remains }
    it should "ignore get" in { Closed after genGet() remains }
    it should "ignore sub" in { Closed after genSub() remains }
    it should "ignore interrupt" in { Closed after Interruption remains }
    it should "ignore handshake " in { Closed after genHandshake() remains }
    it should "ignore unknown" in { Closed after 1 remains }

    behavior of "Active"
    it should "ignore handshake " in {
        Active(session).after(genHandshake()).remains
        Mockito.verifyZeroInteractions(lastSession)
    }
    it should "ignore unknown" in {
        Active(session).after(1).remains
        Mockito.verifyZeroInteractions(lastSession)
    }
    it should "closes on interrupt" in {
        Active(session) after Interruption becomes Closed
        Mockito.verify(lastSession).suspend()
    }
    it should "process get" in {
        val get = genGet()
        Active(session).after(get).remains
        Mockito.verify(lastSession).get(get.getIdsList, get.getType,
                                        genAck(get.getReqId),
                                        genNAck(get.getReqId))
    }
    it should "process subscribe" in {
        val sub = genSub()
        Active(session).after(sub).remains
        Mockito.verify(lastSession).watch(sub.getIdsList, sub.getType,
                                          genAck(sub.getReqId),
                                          genNAck(sub.getReqId))
    }
    it should "process unsubscribe" in {
        val unsub = genUnsub()
        Active(session).after(unsub).remains
        Mockito.verify(lastSession).unwatch(unsub.getId, unsub.getType,
                                            genAck(unsub.getReqId),
                                            genNAck(unsub.getReqId))
    }
    it should "process bye " in {
        val bye = genBye()
        Active(session).after(bye).becomes(Closed)
        Mockito.verify(lastSession).terminate(genAck(bye.getReqId))
    }
}
