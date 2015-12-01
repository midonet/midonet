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

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ComparisonChain;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPv4Subnet;
import org.midonet.util.Range;

@ZoomClass(clazz = Neutron.SecurityGroupRule.class)
public class SecurityGroupRule extends ZoomObject
                               implements Comparable<SecurityGroupRule> {

    public SecurityGroupRule() {}

    public SecurityGroupRule(UUID id, UUID sgId, RuleDirection direction,
                             RuleEthertype ethertype, RuleProtocol protocol) {
        this.id = id;
        this.securityGroupId = sgId;
        this.direction = direction;
        this.ethertype = ethertype;
        this.protocol = protocol;
    }

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("security_group_id")
    @ZoomField(name = "security_group_id")
    public UUID securityGroupId;

    @JsonProperty("remote_group_id")
    @ZoomField(name = "remote_group_id")
    public UUID remoteGroupId;

    @ZoomField(name = "direction")
    public RuleDirection direction;

    @ZoomField(name = "protocol")
    public RuleProtocol protocol;

    @JsonProperty("port_range_min")
    @ZoomField(name = "port_range_min")
    public Integer portRangeMin;

    @JsonProperty("port_range_max")
    @ZoomField(name = "port_range_max")
    public Integer portRangeMax;

    @ZoomField(name = "ethertype")
    public RuleEthertype ethertype;

    @JsonProperty("remote_ip_prefix")
    @ZoomField(name = "remote_ip_prefix")
    public String remoteIpPrefix;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @Override
    public int compareTo(SecurityGroupRule other) {

        return ComparisonChain.start()
                .compare(id, other.id)
                .compare(securityGroupId, other.securityGroupId)
                .compare(direction, other.direction)
                .compare(tenantId, other.tenantId)
                .compare(remoteGroupId, other.remoteGroupId)
                .compare(protocol, other.protocol)
                .compare(portRangeMin, other.portRangeMin)
                .compare(portRangeMax, other.portRangeMax)
                .compare(remoteIpPrefix, other.remoteIpPrefix)
                .compare(ethertype, other.ethertype).result();
    }

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof SecurityGroupRule)) return false;
        final SecurityGroupRule other = (SecurityGroupRule) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(securityGroupId, other.securityGroupId)
                && Objects.equal(remoteGroupId, other.remoteGroupId)
                && Objects.equal(tenantId, other.tenantId)
                && Objects.equal(direction, other.direction)
                && Objects.equal(protocol, other.protocol)
                && Objects.equal(ethertype, other.ethertype)
                && Objects.equal(remoteIpPrefix, other.remoteIpPrefix)
                && Objects.equal(portRangeMax, other.portRangeMax)
                && Objects.equal(portRangeMin, other.portRangeMin);
    }

    @Override
    public int hashCode() {

        return Objects.hashCode(id, securityGroupId, remoteGroupId, direction,
                protocol, portRangeMax, portRangeMin, tenantId, ethertype,
                remoteIpPrefix);
    }

    @Override
    public String toString() {

        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("securityGroupId", securityGroupId)
                .add("remoteGroupId", remoteGroupId)
                .add("direction", direction)
                .add("protocol", protocol)
                .add("portRangeMin", portRangeMin)
                .add("portRangeMax", portRangeMax)
                .add("ethertype", ethertype)
                .add("remoteIpPrefix", remoteIpPrefix)
                .add("tenantId", tenantId).toString();
    }

    @JsonIgnore
    public boolean isEgress() {
        return direction == RuleDirection.EGRESS;
    }

    @JsonIgnore
    public boolean isIngress() {
        return direction == RuleDirection.INGRESS;
    }

    @JsonIgnore
    public Byte protocolNumber() {
        if (protocol == null) return null;
        return protocol.number();
    }

    @JsonIgnore
    public Integer ethertype() {
        if (ethertype == null) return null;
        return ethertype.number();
    }

    @JsonIgnore
    public IPv4Subnet remoteIpv4Subnet() {
        if (remoteIpPrefix == null) return null;
        return IPv4Subnet.fromCidr(remoteIpPrefix);
    }

    @JsonIgnore
    public Range<Integer> portRange() {
        if (portRangeMin == null && portRangeMax == null) return null;

        if(protocol != null && protocol.number() == ICMP.PROTOCOL_NUMBER) {
            return new Range<>(portRangeMax, portRangeMax);
        } else {
            return new Range<>(portRangeMin, portRangeMax);
        }
    }
}
