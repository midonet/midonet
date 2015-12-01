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

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ComparisonChain;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.IPSubnetUtil;


@ZoomClass(clazz = Neutron.NeutronFirewallRule.class)
public class FirewallRule extends ZoomObject
                          implements Comparable<FirewallRule> {

    @ZoomEnum(clazz = Neutron.NeutronFirewallRule.FirewallRuleAction.class)
    public static enum RuleAction {

        @ZoomEnumValue("ALLOW")
        ALLOW("allow"),

        @ZoomEnumValue("DENY")
        DENY("deny");

        private final String value;

        RuleAction(final String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        @SuppressWarnings("unused")
        public static RuleAction forValue(String v) {
            if (v == null) return null;
            try {
                return valueOf(v.toUpperCase());
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    public FirewallRule() {}

    public FirewallRule(UUID id, UUID firewallPolicyId,
                        RuleProtocol protocol, int ipVersion,
                        String sourceIpAddress, String destinationIpAddress,
                        String sourcePort, String destinationPort,
                        RuleAction action, int position, boolean enabled) {
        this.id = id;
        this.firewallPolicyId = firewallPolicyId;
        this.protocol = protocol;
        this.ipVersion = ipVersion;
        this.protocol = protocol;
        this.sourceIpAddress = sourceIpAddress;
        this.destinationIpAddress = destinationIpAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.action = action;
        this.position = position;
        this.enabled = enabled;
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

    @JsonProperty("firewall_policy_id")
    @ZoomField(name = "firewall_policy_id")
    public UUID firewallPolicyId;

    @ZoomField(name = "shared")
    public boolean shared;

    @ZoomField(name = "protocol")
    public RuleProtocol protocol;

    @ZoomField(name = "ip_version")
    public int ipVersion;

    @JsonProperty("source_ip_address")
    @ZoomField(name = "source_ip_address",
        converter = IPSubnetUtil.Converter.class)
    public String sourceIpAddress;

    @JsonProperty("destination_ip_address")
    @ZoomField(name = "destination_ip_address",
        converter = IPSubnetUtil.Converter.class)
    public String destinationIpAddress;

    @JsonProperty("source_port")
    @ZoomField(name = "source_port")
    public String sourcePort;

    @JsonProperty("destination_port")
    @ZoomField(name = "destination_port")
    public String destinationPort;

    @ZoomField(name = "action")
    public RuleAction action;

    @ZoomField(name = "position")
    public int position;

    @ZoomField(name = "enabled")
    public boolean enabled;

    @Override
    public int compareTo(FirewallRule other) {

        return ComparisonChain.start()
            .compare(id, other.id)
            .compare(tenantId, other.tenantId)
            .compare(name, other.name)
            .compare(description, other.description)
            .compare(firewallPolicyId, other.firewallPolicyId)
            .compare(shared, other.shared)
            .compare(protocol, other.protocol)
            .compare(ipVersion, other.ipVersion)
            .compare(sourceIpAddress, other.sourceIpAddress)
            .compare(destinationIpAddress, other.destinationIpAddress)
            .compare(sourcePort, other.sourcePort)
            .compare(destinationPort, other.destinationPort)
            .compare(action, other.action)
            .compare(position, other.position)
            .compare(enabled, other.enabled).result();
    }

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof FirewallRule)) return false;
        final FirewallRule other = (FirewallRule) obj;

        return Objects.equal(id, other.id)
               && Objects.equal(tenantId, other.tenantId)
               && Objects.equal(name, other.name)
               && Objects.equal(description, other.description)
               && Objects.equal(firewallPolicyId, other.firewallPolicyId)
               && protocol == other.protocol
               && shared == other.shared
               && ipVersion == other.ipVersion
               && Objects.equal(sourceIpAddress, other.sourceIpAddress)
               && Objects.equal(destinationIpAddress,
                                other.destinationIpAddress)
               && Objects.equal(sourcePort, other.sourcePort)
               && Objects.equal(destinationPort, other.destinationPort)
               && action == other.action
               && position == other.position
               && enabled == other.enabled;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, name, description,
                                firewallPolicyId, protocol, shared, ipVersion,
                                sourceIpAddress, destinationIpAddress,
                                sourcePort, destinationPort, action, position,
                                enabled);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("id", id)
            .add("tenantId", tenantId)
            .add("name", name)
            .add("description", description)
            .add("firewallPolicyId", firewallPolicyId)
            .add("shared", shared)
            .add("protocol", protocol)
            .add("ipVersion", ipVersion)
            .add("sourceIpAddress", sourceIpAddress)
            .add("destinationIpAddress", destinationIpAddress)
            .add("sourcePort", sourcePort)
            .add("destinationPort", destinationPort)
            .add("action", action)
            .add("position", position)
            .add("enabled", enabled).toString();
    }
}
