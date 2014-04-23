/*
 * Copyright 2014 Midokura Pte. Ltd.
 */
package org.midonet.cluster.data.neutron;


import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

public class IPAllocationPool {

    public IPAllocationPool() {}

    public IPAllocationPool(String firstIp, String lastIp) {
        this.firstIp = firstIp;
        this.lastIp = lastIp;
    }

    @JsonProperty("first_ip")
    public String firstIp;

    @JsonProperty("last_ip")
    public String lastIp;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof IPAllocationPool)) return false;

        final IPAllocationPool other = (IPAllocationPool) obj;

        return Objects.equal(firstIp, other.firstIp)
                && Objects.equal(lastIp, other.lastIp);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(firstIp, lastIp);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("firstIp", firstIp)
                .add("lastIp", lastIp).toString();
    }

}
