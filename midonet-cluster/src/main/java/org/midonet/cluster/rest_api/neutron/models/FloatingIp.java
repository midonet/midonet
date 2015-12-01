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

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

@ZoomClass(clazz = Neutron.FloatingIp.class)
public class FloatingIp extends ZoomObject {

    public FloatingIp() {}

    public FloatingIp(UUID id, String tenantId, UUID routerId,
                      String floatingIpAddress, UUID portId,
                      String fixedIpAddress, UUID floatingNetworkId) {
        this.id = id;
        this.tenantId = tenantId;
        this.routerId = routerId;
        this.floatingIpAddress = floatingIpAddress;
        this.portId = portId;
        this.fixedIpAddress = fixedIpAddress;
        this.floatingNetworkId = floatingNetworkId;
    }

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("floating_ip_address")
    @ZoomField(name = "floating_ip_address",
               converter = IPAddressUtil.Converter.class)
    public String floatingIpAddress;

    @JsonProperty("floating_network_id")
    @ZoomField(name = "floating_network_id")
    public UUID floatingNetworkId;

    @JsonProperty("router_id")
    @ZoomField(name = "router_id")
    public UUID routerId;

    @JsonProperty("port_id")
    @ZoomField(name = "port_id")
    public UUID portId;

    @JsonProperty("fixed_ip_address")
    @ZoomField(name = "fixed_ip_address",
               converter = IPAddressUtil.Converter.class)
    public String fixedIpAddress;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof FloatingIp)) return false;
        final FloatingIp other = (FloatingIp) obj;

        return Objects.equal(id, other.id)
                && Objects.equal(floatingIpAddress, other.floatingIpAddress)
                && Objects.equal(floatingNetworkId, other.floatingNetworkId)
                && Objects.equal(tenantId, other.tenantId)
                && Objects.equal(routerId, other.routerId)
                && Objects.equal(portId, other.portId)
                && Objects.equal(fixedIpAddress, other.fixedIpAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, floatingIpAddress, floatingNetworkId,
                tenantId, routerId, portId, fixedIpAddress);
    }

    @Override
    public String toString() {

        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .add("floatingIpAddress", floatingIpAddress)
                .add("floatingNetworkId", floatingNetworkId)
                .add("tenantId", tenantId)
                .add("routerId", routerId)
                .add("portId", portId)
                .add("fixedIpAddress", fixedIpAddress).toString();
    }

    @JsonIgnore
    public IPv4Subnet floatingIpv4Subnet() {
        if (floatingIpAddress == null) return null;
        return new IPv4Subnet(floatingIpAddress, 32);
    }

    @JsonIgnore
    public IPv4Addr floatingIpv4Addr() {
        if (floatingIpAddress == null) return null;
        return IPv4Addr.fromString(floatingIpAddress);
    }

    @JsonIgnore
    public IPv4Addr fixedIpv4Addr() {
        if (fixedIpAddress == null) return null;
        return IPv4Addr.fromString(fixedIpAddress);
    }

    @JsonIgnore
    public boolean isAssociated() {
        return portId != null;
    }
}
