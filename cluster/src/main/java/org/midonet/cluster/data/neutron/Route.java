/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;

public class Route {

    public Route() {}

    public Route(String destination, String nexthop) {
        this.destination = destination;
        this.nexthop = nexthop;
    }

    public String destination;
    public String nexthop;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Route)) return false;

        final Route other = (Route) obj;

        return Objects.equal(destination, other.destination)
                && Objects.equal(nexthop, other.nexthop);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(destination, nexthop);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("destination", destination)
                .add("nexthop", nexthop).toString();
    }

}
