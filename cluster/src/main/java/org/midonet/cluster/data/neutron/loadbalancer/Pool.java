/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import java.util.List;
import java.util.UUID;

import com.google.common.base.Objects;

import org.apache.commons.collections4.ListUtils;
import org.codehaus.jackson.annotate.JsonProperty;

import org.midonet.util.collection.ListUtil;

public class Pool {

    @JsonProperty("admin_state_up")
    public boolean adminStateUp;

    public String description;

    @JsonProperty("health_monitors")
    public List<UUID> healthMonitors;

    public UUID id;

    @JsonProperty("lb_method")
    public String lbMethod;

    public List<UUID> members;

    public String name;

    public String protocol;

    public String provider;

    @JsonProperty("router_id")
    public UUID routerId;

    public String status;

    @JsonProperty("status_description")
    public String statusDescription;

    @JsonProperty("subnet_id")
    public UUID subnetId;

    @JsonProperty("tenant_id")
    public String tenantId;

    @JsonProperty("vip_id")
    public String vipId;


    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Pool)) return false;
        final Pool other = (Pool) obj;

        return Objects.equal(adminStateUp, other.adminStateUp)
               && Objects.equal(description, other.description)
               && Objects.equal(healthMonitors, other.healthMonitors)
               && Objects.equal(id, other.id)
               && Objects.equal(lbMethod, other.lbMethod)
               && Objects.equal(members, other.members)
               && Objects.equal(name, other.name)
               && Objects.equal(protocol, other.protocol)
               && Objects.equal(provider, other.provider)
               && Objects.equal(routerId, other.routerId)
               && Objects.equal(status, other.status)
               && Objects.equal(subnetId, other.subnetId)
               && Objects.equal(tenantId, other.tenantId)
               && Objects.equal(vipId, other.vipId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(adminStateUp, description,
                                ListUtils.hashCodeForList(healthMonitors), id,
                                lbMethod, ListUtils.hashCodeForList(members),
                                name, protocol, provider, routerId, status,
                                subnetId, tenantId, vipId);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("adminStateUp", adminStateUp)
            .add("description", description)
            .add("healthMonitors", healthMonitors)
            .add("id", id)
            .add("lbMethod", lbMethod)
            .add("members", ListUtil.toString(members))
            .add("name", name)
            .add("protocol", protocol)
            .add("provider", provider)
            .add("routerId", routerId)
            .add("status", status)
            .add("subnetId", subnetId)
            .add("tenantId", tenantId)
            .add("vipId", vipId)
            .toString();
    }
}
