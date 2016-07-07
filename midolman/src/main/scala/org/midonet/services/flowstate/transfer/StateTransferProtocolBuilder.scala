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

import org.midonet.cluster.flowstate.FlowStateTransfer._
import org.midonet.cluster.flowstate.FlowStateTransfer.StateResponse.Error.Code
import org.midonet.cluster.models.Commons
import org.midonet.cluster.util.UUIDUtil.toProto

/*
 * Helper for building flow state transfer Protocol Buffers objects
 */

object StateTransferProtocolBuilder {

    def buildStateRequestInternal(portId: UUID) = {
        @inline def internal = StateRequest.Internal.newBuilder()
            .setPortId(toProto(portId))
            .build()

        StateRequest.newBuilder()
            .setInternal(internal)
            .build()
    }

    def buildStateRequestRemote(portId: UUID, address: String) = {
        @inline def remoteIp = Commons.IPAddress.newBuilder()
                .setAddress(address)
                .setVersion(Commons.IPVersion.V4)
                .build()

        @inline def remote = StateRequest.Remote.newBuilder()
            .setPortId(toProto(portId))
            .setRemoteIp(remoteIp)
            .build()

        StateRequest.newBuilder()
            .setRemote(remote)
            .build()
    }

    def buildStateRequestRaw(portId: UUID) = {
        @inline def raw = StateRequest.Raw.newBuilder()
            .setPortId(toProto(portId))
            .build()

        StateRequest.newBuilder()
            .setRaw(raw)
            .build()
    }

    def buildAck(portId: UUID) = {
        @inline def ack = StateResponse.Ack.newBuilder()
            .setPortId(toProto(portId))
            .build()

        StateResponse.newBuilder()
            .setAck(ack)
            .build()
    }

    def buildError(code: Code, e: Throwable) = {
        @inline def error = StateResponse.Error.newBuilder()
            .setCode(code)
            .setDescription(
                Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
            )
            .build()

        StateResponse.newBuilder()
            .setError(error)
            .build()
    }

}
