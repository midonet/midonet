/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron.loadbalancer;

import java.util.List;
import com.google.common.base.Objects;
import org.apache.commons.collections4.ListUtils;
import org.codehaus.jackson.annotate.JsonProperty;
import org.midonet.util.collection.ListUtil;

import java.util.UUID;

public class Pool {

    public UUID id;

    @JsonProperty("tenant_id")
    public String tenantId;

    @JsonProperty("vip_id")
    public String vipId;

    public String name;

    public String protocol;

    @JsonProperty("networkId")
    public UUID networkId;

    public List<UUID> members;

    @JsonProperty("health_monitors")
    public List<UUID> healthMonitors;

    @JsonProperty("admin_state_up")
    public String adminStateUp;

    public String status;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Pool)) return false;
        final Pool other = (Pool) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(tenantId, other.tenantId)
                && Objects.equal(vipId, other.vipId)
                && Objects.equal(name, other.name)
                && Objects.equal(protocol, other.protocol)
                && Objects.equal(networkId, other.networkId)
                && Objects.equal(members, other.members)
                && Objects.equal(healthMonitors, other.healthMonitors)
                && Objects.equal(adminStateUp, other.adminStateUp)
                && Objects.equal(status, other.status);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, vipId, name, protocol, networkId,
                ListUtils.hashCodeForList(members),
                ListUtils.hashCodeForList(healthMonitors),
                adminStateUp, status);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("vipId", vipId)
                .add("name", name)
                .add("protocol", protocol)
                .add("networkId", networkId)
                .add("members", ListUtil.toString(members))
                .add("healthMonitors", ListUtil.toString(healthMonitors))
                .add("adminStateUp", adminStateUp)
                .add("status", status)
                .toString();
    }
}
