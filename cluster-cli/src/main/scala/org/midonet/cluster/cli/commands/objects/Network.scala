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

import org.midonet.cluster.data.{ZoomClass, ZoomObject, ZoomField}
import org.midonet.cluster.models.Topology.{Network => TopologyNetwork}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}

/** A topology network. */
@ZoomClass(clazz = classOf[TopologyNetwork])
@CliName(name = "network")
final class Network extends ZoomObject with Obj {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    @CliName(name = "id")
    var id: UUID = _
    @ZoomField(name = "name")
    @CliName(name = "name", readonly = false)
    var name: String = _
    @ZoomField(name = "tenant_id")
    @CliName(name = "tenant-id", readonly = false)
    var tenandId: String = _
    @ZoomField(name = "admin_state_up")
    @CliName(name = "admin-state-up", readonly = false)
    var adminStateUp: Boolean = _

    @ZoomField(name = "tunnel_key")
    @CliName(name = "tunnel-key", readonly = false)
    var tunnelKey: Long = _
    @ZoomField(name = "inbound_filter_id", converter = classOf[UUIDConverter])
    @CliName(name = "inbound-filter-id", readonly = false)
    var inboundFilter: UUID = _
    @ZoomField(name = "outbound_filter_id", converter = classOf[UUIDConverter])
    @CliName(name = "outbound-filter-id", readonly = false)
    var outboundFilter: UUID = _

    @ZoomField(name = "port_ids", converter = classOf[UUIDConverter])
    @CliName(name = "port-ids")
    var portIds: Set[UUID] = _
    @ZoomField(name = "vxlan_port_ids", converter = classOf[UUIDConverter])
    @CliName(name = "vxlan-port-ids")
    var vxlanPortIds: Set[UUID] = _
    @ZoomField(name = "dhcp_ids", converter = classOf[UUIDConverter])
    @CliName(name = "dhcp-ids")
    var dhcpIds: Set[UUID] = _

}
