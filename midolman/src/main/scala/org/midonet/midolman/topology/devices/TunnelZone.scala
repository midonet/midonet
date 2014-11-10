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

import org.midonet.cluster.data._
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.HostToIp
import org.midonet.cluster.util.{IPAddressUtil, MapConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.VirtualTopology.Device

import org.midonet.packets.IPAddr
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

/**
 * This class implements the MapConverter trait to do the conversion between
 * tuples of type (UUID, IPAddr) and HostToIp protos.
 */
class HostIpConverter extends MapConverter[UUID, IPAddr, HostToIp] {

    override def toKey(proto: HostToIp): UUID = {
        proto.getHostId.asJava
    }

    def toValue(proto: HostToIp): IPAddr = {
        IPAddressUtil.toIPAddr(proto.getIp)
    }

    def toProto(key: UUID, value: IPAddr): HostToIp = {
        HostToIp.newBuilder
            .setHostId(key.asProto)
            .setIp(IPAddressUtil.toProto(value))
            .build()
    }
}

@ZoomClass(clazz = classOf[Topology.TunnelZone])
class TunnelZone extends ZoomObject with Device {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _
    @ZoomField(name = "name")
    var name: String = _
    @ZoomField(name = "type")
    var zoneType: TunnelZoneType = _
    @ZoomField(name = "hosts", converter = classOf[HostIpConverter])
    var hosts: Map[UUID, IPAddr] = _

    private var _deviceTag: FlowTag = _

    override def afterFromProto(): Unit = {
        _deviceTag = FlowTagger.tagForDevice(id)
        super.afterFromProto()
    }

    def deviceTag = _deviceTag
}