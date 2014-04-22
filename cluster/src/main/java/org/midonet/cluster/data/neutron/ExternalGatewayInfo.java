/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.UUID;

public class ExternalGatewayInfo {

    public ExternalGatewayInfo() {}

    public ExternalGatewayInfo(UUID networkId, Boolean enableSnat) {
        this.networkId = networkId;
        this.enableSnat = enableSnat;
    }

    @JsonProperty("network_id")
    public UUID networkId;

    @JsonProperty("enable_snat")
    public boolean enableSnat;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof ExternalGatewayInfo)) return false;

        final ExternalGatewayInfo other = (ExternalGatewayInfo) obj;

        return Objects.equal(networkId, other.networkId)
                && Objects.equal(enableSnat, other.enableSnat);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(networkId, enableSnat);
    }

    @Override
    public String toString() {

        return Objects.toStringHelper(this)
                .add("networkId", networkId)
                .add("enableSnat", enableSnat).toString();
    }
}
