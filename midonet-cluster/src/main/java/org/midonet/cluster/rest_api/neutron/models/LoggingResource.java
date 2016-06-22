/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.rest_api.neutron.models;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;


@ZoomClass(clazz = Neutron.NeutronLoggingResource.class)
public class LoggingResource extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "enabled")
    public boolean enabled;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof LoggingResource)) return false;
        final LoggingResource other = (LoggingResource) obj;

        return Objects.equal(id, other.id)
               && Objects.equal(name, other.name)
               && Objects.equal(description, other.description)
               && Objects.equal(tenantId, other.tenantId)
               && Objects.equal(enabled, other.enabled);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, name, description, tenantId, enabled);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("id", id)
            .add("name", name)
            .add("description", description)
            .add("tenantId", tenantId)
            .add("enabled", enabled)
            .toString();
    }
}
