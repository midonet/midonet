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
package org.midonet.services.flowstate.transfer.internal

import java.util.UUID

abstract class StateTransferResponse

case class StateTransferAck(portId: UUID, flowState: Array[Byte])
    extends StateTransferResponse

case class StateTransferError(code: ErrorCode.ErrorCode, description: String)
    extends StateTransferResponse

object ErrorCode extends Enumeration {
    type ErrorCode = Value
    val GENERIC = Value("GENERIC")
    val BAD_REQUEST = Value("BAD_REQUEST")
    val STORAGE_ERROR = Value("STORAGE_ERROR")
}