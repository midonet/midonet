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

package org.midonet.midolman.monitoring

import java.nio.ByteBuffer
import java.util.UUID

import org.midonet.cluster.flowhistory.JsonSerialization
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.PacketWorkflow.{SimulationResult => MMSimRes}
import org.midonet.midolman.config.FlowHistoryConfig
import org.midonet.midolman.simulation.PacketContext

class JsonFlowRecorder(hostId: UUID, config: FlowHistoryConfig,
                       backend: MidonetBackend)
        extends AbstractFlowRecorder(config, backend) {

    val serializer = new JsonSerialization

    override def encodeRecord(pktContext: PacketContext,
                              simRes: MMSimRes): ByteBuffer = {
        ByteBuffer.wrap(
            serializer.flowRecordToBuffer(
                FlowRecordBuilder.buildRecord(hostId, pktContext, simRes)))
    }
}

