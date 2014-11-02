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

package org.midonet.cluster.minions.topology

import java.util.UUID

import com.google.protobuf.Message
import org.midonet.cluster.minions.topology.TopologyApiProtocol._
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.rpc.Commands.Request._
import org.midonet.cluster.rpc.Commands.Response._
import org.midonet.cluster.util.UUIDUtil
import org.scalatest.{FeatureSpec, Matchers}
import org.slf4j.LoggerFactory
import rx.Observable
import rx.functions.{Action1, Func2}

/** Tests the Topology API protocol implementation. */
class ApiTest extends FeatureSpec with Matchers {

    val log = LoggerFactory.getLogger(classOf[ApiTest])

    def randId = UUIDUtil.toProto(UUID.randomUUID())

    def genHandshake(reqId: Commons.UUID) = Handshake.newBuilder().setCnxnId(randId).setReqId(reqId).build()
    def genBye(reqId: Commons.UUID) = Bye.newBuilder().setReqId(reqId).build()
    def genAck(reqId: Commons.UUID) = Ack.newBuilder().setReqId(reqId).build()
    def genNAck(reqId: Commons.UUID) = NAck.newBuilder().setReqId(reqId).build()
    def genGet(reqId: Commons.UUID, sub: Boolean = false) = Get.newBuilder()
                                         .setReqId(reqId)
                                         .setSubscribe(sub)
                                         .addIds(randId)
                                         .addIds(randId)
                                         .setType(Topology.Type.NETWORK)
                                         .build()

    val someId = randId
    val cnxnId = randId
    val handshake = genHandshake(cnxnId)

    val cases = Map[(String, TopologyApiProtocol, Message), Transition] (
        ("Listening opens connection", Ready(null), handshake)
             -> Transition(Active(handshake.getReqId),
                           Seq(genAck(handshake.getReqId))),
        ("Listening restores connection", Ready(null), handshake)
             -> Transition(Ready(handshake.getReqId),
                           Seq(genAck(handshake.getReqId))),
        ("Listening rejects wrong cnxn id", Ready(someId), handshake)
             -> Transition(Closed(someId), Seq(genNAck(handshake.getReqId))),
        ("Listening ignores unknown proto", Ready(someId), randId)
             -> Transition(Ready(someId), Seq.empty),
        ("Active handles get", Active(cnxnId), genGet(someId))
             -> Transition(Active(cnxnId), Seq(genAck(someId))),
        ("Active handles sub", Active(cnxnId), genGet(someId, true))
             -> Transition(Active(cnxnId), Seq(genAck(someId))),
        ("Active handles bye", Active(cnxnId), genBye(cnxnId))
             -> Transition(Closed(cnxnId), Seq.empty),
        ("Closed ignores bye", Closed(cnxnId), genBye(someId))
             -> Transition(Closed(cnxnId), Seq.empty),
        ("Closed ignores get", Closed(cnxnId), genGet(someId))
             -> Transition(Closed(cnxnId), Seq.empty),
        ("Closed ignores bye with wrong id", Closed(cnxnId), genBye(cnxnId))
             -> Transition(Closed(cnxnId), Seq.empty)
    )

    feature("Topology API protocol implementation") {
        cases.seq.foreach { case ((desc, state, input), asExpected) =>
            scenario(desc) {
                state process input should be (asExpected)
            }
        }
    }
}
