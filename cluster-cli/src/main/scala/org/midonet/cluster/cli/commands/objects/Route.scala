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

package org.midonet.cluster.cli.commands.objects

import java.util.UUID

import org.midonet.cluster.data.{ZoomObject, ZoomClass, ZoomField}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.cluster.util.IPSubnetUtil.{Converter => IPSubnetConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.packets.{IPAddr, IPSubnet}

/** A route. */
@ZoomClass(clazz = classOf[Topology.Route])
@CliName(name = "route")
class Route extends ZoomObject with Obj {
    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    @CliName(name = "id")
    var id: UUID = _
    @ZoomField(name = "router_id", converter = classOf[UUIDConverter])
    @CliName(name = "router-id")
    var routerId: UUID = _
    @ZoomField(name = "src_subnet", converter = classOf[IPSubnetConverter])
    @CliName(name = "src-network", readonly = false)
    var srcNetwork: IPSubnet[_] = _
    @ZoomField(name = "dst_subnet", converter = classOf[IPSubnetConverter])
    @CliName(name = "dst-network", readonly = false)
    var dstNetwork: IPSubnet[_] = _
    @ZoomField(name = "next_hop")
    @CliName(name = "next-hop", readonly = false)
    var nextHop: RouteNextHop = _
    @ZoomField(name = "next_hop_port_id", converter = classOf[UUIDConverter])
    @CliName(name = "next-hop-port-id", readonly = false)
    var nextHopPort: UUID = _
    @ZoomField(name = "next_hop_gateway", converter = classOf[IPAddressConverter])
    @CliName(name = "next-hop-gateway", readonly = false)
    var nextHopGateway: IPAddr = _
    @ZoomField(name = "weight")
    @CliName(name = "weight", readonly = false)
    var weight: Int = _
    @ZoomField(name = "attributes")
    @CliName(name = "attributes", readonly = false)
    var attributes: String = _
}
