/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
