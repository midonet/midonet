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

package org.midonet.midolman.simulation

import java.util.UUID

import org.midonet.cluster.data.{ZoomObject, ZoomField, ZoomClass}
import org.midonet.cluster.models.Topology.OverlayVtep
import org.midonet.cluster.util.UUIDUtil
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.sdn.flows.FlowTagger._

@ZoomClass (clazz = classOf[OverlayVtep])
class Vtep extends ZoomObject with Coordinator.Device with VirtualDevice {

    @ZoomField(name = "id", converter = classOf[UUIDUtil.Converter])
    var id: UUID = null

    @ZoomField(name = "switch_ids", converter = classOf[UUIDUtil.Converter])
    var switchIds = List.empty[UUID]

    @ZoomField(name = "tun_port_id", converter = classOf[UUIDUtil.Converter])
    var tunPortId: UUID = null

    override val deviceTag = tagForDevice(id)

    override def process(context: PacketContext): SimulationResult = {
        null
    }
}
