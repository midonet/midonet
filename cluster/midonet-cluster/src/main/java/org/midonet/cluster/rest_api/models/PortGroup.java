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
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.PortGroup.class)
public class PortGroup extends UriResource {

    public static final int MIN_PORT_GROUP_NAME_LEN = 1;
    public static final int MAX_PORT_GROUP_NAME_LEN = 255;

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @NotNull
    @Size(min = MIN_PORT_GROUP_NAME_LEN, max = MAX_PORT_GROUP_NAME_LEN)
    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "stateful")
    public boolean stateful;

    @JsonIgnore
    @ZoomField(name = "port_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> portIds;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.PORT_GROUPS, id);
    }

    public URI getPorts() {
        return relativeUri(ResourceUris.PORTS);
    }

    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(PortGroup from) {
        id = from.id;
        portIds = from.portIds;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("tenantId", tenantId)
            .add("name", name)
            .add("stateful", stateful)
            .add("portIds", portIds)
            .toString();
    }
}

