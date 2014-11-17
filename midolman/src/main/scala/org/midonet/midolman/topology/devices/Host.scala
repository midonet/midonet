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

package org.midonet.midolman.topology.devices

import java.util.UUID

import scala.collection.mutable

import org.midonet.cluster.data.{ZoomField, ZoomObject}
import org.midonet.cluster.models.Topology.Host.PortToInterface
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddrConverter}
import org.midonet.cluster.util.MapConverter
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter, _}
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.packets.IPAddr
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

/**
 * This class implements the MapConverter trait to do the conversion between
 * tuples of type (UUID, String) and PortToInterface protos.
 */
class PortInterfaceConverter extends MapConverter[UUID, String, PortToInterface] {

    override def toKey(proto: PortToInterface): UUID = {
        proto.getPortId.asJava
    }

    def toValue(proto: PortToInterface): String = {
        proto.getInterfaceName
    }

    def toProto(key: UUID, value: String): PortToInterface = {
        PortToInterface.newBuilder
            .setPortId(key.asProto)
            .setInterfaceName(value)
            .build()
    }
}

class Host extends ZoomObject with VirtualDevice {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _
    @ZoomField(name = "name")
    var name: String = _
    @ZoomField(name = "addresses", converter = classOf[IPAddrConverter])
    var addresses: Set[IPAddr] = _
    @ZoomField(name = "port_interface_mapping",
               converter = classOf[PortInterfaceConverter])
    var portInterfaceMapping: Map[UUID, String] = _
    @ZoomField(name = "flooding_proxy_weight")
    var floodingProxyWeight: Int = _
    @ZoomField(name = "tunnel_zone_ids", converter = classOf[UUIDConverter])
    var tunnelZoneIds: Set[UUID] = _

    // To be filled by the HostObservable.
    // The IP address of the host in each one of the tunnel zones.
    var tunnelZones = mutable.Map[UUID, IPAddr]()

    // The alive status of the host is stored outside of the host proto.
    var alive: Boolean = _

    private var _deviceTag: FlowTag = _

    override def afterFromProto(): Unit = {
        _deviceTag = FlowTagger.tagForDevice(id)
        super.afterFromProto()
    }

    def deviceTag = _deviceTag
}
