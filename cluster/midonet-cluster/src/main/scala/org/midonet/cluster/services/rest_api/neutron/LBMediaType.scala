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
package org.midonet.cluster.services.rest_api.neutron

object LBMediaType {
    final val VIP_JSON_V1 = "application/vnd.org.midonet.neutron.lb.Vip-v1+json"
    final val VIPS_JSON_V1 = "application/vnd.org.midonet.neutron.lb.Vips-v1+json"
    final val POOL_JSON_V1 = "application/vnd.org.midonet.neutron.lb.Pool-v1+json"
    final val POOLS_JSON_V1 = "application/vnd.org.midonet.neutron.lb.Pools-v1+json"
    final val MEMBER_JSON_V1 = "application/vnd.org.midonet.neutron.lb.PoolMember-v1+json"
    final val MEMBERS_JSON_V1 = "application/vnd.org.midonet.neutron.lb.PoolMembers-v1+json"
    final val HEALTH_MONITOR_JSON_V1 = "application/vnd.org.midonet.neutron.lb.HealthMonitor-v1+json"
    final val HEALTH_MONITORS_JSON_V1 = "application/vnd.org.midonet.neutron.lb.HealthMonitors-v1+json"
    final val POOL_HEALTH_MONITOR_JSON_V1 = "application/vnd.org.midonet.neutron.lb.PoolHealthMonitor-v1+json"
}