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
package org.midonet.api.auth.rest_api;

import java.net.URI;

import com.google.common.base.Objects;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

/**
 * Class representing a tenant object in the identity service.  This is the
 * generic MidoNet representation of tenant models from various identity
 * services that it could integrate with..
 */
public class Tenant extends UriResource {

    private String id;
    private String name;

    public Tenant(){
    }

    public Tenant(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public Tenant(org.midonet.cluster.auth.Tenant tenant) {
        this.id = tenant.getId();
        this.name = tenant.getName();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public URI getUri() {
        if (getBaseUri() != null && getId() != null) {
            return ResourceUriBuilder.getTenant(getBaseUri(), getId());
        } else {
            return null;
        }
    }

    public URI getRouters() {
        if (getBaseUri() != null && getId() != null) {
            return ResourceUriBuilder.getTenantRouters(
                    getBaseUri(), getId());
        } else {
            return null;
        }
    }

    public URI getBridges() {
        if (getBaseUri() != null && getId() != null) {
            return ResourceUriBuilder.getTenantBridges(
                    getBaseUri(), getId());
        } else {
            return null;
        }
    }

    public URI getChains() {
        if (getBaseUri() != null && getId() != null) {
            return ResourceUriBuilder.getTenantChains(
                    getBaseUri(), getId());
        } else {
            return null;
        }
    }

    public URI getPortGroups() {
        if (getBaseUri() != null && getId() != null) {
            return ResourceUriBuilder.getTenantPortGroups(
                    getBaseUri(), getId());
        } else {
            return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("id=");
        sb.append(this.getId());
        sb.append(", name=");
        sb.append(this.getName());
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, name);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        Tenant otherTenant = (Tenant) other;
        if (!Objects.equal(this.getId(), otherTenant.getId())) {
            return false;
        }

        if (!Objects.equal(this.getName(), otherTenant.getName())) {
            return false;
        }

        return true;
    }
}
