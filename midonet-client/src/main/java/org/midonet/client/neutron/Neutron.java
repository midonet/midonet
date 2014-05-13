/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.client.neutron;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

import java.net.URI;

public class Neutron {

    public URI uri;

    public URI networks;

    @JsonProperty("network_template")
    public String networkTemplate;

    public URI subnets;

    @JsonProperty("subnet_template")
    public String subnetTemplate;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof Neutron)) return false;
        final Neutron other = (Neutron) obj;

        return Objects.equal(uri, other.uri)
                && Objects.equal(networks, other.networks)
                && Objects.equal(networkTemplate, other.networkTemplate)
                && Objects.equal(subnets, other.subnets)
                && Objects.equal(subnetTemplate, other.subnetTemplate);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uri, networks, networkTemplate, subnets,
                subnetTemplate);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("uri", uri)
                .add("networks", networks)
                .add("networkTemplate", networkTemplate)
                .add("subnets", subnets)
                .add("subnetTemplate", subnetTemplate).toString();
    }
}
