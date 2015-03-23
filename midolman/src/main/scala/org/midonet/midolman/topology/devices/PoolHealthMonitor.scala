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

import scala.collection.JavaConversions.seqAsJavaList

import org.midonet.midolman.l4lb.PoolHealthMonitorMapManager.PoolHealthMonitorLegacyMap
import org.midonet.midolman.simulation.{VIP, LoadBalancer, PoolMember}
import org.midonet.midolman.state.l4lb.VipSessionPersistence
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager.HealthMonitorConfig
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager.LoadBalancerConfig
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager.PoolMemberConfig
import org.midonet.midolman.state.zkManagers.VipZkManager.VipConfig
import org.midonet.midolman.topology.VirtualTopology.Device

case class PoolHealthMonitor(healthMonitor: HealthMonitor,
                             loadBalancer: LoadBalancer,
                             vips: Iterable[VIP],
                             poolMembers: Iterable[PoolMember]) {

    override def toString =
        s"PoolHealthMonitor [healtMonitor=$healthMonitor " +
        s"loadBalancer=$loadBalancer vips=$vips " +
        s"poolMembers=$poolMembers]"

    // Convert to legacy config
    @Deprecated
    def toConfig: PoolHealthMonitorConfig = PoolHealthMonitor.toConfig(this)
}

object PoolHealthMonitor {
    // Conversion to legacy model
    @Deprecated
    def toConfig(lb: LoadBalancer): LoadBalancerConfig =
        new LoadBalancerConfig(lb.routerId, lb.adminStateUp)
    @Deprecated
    def toConfig(vip: VIP): VipConfig =
        new VipConfig(vip.loadBalancerId, vip.poolId,
                      if (vip.address == null) null else vip.address.toString,
                      vip.protocolPort,
                      if (vip.isStickySourceIP) VipSessionPersistence.SOURCE_IP
                      else null,
                      vip.adminStateUp)
    @Deprecated
    def toConfig(pm: PoolMember): PoolMemberConfig =
        new PoolMemberConfig(pm.id,
                             if (pm.address == null) null
                             else pm.address.toString,
                             pm.protocolPort, pm.weight,
                             pm.adminStateUp, pm.status)
    @Deprecated
    def toConfig(hm: HealthMonitor): HealthMonitorConfig =
        new HealthMonitorConfig(hm.healthMonitorType, hm.delay, hm.timeout,
                                hm.maxRetries, hm.adminStateUp, hm.status)
    @Deprecated
    def toConfig(phm: PoolHealthMonitor): PoolHealthMonitorConfig =
        new PoolHealthMonitorConfig(
            toConfig(phm.loadBalancer),
            seqAsJavaList(phm.vips.map(toConfig).toSeq),
            seqAsJavaList(phm.poolMembers.map(toConfig).toSeq),
            toConfig(phm.healthMonitor))
}

case class PoolHealthMonitorMap(mappings: Map[UUID, PoolHealthMonitor])
    extends Device {
    @Deprecated
    def toConfig: PoolHealthMonitorLegacyMap =
        PoolHealthMonitorLegacyMap(mappings.mapValues(PoolHealthMonitor.toConfig))
}

