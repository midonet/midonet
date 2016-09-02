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

package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;

@ZoomClass(clazz = Topology.Port.VppBinding.class)
public class VppBinding extends UriResource {

    @NotNull
    public UUID portId;

    @NotNull
    public UUID hostId;

    @ZoomField(name = "interface_name")
    public String interfaceName;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.HOSTS(), hostId,
                           ResourceUris.VPP_BINDINGS(), portId);
    }

    public URI getHost() {
        return absoluteUri(ResourceUris.HOSTS(), hostId);
    }

    public URI getPort() {
        return absoluteUri(ResourceUris.PORTS(), portId);
    }

    @JsonIgnore
    public void create(UUID hostId, UUID portId) {
        this.hostId = hostId;
        this.portId = portId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
            .add("portId", portId)
            .add("hostId", hostId)
            .add("interfaceName", interfaceName)
            .toString();
    }

}
