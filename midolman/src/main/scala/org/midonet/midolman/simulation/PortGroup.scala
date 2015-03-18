/*
 * Copyright 2014 Midokura SARL
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

import com.google.protobuf.MessageOrBuilder

import org.midonet.cluster.data.ZoomConvert.ScalaZoomField
import org.midonet.cluster.data.ZoomObject
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.sdn.flows.FlowTagger

class PortGroup(@ScalaZoomField(name = "id", converter = classOf[UUIDConverter])
                val id: UUID,
                @ScalaZoomField(name = "name")
                val name: String,
                @ScalaZoomField(name = "stateful")
                val stateful: Boolean,
                @ScalaZoomField(name = "port_ids", converter = classOf[UUIDConverter])
                val members: Set[UUID])
    extends ZoomObject with VirtualDevice {

    private var _deviceTag = FlowTagger.tagForDevice(id)

    def this() = this(null, null, false, null)
    override def deviceTag = _deviceTag

    override def afterFromProto(proto: MessageOrBuilder): Unit = {
        _deviceTag = FlowTagger.tagForDevice(id)
    }
}
