/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

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

}
