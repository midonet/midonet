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

import org.midonet.cluster.flowstate.FlowStateTransfer.{StateRequest, StateResponse}
import org.midonet.services.flowstate.transfer.internal._

/*
 * Helper object to parse different Protocol Buffer State Transfer Protocol
 * responses to internal objects that can be handled with more ease
 */
object StateTransferProtocolParser {

    def parseStateRequest(request: StateRequest) = {
        if (request.hasInternal) {
            new StateRequestInternal(request.getInternal.getPortId)
        } else if (request.hasRemote) {
            new StateRequestRemote(request.getRemote.getPortId,
                request.getRemote.getRemoteIp)
        } else if (request.hasRaw) {
            new StateRequestRaw(request.getRaw.getPortId)
        } else {
            throw new IllegalArgumentException(s"Can not parse empty" +
                s" ${request.getClass.getSimpleName}")
        }
    }

    def parseStateResponse(response: StateResponse) = {
        if (response.hasAck) {
            new StateAck(response.getAck.getPortId)
        } else if (response.hasError) {
            parseError(response.getError)
        } else {
            throw new IllegalArgumentException(s"Can not parse empty" +
                s" ${response.getClass.getSimpleName}")
        }
    }

    private def parseError(error: StateResponse.Error) = {
        val code = ErrorCode.withName(error.getCode.name())
        val description = error.getDescription

        new StateError(code, description)
    }

}
