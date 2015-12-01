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
import org.midonet.midolman.state.l4lb.{HealthMonitorType, LBStatus}
import org.midonet.midolman.topology.VirtualTopology.Device

@ZoomClass(clazz = classOf[Topology.HealthMonitor])
class HealthMonitor extends ZoomObject with Device {

    @ZoomField(name = "id")
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

    @ZoomField(name = "max_retries")
    var maxRetries: Int = _

    def this(id: UUID, adminStateUp: Boolean, t: HealthMonitorType,
             status: LBStatus, delay: Int, timeout: Int, maxRetries: Int) = {
        this()
        this.id = id
        this.adminStateUp = adminStateUp
        this.healthMonitorType = t
        this.status = status
        this.delay = delay
        this.timeout = timeout
        this.maxRetries = maxRetries
    }

    override def toString =
        s"HealthMonitor [id=$id adminStateUp=$adminStateUp " +
        s"healthMonitorType=$healthMonitorType status=$status " +
        s"delay=$delay timeout=$timeout maxRetries=$maxRetries]"
}

