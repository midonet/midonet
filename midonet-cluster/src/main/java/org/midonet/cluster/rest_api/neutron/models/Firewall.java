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

package org.midonet.cluster.rest_api.neutron.models;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.apache.commons.collections4.ListUtils;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.util.collection.ListUtil;


@ZoomClass(clazz = Neutron.NeutronFirewall.class)
public class Firewall extends ZoomObject {

    public Firewall() {}

    public Firewall(UUID id, UUID firewallPolicyId, boolean adminStateUp,
                    List<FirewallRule> firewallRuleList,
                    List<UUID> addRouterIds, List<UUID> delRouterIds) {
        this.id = id;
        this.firewallPolicyId = firewallPolicyId;
        this.adminStateUp = adminStateUp;
        this.firewallRuleList = firewallRuleList;
        this.addRouterIds = addRouterIds;
        this.delRouterIds = delRouterIds;
    }

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @ZoomField(name = "shared")
    public boolean shared;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp;

    @ZoomField(name = "status")
    public String status;

    @JsonProperty("firewall_policy_id")
    @ZoomField(name = "firewall_policy_id")
    public UUID firewallPolicyId;

    @JsonProperty("firewall_rule_list")
    @ZoomField(name = "firewall_rule_list")
    public List<FirewallRule> firewallRuleList;

    @JsonProperty("add-router-ids")
    @ZoomField(name = "add_router_ids")
    public List<UUID> addRouterIds;

    @JsonProperty("del-router-ids")
    @ZoomField(name = "del_router_ids")
    public List<UUID> delRouterIds;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Firewall)) return false;
        final Firewall other = (Firewall) obj;

        return Objects.equal(id, other.id)
               && Objects.equal(tenantId, other.tenantId)
               && Objects.equal(name, other.name)
               && Objects.equal(description, other.description)
               && shared == other.shared
               && adminStateUp == other.adminStateUp
               && Objects.equal(status, other.status)
               && Objects.equal(firewallPolicyId, other.firewallPolicyId)
               && ListUtils.isEqualList(firewallRuleList,
                                        other.firewallRuleList)
               && ListUtils.isEqualList(addRouterIds, other.addRouterIds)
               && ListUtils.isEqualList(delRouterIds, other.delRouterIds);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, name, description, shared,
                                adminStateUp, status, firewallPolicyId,
                                ListUtils.hashCodeForList(firewallRuleList),
                                ListUtils.hashCodeForList(addRouterIds),
                                ListUtils.hashCodeForList(delRouterIds));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("id", id)
            .add("tenantId", tenantId)
            .add("name", name)
            .add("description", description)
            .add("shared", shared)
            .add("adminStateUp", adminStateUp)
            .add("status", status)
            .add("firewallPolicyId", firewallPolicyId)
            .add("firewallRuleList", ListUtil.toString(firewallRuleList))
            .add("addRouterIds", ListUtil.toString(addRouterIds))
            .add("delRouterIds", ListUtil.toString(delRouterIds))
            .toString();
    }
}
