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

import org.midonet.cluster.models.Commons.UUID

trait StateResponse

case class StateAck(portId: UUID)
    extends StateResponse

case class StateError(code: ErrorCode.ErrorCode, description: String)
    extends StateResponse

object ErrorCode extends Enumeration {
    type ErrorCode = Value
    val GENERIC, BAD_REQUEST, STORAGE_ERROR = Value
}