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

import org.midonet.cluster.data.{ZoomField, ZoomObject, ZoomClass}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.midolman.state.l4lb.{HealthMonitorType, LBStatus}
import org.midonet.midolman.topology.VirtualTopology.Device

@ZoomClass(clazz = classOf[Topology.HealthMonitor])
class HealthMonitor extends ZoomObject with Device {

    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _

    @ZoomField(name = "admin_state_up")
    var adminStateUp: Boolean = _

    @ZoomField(name = "type")
    var healthMonitorType: HealthMonitorType = _

    @ZoomField(name = "status")
    var status: LBStatus = _

    @ZoomField(name = "delay")
    var delay: Int = _

    @ZoomField(name = "timeout")
    var timeout: Int = _

    @ZoomField(name = "maxRetries")
    var maxRetries: Int = _

    override def toString =
        s"HealthMonitor [id=$id adminStateUp=$adminStateUp " +
        s"healthMonitorType=$healthMonitorType status=$status " +
        s"delay=$delay timeout=$timeout maxRetries=$maxRetries]"
}

