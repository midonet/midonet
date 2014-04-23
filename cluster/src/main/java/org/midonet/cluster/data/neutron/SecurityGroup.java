/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.apache.commons.collections4.ListUtils;
import org.codehaus.jackson.annotate.JsonProperty;
import org.midonet.util.collection.ListUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SecurityGroup {

    public UUID id;

    public String name;

    public String description;

    @JsonProperty("tenant_id")
    public String tenantId;

    @JsonProperty("security_group_rules")
    public List<SecurityGroupRule> securityGroupRules = new ArrayList<>();

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof SecurityGroup)) return false;
        final SecurityGroup other = (SecurityGroup) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(name, other.name)
                && Objects.equal(description, other.description)
                && Objects.equal(tenantId, other.tenantId)
                && ListUtils.isEqualList(
                    securityGroupRules, other.securityGroupRules);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, name, description, tenantId,
                ListUtils.hashCodeForList(securityGroupRules));
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("id", id)
                .add("name", name)
                .add("description", description)
                .add("tenantId", tenantId)
                .add("securityGroupRules",
                        ListUtil.toString(securityGroupRules))
                .toString();
    }
}
