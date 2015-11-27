/*
 * Copyright 2015 Midokura SARL
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.HostGroup.class)
public class HostGroup extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "name")
    public String name;

    @JsonIgnore
    @ZoomField(name = "host_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> hostIds;

    @JsonIgnore
    @ZoomField(name = "service_group_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> serviceGroupIds;

    public HostGroup() {
        super();
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.HOST_GROUPS, id);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(HostGroup that) {
        if (that != null) {
            this.name = that.name;
            this.hostIds = that.hostIds;
            this.serviceGroupIds = that.serviceGroupIds;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        HostGroup that = (HostGroup) o;
        return Objects.equals(this.id, that.id)
               && Objects.equals(this.name, that.name)
               && Objects.equals(this.hostIds, that.hostIds)
               && Objects.equals(this.serviceGroupIds, that.serviceGroupIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, hostIds, serviceGroupIds);
    }
}

