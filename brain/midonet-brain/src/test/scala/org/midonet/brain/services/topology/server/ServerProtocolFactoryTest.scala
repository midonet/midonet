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
import com.google.protobuf.Message

import org.junit.runner.RunWith
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import rx.{Observable, Observer}

import org.midonet.cluster.rpc.Commands
import org.midonet.cluster.util.UUIDUtil

/** Tests the Topology API protocol implementation. */
@RunWith(classOf[JUnitRunner])
class ServerProtocolFactoryTest extends FeatureSpec with Matchers {

    val cnxUuid = UUID.randomUUID()
    val cnxId = UUIDUtil.toProto(cnxUuid)
    val reqId = UUIDUtil.toProto(UUID.randomUUID())
    val hs = Commands.Request.newBuilder().setHandshake(
        Commands.Request.Handshake.newBuilder()
            .setReqId(reqId)
            .setCnxnId(cnxId)
            .build()
    ).build()

    feature("get the initial protocol state") {
        scenario("basic life cycle") {
            val si = Mockito.mock(classOf[SessionInventory])
            val fac = new ServerProtocolFactory(si)
            Mockito.verifyZeroInteractions(si)
        }

        scenario("start state requested") {
            val si = Mockito.mock(classOf[SessionInventory])
            val fac = new ServerProtocolFactory(si)
            val out = Mockito.mock(classOf[Observer[Message]])
            val state = fac.start(out)
            state.isInstanceOf[Ready] should be (true)
            Mockito.verifyZeroInteractions(si)
            Mockito.verifyZeroInteractions(out)
        }

        scenario("returned initial state processes handshake correctly") {

            val sessionOutput: Observable[Commands.Response] = Observable.empty()
            val session = Mockito.mock(classOf[Session])
            Mockito.when(session.observable()).thenReturn(sessionOutput)

            val si = Mockito.mock(classOf[SessionInventory])
            Mockito.when(si.claim(cnxUuid)).thenReturn(session)

            val out = Mockito.mock(classOf[Observer[Message]])

            val factory = new ServerProtocolFactory(si)
            val state = factory.start(out)
            state.isInstanceOf[Ready] should be (true)
            Mockito.verifyZeroInteractions(si)
            Mockito.verifyZeroInteractions(out)

            val next = state.process(hs)

            val resp = ArgumentCaptor.forClass(classOf[Commands.Response])
            Mockito.verify(out, Mockito.times(1)).onNext(resp.capture)
            resp.getValue.hasAck should be (true)
            resp.getValue.getAck.getReqId should be (reqId)

            Mockito.verify(si, Mockito.times(1)).claim(cnxUuid)

            next.isInstanceOf[Active] should be (true)
        }
    }
}
