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
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

import java.util.UUID;

public class FloatingIp {

    public FloatingIp() {}

    public FloatingIp(UUID id, String tenantId, UUID routerId,
                      String floatingIpAddress, UUID portId,
                      String fixedIpAddress) {
        this.id = id;
        this.tenantId = tenantId;
        this.routerId = routerId;
        this.floatingIpAddress = floatingIpAddress;
        this.portId = portId;
        this.fixedIpAddress = fixedIpAddress;
    }

    public UUID id;

    @JsonProperty("floating_ip_address")
    public String floatingIpAddress;

    @JsonProperty("floating_network_id")
    public UUID floatingNetworkId;

    @JsonProperty("router_id")
    public UUID routerId;

    @JsonProperty("port_id")
    public UUID portId;

    @JsonProperty("fixed_ip_address")
    public String fixedIpAddress;

    @JsonProperty("tenant_id")
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

        return Objects.toStringHelper(this)
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
