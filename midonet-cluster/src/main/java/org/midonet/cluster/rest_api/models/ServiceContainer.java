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
import java.util.Objects;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.ws.rs.core.UriBuilder;

import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.ServiceContainer.class)
public class ServiceContainer extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "service_group_id", converter = UUIDUtil.Converter.class)
    public UUID serviceGroupId;

    @NotNull // as long as we have them only on routers
    @ZoomField(name = "router_id", converter = UUIDUtil.Converter.class)
    public UUID routerId;

    @ZoomField(name = "configuration_id", converter = UUIDUtil.Converter.class)
    public UUID configurationId;

    @ZoomField(name = "port_id", converter = UUIDUtil.Converter.class)
    public UUID portId;

    public ServiceContainer() {
        super();
    }

    @Override
    public URI getUri() {
        return
            UriBuilder.fromUri(absoluteUri(ResourceUris.ROUTERS, routerId))
                      .path(ResourceUris.SERVICE_CONTAINERS)
                      .path(id.toString())
                      .build();
    }

    public URI getDevice() {
        return absoluteUri(ResourceUris.ROUTERS, routerId);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, serviceGroupId, routerId, configurationId,
                            portId);
    }

    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null || this.getClass() != that.getClass()) {
            return false;
        }
        ServiceContainer thatSC = (ServiceContainer)that;
        return Objects.equals(this.id, thatSC.id)
               && Objects.equals(this.serviceGroupId, thatSC.serviceGroupId)
               && Objects.equals(this.routerId, thatSC.routerId)
               && Objects.equals(this.configurationId, thatSC.configurationId)
               && Objects.equals(this.portId, thatSC.portId);
    }
}
