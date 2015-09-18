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
package org.midonet.cluster.rest_api.neutron.models;

import java.net.URI;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

public class LoadBalancer {

    @JsonProperty("health_monitors")
    public URI healthMonitors;

    @JsonProperty("health_monitor_template")
    public String healthMonitorTemplate;

    public URI members;

    @JsonProperty("member_template")
    public String memberTemplate;

    public URI pools;

    @JsonProperty("pool_template")
    public String poolTemplate;

    public URI vips;

    @JsonProperty("vip_template")
    public String vipTemplate;

    @JsonProperty("pool_health_monitor")
    public URI poolHealthMonitor;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) {
            return true;
        }

        if (!(obj instanceof LoadBalancer)) {
            return false;
        }
        final LoadBalancer other = (LoadBalancer) obj;

        return Objects.equal(healthMonitors, other.healthMonitors)
               && Objects.equal(healthMonitorTemplate,
                                other.healthMonitorTemplate)
               && Objects.equal(members, other.members)
               && Objects.equal(memberTemplate, other.memberTemplate)
               && Objects.equal(pools, other.pools)
               && Objects.equal(poolTemplate, other.poolTemplate)
               && Objects.equal(vips, other.vips)
               && Objects.equal(vipTemplate, other.vipTemplate)
               && Objects.equal(poolHealthMonitor, other.poolHealthMonitor);
    }

    @Override
    public final int hashCode() {
        return Objects.hashCode(healthMonitors, healthMonitorTemplate,
                                members, memberTemplate, pools, poolTemplate,
                                vips, vipTemplate, poolHealthMonitor);
    }

    @Override
    public final String toString() {

        return Objects.toStringHelper(this)
            .add("healthMonitors", healthMonitors)
            .add("healthMonitorTemplate", healthMonitorTemplate)
            .add("members", members)
            .add("memberTemplate", memberTemplate)
            .add("pools", pools)
            .add("poolTemplate", poolTemplate)
            .add("vips", vips)
            .add("vipTemplate", vipTemplate)
            .add("poolHealthMonitor", poolHealthMonitor).toString();
    }
}
