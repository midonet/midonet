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

package org.midonet.midolman.topology.devices

import java.util.UUID

import org.midonet.cluster.data.{ZoomClass, ZoomField, ZoomObject}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.midolman.topology.VirtualTopology.Device

@ZoomClass(clazz = classOf[Topology.PoolHealthMonitor])
class PoolHealthMonitor extends ZoomObject with Device {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _

    @ZoomField(name = "health_monitor_id", converter = classOf[UUIDConverter])
    var healthMonitorId: UUID = _

    @ZoomField(name = "load_balancer_id", converter = classOf[UUIDConverter])
    var loadBalancerId: UUID = _

    @ZoomField(name = "vip_ids", converter = classOf[UUIDConverter])
    var vipIds: Set[UUID] = _

    @ZoomField(name = "pool_member_ids", converter = classOf[UUIDConverter])
    var poolMemberIds: Set[UUID] = _

    override def toString =
        s"PoolHealthMonitor [id=$id healtMonitorId=$healthMonitorId " +
        s"loadBalancerId=$loadBalancerId vipIds=$vipIds " +
        s"poolMemberIds=$poolMemberIds]"
}

case class PoolHealthMonitorMap(mappings: Map[UUID, PoolHealthMonitor])
    extends Device

