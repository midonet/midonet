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

import java.util.UUID

import org.midonet.cluster.util.UUIDUtil

import org.midonet.cluster.rpc.Commands

abstract class Command
case class HandShake(reqId: UUID, lastTx: String) extends Command
case class Get(reqId: UUID) extends Command
case class Unsubscribe(reqId: UUID) extends Command
case class Bye(reqId: UUID) extends Command
case class InvalidCommand(proto: Commands.Request) extends Command

object Command {
    def parse(proto: Commands.Request): Command =
        if (proto.hasHandshake)
            new HandShake(UUIDUtil.fromProto(proto.getHandshake.getReqId),
                         proto.getHandshake.getLastTxId)
        else if (proto.hasGet)
            new Get(UUIDUtil.fromProto(proto.getGet.getReqId))
        else if (proto.hasUnsubscribe)
            new Unsubscribe(UUIDUtil.fromProto(proto.getUnsubscribe.getReqId))
        else if (proto.hasBye)
            new Bye(UUIDUtil.fromProto(proto.getBye.getCnxnId))
        else
            new InvalidCommand(proto)
}

