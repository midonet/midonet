package org.midonet.services.flowstate.transfer

import java.util.UUID

import com.google.protobuf.ByteString
import org.midonet.cluster.flowstate.FlowStateTransfer._
import org.midonet.cluster.flowstate.FlowStateTransfer.StateForPortResponse.Error.Code
import org.midonet.cluster.models.Commons.{UUID => ProtoUUID}

/*
 * Copyright 2016 Midokura SARL
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

/*
 * Helper for building flow state transfer Protocol Buffers objects
 */

object StateTransferProtocolBuilder {

    def buildStateForPort(portId: UUID) = {
        StateForPortRequest.newBuilder()
            .setPortId(toProtoUUID(portId))
            .build()
    }

    def buildAck(portId: UUID, data: Array[Byte]) = {
        StateForPortResponse.newBuilder()
            .setAck(buildAckInternal(portId, data))
            .build()
    }

    def buildError(code: Code, e: Throwable) = {
        StateForPortResponse.newBuilder()
            .setError(buildErrorInternal(code, e))
            .build()
    }

    private def buildErrorInternal(code: Code, e: Throwable) = {
        StateForPortResponse.Error.newBuilder()
            .setCode(code)
            .setDescription(e.getMessage)
            .build()
    }

    private def buildAckInternal(portId: UUID, data: Array[Byte]) = {
        StateForPortResponse.Ack.newBuilder()
            .setPortId(toProtoUUID(portId))
            .setFlowstate(ByteString.copyFrom(data))
            .build()
    }

    private def toProtoUUID(uuid: UUID) = {
        ProtoUUID.newBuilder()
            .setLsb(uuid.getLeastSignificantBits)
            .setMsb(uuid.getMostSignificantBits)
            .build()
    }

}
