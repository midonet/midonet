/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.brain.services.c3po

import scala.collection.mutable.ListBuffer

import com.google.protobuf.Message

import org.midonet.brain.services.c3po.midonet.MidoOp
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology.Chain
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid


package object translators {
    type MidoOpList = List[MidoOp[_ <: Message]]
    type MidoOpListBuffer = ListBuffer[MidoOp[_ <: Message]]

    protected[translators]
    case class ChainIds(inChainId: UUID, outChainId: UUID)

    // Deterministically generate chain IDs from a device ID.
    protected[translators]
    def getChainIds(deviceId: UUID) = {
        val inChainId = deviceId.nextUuid
        ChainIds(inChainId, inChainId.nextUuid)
    }

    def createChain(id: UUID, name: String): Chain =
        Chain.newBuilder().setId(id).setName(name).build()
}
