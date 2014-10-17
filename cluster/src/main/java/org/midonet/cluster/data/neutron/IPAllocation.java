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
import org.midonet.packets.*;

import java.util.UUID;

public class IPAllocation {

    public IPAllocation() {}

    public IPAllocation(String ipAddress, UUID subnetId) {
        this.ipAddress = ipAddress;
        this.subnetId = subnetId;
    }

    @JsonProperty("ip_address")
    public String ipAddress;

    @JsonProperty("subnet_id")
    public UUID subnetId;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof IPAllocation)) return false;

        final IPAllocation other = (IPAllocation) obj;

        return Objects.equal(ipAddress, other.ipAddress)
                && Objects.equal(subnetId, other.subnetId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ipAddress, subnetId);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("ipAddress", ipAddress)
                .add("subnetId", subnetId).toString();
    }

    @JsonIgnore
    public IPv4Subnet ipv4Subnet() {
        return new IPv4Subnet(IPv4Addr.fromString(ipAddress), 32);
    }

    @JsonIgnore
    public IPv6Subnet ipv6Subnet() {
        if (ipAddress == null) return null;
        return new IPv6Subnet(IPv6Addr.fromString(ipAddress), 128);
    }

    @JsonIgnore
    public IPv4Addr ipv4Addr() {
        return IPv4Addr.fromString(ipAddress);
    }

    @JsonIgnore
    public IPv6Addr ipv6Addr() {
        if (ipAddress == null) return null;
        return IPv6Addr.fromString(ipAddress);
    }
}
