package org.midonet.services.flowstate.transfer

import java.util.UUID

import org.midonet.cluster.flowstate.FlowStateTransfer.{StateForPortRequest, StateForPortResponse}
import org.midonet.cluster.models.Commons.{UUID => ProtoUUID}
import org.midonet.services.flowstate.transfer.internal.{ErrorCode, StateForPort, StateTransferAck, StateTransferError}

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
 * Helper object to parse different Protocol Buffer State Transfer Protocol
 * responses to internal objects that can be handled with more ease
 */
object StateTransferProtocolParser {

    def parseTransferRequest(request: StateForPortRequest) = {
        val portId = toJavaUUID(request.getPortId)
        new StateForPort(portId)
    }

    def parseTransferResponse(response: StateForPortResponse) = {
        if (response.hasAck) {
            parseAck(response.getAck)
        } else if (response.hasError) {
            parseError(response.getError)
        } else {
            throw new IllegalArgumentException(s"Can not parse empty" +
                s" ${response.getClass.getSimpleName}")
        }
    }

    private def parseAck(ack: StateForPortResponse.Ack) = {
        val portId = toJavaUUID(ack.getPortId)
        val flowState = ack.getFlowstate.toByteArray

        new StateTransferAck(portId, flowState)
    }

    private def parseError(error: StateForPortResponse.Error) = {
        val code = ErrorCode.withName(error.getCode.name())
        val description = error.getDescription

        new StateTransferError(code, description)
    }

    private def toJavaUUID(protoUUID: ProtoUUID) = {
        new UUID(protoUUID.getMsb, protoUUID.getLsb)
    }
}
