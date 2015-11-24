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
package org.midonet.cluster.rest_api.models;

import java.net.URI;

import javax.ws.rs.core.UriBuilder;

import com.google.common.base.Objects;

import static org.midonet.cluster.rest_api.ResourceUris.BRIDGES;
import static org.midonet.cluster.rest_api.ResourceUris.CHAINS;
import static org.midonet.cluster.rest_api.ResourceUris.PORT_GROUPS;
import static org.midonet.cluster.rest_api.ResourceUris.ROUTERS;
import static org.midonet.cluster.rest_api.ResourceUris.TENANTS;
import static org.midonet.cluster.rest_api.ResourceUris.TENANT_ID_PARAM;

/**
 * Class representing a tenant object in the identity service.  This is the
 * generic MidoNet representation of tenant models from various identity
 * services that it could integrate with.
 */
public class Tenant extends UriResource {

    public String id;
    public String name;
    public String description;
    public boolean enabled;

    public Tenant(){
    }

    public Tenant(URI baseUri, String id, String name, String description,
                  boolean enabled) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.enabled = enabled;
        this.setBaseUri(baseUri);
    }

    @Override
    public URI getUri() {
        return absoluteUri(TENANTS(), id);
    }

    public URI getRouters() {
        return UriBuilder.fromUri(getBaseUri())
                         .path(ROUTERS())
                         .queryParam(TENANT_ID_PARAM(), id)
                         .build();
    }

    public URI getBridges() {
        return UriBuilder.fromUri(getBaseUri())
                         .path(BRIDGES())
                         .queryParam(TENANT_ID_PARAM(), id)
                         .build();
    }

    public URI getChains() {
        return UriBuilder.fromUri(getBaseUri())
                         .path(CHAINS())
                         .queryParam(TENANT_ID_PARAM(), id)
                         .build();
    }

    public URI getPortGroups() {
        return UriBuilder.fromUri(getBaseUri())
                         .path(PORT_GROUPS())
                         .queryParam(TENANT_ID_PARAM(), id)
                         .build();
    }

    @Override
    public String toString() {
        return "Tenant { id = " + id +
               ", name = " + name +
               ", description = " + description +
               ", enabled = " + enabled +
               ", uri = " + getUri() + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, name, description, enabled);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        Tenant that = (Tenant) other;
        return Objects.equal(this.id, that.id) &&
               Objects.equal(this.name, that.name) &&
               Objects.equal(this.description, that.description) &&
               this.enabled == that.enabled;
    }
}
