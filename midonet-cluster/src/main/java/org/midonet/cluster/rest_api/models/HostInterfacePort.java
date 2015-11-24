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
import java.util.UUID;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.Port.class)
public class HostInterfacePort extends UriResource {

    @NotNull
    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID portId;

    @NotNull
    @ZoomField(name = "host_id", converter = UUIDUtil.Converter.class)
    public UUID hostId;

    @NotNull
    @ZoomField(name = "interface_name")
    public String interfaceName;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.HOSTS(), hostId,
                           ResourceUris.PORTS(), portId);
    }

    public URI getHost() {
        return absoluteUri(ResourceUris.HOSTS(), hostId);
    }

    public URI getPort() {
        return absoluteUri(ResourceUris.PORTS(), portId);
    }

    @JsonIgnore
    public void create(UUID hostId) {
        this.hostId = hostId;
    }

}
