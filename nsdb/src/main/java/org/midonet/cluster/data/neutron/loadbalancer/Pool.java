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
package org.midonet.cluster.data.neutron.loadbalancer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.google.common.base.Objects;

import org.apache.commons.collections4.ListUtils;
import org.codehaus.jackson.annotate.JsonIgnore;
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
    public UUID vipId;


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

    @JsonIgnore
    public void addMember(UUID memberId) {
        if (members == null) {
            members = new ArrayList<>();
        }
        members.add(memberId);
    }

    @JsonIgnore
    public void removeMember(UUID memberId) {
        for (Iterator<UUID> it = members.iterator(); it.hasNext();) {
            if (Objects.equal(it.next(), memberId)) {
                it.remove();
                return;
            }
        }
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

    @JsonIgnore
    public boolean hasHealthMonitorAssociated() {
        return healthMonitors != null && healthMonitors.size() > 0;
    }

    @JsonIgnore
    public UUID getHealthMonitor() {
        return hasHealthMonitorAssociated() ? healthMonitors.get(0) : null;
    }
}
