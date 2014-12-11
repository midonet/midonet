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

import org.midonet.cluster.data.TunnelZone.HostConfig
import org.midonet.cluster.data.{TunnelZone => OldTunnelZone, _}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter, _}
import org.midonet.cluster.util.{IPAddressUtil, MapConverter}
import org.midonet.midolman.topology.HostConfigOperation
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{ZoneChanged, ZoneMembers}
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.TunnelZone.HostIpConverter
import org.midonet.packets.{IPAddr, IPv4Addr}

object TunnelZone {

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

    /**
     * This method is only to be used during the transition between the old and
     * the new cluster design.
     */
    @Deprecated
    def toOldTunnelZoneType(newType: TunnelZoneType): OldTunnelZone.Type =
        newType match {

        case TunnelZoneType.GRE => OldTunnelZone.Type.gre
        case TunnelZoneType.VTEP => OldTunnelZone.Type.vtep
        case TunnelZoneType.VXLAN => OldTunnelZone.Type.vxlan
        case _ => throw new IllegalArgumentException("Unsupported tunnel type: "
                                                     + s"$newType")
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

    /**
     * Extracts the hosts field of the tunnel zone as a ZoneMembers object.
     * This method is only to be used during the transition between the old and
     * the new cluster design.
     */
    @Deprecated
    def toZoneMembers: ZoneMembers = {
        val hostSet = hosts.map(host =>
            new HostConfig(host._1).setIp(host._2.asInstanceOf[IPv4Addr])
        ).toSet
        new ZoneMembers(id, TunnelZone.toOldTunnelZoneType(zoneType), hostSet)
    }

    /**
     * Extracts the difference in one host between this TunnelZone and the zone
     * members passed as parameter.
     * This method is only to be used during the transition between the old and
     * the new cluster design.
     */
    @Deprecated
    def diffMembers(oldZone: ZoneMembers): ZoneChanged = {
        val oldZoneSize = oldZone.members.size

        if (oldZoneSize > hosts.keySet.size) {
            val removed = oldZone.members.map(_.getId).diff(hosts.keySet).toList.head
            val removedMember = oldZone.members.find(_.getId.eq(removed)).get
            new ZoneChanged(id, TunnelZone.toOldTunnelZoneType(zoneType),
                            removedMember, HostConfigOperation.Deleted)
        } else {
            val added = hosts.keySet.diff(oldZone.members.map(_.getId).toSet).toList.head
            val addedMember = new HostConfig(added).setIp(hosts.get(added).get
                                                              .asInstanceOf[IPv4Addr])
            new ZoneChanged(id, TunnelZone.toOldTunnelZoneType(zoneType), addedMember,
                            HostConfigOperation.Added)
        }
    }
}