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

import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;

import static org.midonet.cluster.rest_api.ResourceUris.SERVICE_CONTAINER_GROUPS;

@ZoomClass(clazz = Topology.ServiceContainer.class)
public class ServiceContainer extends UriResource {

    @ZoomField(name = "id")
    public UUID id;

    @NotNull
    @ZoomField(name = "service_group_id")
    public UUID serviceGroupId;

    @ZoomField(name = "configuration_id")
    public UUID configurationId;

    @ZoomField(name = "port_id")
    public UUID portId;

    public ServiceContainer() {
        super();
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.SERVICE_CONTAINERS, id);
    }

    public URI getPort() {
        return absoluteUri(ResourceUris.PORTS, portId);
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
        return Objects.hash(id, serviceGroupId, configurationId,
                            portId);
    }

    public URI getServiceContainerGroup() {
        return absoluteUri(SERVICE_CONTAINER_GROUPS,
                           serviceGroupId);
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
               && Objects.equals(this.configurationId, thatSC.configurationId)
               && Objects.equals(this.portId, thatSC.portId);
    }
}
