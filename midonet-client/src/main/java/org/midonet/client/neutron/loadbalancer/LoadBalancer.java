/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.client.neutron.loadbalancer;

import java.net.URI;

import com.google.common.base.Objects;

import org.codehaus.jackson.annotate.JsonProperty;

public class LoadBalancer {

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
