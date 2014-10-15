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

package org.midonet.brain.api.services

import com.google.protobuf.Message

import org.slf4j.LoggerFactory

import org.midonet.cluster.models.Commons
import org.midonet.cluster.rpc.Commands

object Suspend

/**
 * TODO: dummy protocol until we plug in the real one
 */
object State {
    type ClientFactory = (Commons.UUID, Message, Message) => Option[ClientBase]
    def start(f: ClientFactory): State = Ready(f)
}

sealed class State() {
    protected type Process = PartialFunction[Any, State]
    private val log = LoggerFactory.getLogger(classOf[State])
    val response = Commands.Response.newBuilder()
    protected object ack {
        val builder = Commands.Response.Ack.newBuilder()
        def get(id: Commons.UUID) =
            response.setAck(builder.setReqId(id).build).build
    }
    protected object nack {
        val builder = Commands.Response.NAck.newBuilder()
        def get(id: Commons.UUID) =
            response.setNack(builder.setReqId(id).build).build
    }
    def process: Process = {
        case m =>
            log.warn("Unhandled message: {}", m.getClass)
            this
    }
}
final case class Ready(f: State.ClientFactory) extends State() {
    override def process = ({
        case msg: Commands.Request.Handshake =>
            f(msg.getCnxnId, ack.get(msg.getReqId), nack.get(msg.getReqId)) match {
                case Some(cl) => this
                case None => this
            }
    }: Process) orElse super.process
}

