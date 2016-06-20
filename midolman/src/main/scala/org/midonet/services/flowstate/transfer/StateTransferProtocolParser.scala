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

package org.midonet.services.flowstate.transfer

import java.util.UUID

import org.midonet.cluster.flowstate.FlowStateTransfer.{StateRequest, StateResponse}
import org.midonet.cluster.models.Commons.{UUID => ProtoUUID}
import org.midonet.services.flowstate.transfer.internal._

/*
 * Helper object to parse different Protocol Buffer State Transfer Protocol
 * responses to internal objects that can be handled with more ease
 */
object StateTransferProtocolParser {

    def parseStateRequest(request: StateRequest) = {
        if (request.hasInternal) {
            parseStateRequestInternal(request.getInternal)
        } else if (request.hasRemote) {
            parseStateRequestRemote(request.getRemote)
        } else if (request.hasRaw) {
            parseStateRequestRaw(request.getRaw)
        } else {
            throw new IllegalArgumentException(s"Can not parse empty" +
                s" ${request.getClass.getSimpleName}")
        }
    }

    private def parseStateRequestInternal(internal: StateRequest.Internal) = {
        val portId = toJavaUUID(internal.getPortId)

        new StateRequestInternal(portId)
    }

    private def parseStateRequestRemote(transfer: StateRequest.Remote) = {
        val portId = toJavaUUID(transfer.getPortId)
        val host = transfer.getHost

        new StateRequestRemote(portId, host)
    }

    private def parseStateRequestRaw(raw: StateRequest.Raw) = {
        val portId = toJavaUUID(raw.getPortId)

        new StateRequestRaw(portId)
    }

    def parseStateResponse(response: StateResponse) = {
        if (response.hasAckInternal) {
            parseAckInternal(response.getAckInternal)
        } else if (response.hasAckRemote) {
            parseAckRemote(response.getAckRemote)
        } else if (response.hasAckRaw) {
            parseAckRaw(response.getAckRaw)
        } else if (response.hasError) {
            parseError(response.getError)
        } else {
            throw new IllegalArgumentException(s"Can not parse empty" +
                s" ${response.getClass.getSimpleName}")
        }
    }

    private def parseAckInternal(ack: StateResponse.AckInternal) = {
        val portId = toJavaUUID(ack.getPortId)

        new StateAckInternal(portId)
    }

    private def parseAckRemote(ack: StateResponse.AckRemote) = {
        val portId = toJavaUUID(ack.getPortId)

        new StateAckRemote(portId)
    }

    private def parseAckRaw(ack: StateResponse.AckRaw) = {
        val portId = toJavaUUID(ack.getPortId)

        new StateAckRaw(portId)
    }

    private def parseError(error: StateResponse.Error) = {
        val code = ErrorCode.withName(error.getCode.name())
        val description = error.getDescription

        new StateError(code, description)
    }

    private def toJavaUUID(protoUUID: ProtoUUID) = {
        new UUID(protoUUID.getMsb, protoUUID.getLsb)
    }
}
