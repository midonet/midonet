/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

import java.util.UUID;

public class ProviderRouter {

    public static final String NAME = "MidoNet Provider Router";
    public static final IPv4Subnet LL_CIDR = new IPv4Subnet(
            "169.254.255.0", 30);
    public static final IPv4Addr LL_GW_IP_1 = IPv4Addr.fromString(
            "169.254.255.1");
    public static final IPv4Addr LL_GW_IP_2 = IPv4Addr.fromString(
            "169.254.255.2");

    public ProviderRouter() {}

    public ProviderRouter(UUID id) {
        this.id = id;
    }

    public UUID id;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof ProviderRouter)) return false;
        final ProviderRouter other = (ProviderRouter) obj;

        return Objects.equal(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);

    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id).toString();
    }
}
