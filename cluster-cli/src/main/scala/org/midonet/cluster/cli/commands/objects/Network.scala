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

import org.midonet.cluster.data.{ZoomObject, ZoomField}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}

/** A topology network. */
class Network extends ZoomObject {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _
    @ZoomField(name = "name")
    var name: String = _
    @ZoomField(name = "tenant_id")
    var tenandId: String = _
    @ZoomField(name = "admin_state_up")
    var adminStateUp: Boolean = _

    @ZoomField(name = "tunnel_key")
    var tunnelKey: Long = _
    @ZoomField(name = "inbound_filter_id", converter = classOf[UUIDConverter])
    var inboundFilter: UUID = _
    @ZoomField(name = "outbound_filter_id", converter = classOf[UUIDConverter])
    var outboundFilter: UUID = _

    @ZoomField(name = "port_ids", converter = classOf[UUIDConverter])
    var portIds: Set[UUID] = _
    @ZoomField(name = "vxlan_port_ids", converter = classOf[UUIDConverter])
    var vxlanPortIds: Set[UUID] = _

    override def toString =
        s"id=$id name=$name tenantId=$tenandId adminStateUp=$adminStateUp " +
        s"tunnelKey=$tunnelKey inboundFilter=$inboundFilter " +
        s"outboundFilter=$outboundFilter portIds=$portIds vxlanPortIds=$vxlanPortIds"

}
