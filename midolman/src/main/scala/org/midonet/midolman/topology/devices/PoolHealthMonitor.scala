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

import scala.collection.JavaConversions.iterableAsScalaIterable

import org.midonet.midolman.simulation.{Vip, LoadBalancer, PoolMember}
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.packets.IPv4Addr

case class PoolHealthMonitor(healthMonitor: HealthMonitor,
                             loadBalancer: LoadBalancer,
                             vips: Iterable[Vip],
                             poolMembers: Iterable[PoolMember]) {

    override def toString =
        s"PoolHealthMonitor [healtMonitor=$healthMonitor " +
        s"loadBalancer=$loadBalancer vips=$vips " +
        s"poolMembers=$poolMembers]"
}

object PoolHealthMonitor {
    // Conversion from legacy model
    @Deprecated
    def fromConfig(poolId: UUID, phmc: PoolHealthMonitorConfig): PoolHealthMonitor = {
        val hm = new HealthMonitor(phmc.healthMonitorConfig.persistedId,
                                   phmc.healthMonitorConfig.config.adminStateUp,
                                   phmc.healthMonitorConfig.config.`type`,
                                   phmc.healthMonitorConfig.config.status,
                                   phmc.healthMonitorConfig.config.delay,
                                   phmc.healthMonitorConfig.config.timeout,
                                   phmc.healthMonitorConfig.config.maxRetries)

        val vips = iterableAsScalaIterable(phmc.vipConfigs).map(v => new Vip(
            v.persistedId,
            v.config.adminStateUp,
            v.config.poolId,
            if (v.config.address == null) null
            else IPv4Addr.fromString(v.config.address),
            v.config.protocolPort,
            v.config.sessionPersistence))

        val lb = new LoadBalancer(phmc.loadBalancerConfig.persistedId,
                                  phmc.loadBalancerConfig.config.adminStateUp,
                                  phmc.loadBalancerConfig.config.routerId,
                                  vips.toArray)

        val pms = iterableAsScalaIterable(phmc.poolMemberConfigs).map(
            pm => new PoolMember(pm.persistedId,
                                 pm.config.adminStateUp,
                                 pm.config.status,
                                 if (pm.config.address == null) null
                                 else IPv4Addr.fromString(pm.config.address),
                                 pm.config.protocolPort,
                                 pm.config.weight))

        PoolHealthMonitor(hm, lb, vips.filter(_.poolId == poolId), pms)
    }
}

case class PoolHealthMonitorMap(mappings: Map[UUID, PoolHealthMonitor])
    extends Device

