/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.UUID;

public class ProviderRouter {

    public static final String NAME = "MidoNet Provider Router";

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
