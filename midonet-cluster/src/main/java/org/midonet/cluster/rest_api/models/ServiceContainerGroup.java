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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.validation.constraints.NotNull;

import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

import static org.midonet.cluster.rest_api.ResourceUris.SERVICE_CONTAINERS;

@ZoomClass(clazz = Topology.ServiceContainerGroup.class)
public class ServiceContainerGroup extends UriResource {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "host_group_id")
    public UUID hostGroupId;

    @ZoomField(name = "port_group_id")
    public UUID portGroupId;

    @JsonIgnore
    @ZoomField(name = "service_container_ids")
    public List<UUID> serviceContainerIds;

    public ServiceContainerGroup() {
        super();
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.SERVICE_CONTAINER_GROUPS, id);
    }

    public URI getServiceContainers() {
        return relativeUri(SERVICE_CONTAINERS);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(ServiceContainerGroup that) {
        if (that != null) {
            this.serviceContainerIds = that.serviceContainerIds;
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

        ServiceContainerGroup that = (ServiceContainerGroup) o;
        return Objects.equals(this.id, that.id)
               && Objects.equals(this.hostGroupId, that.hostGroupId)
               && Objects.equals(this.portGroupId, that.portGroupId)
               && Objects.equals(this.serviceContainerIds,
                                 that.serviceContainerIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, hostGroupId, portGroupId, serviceContainerIds);
    }

    @Override
    public String toString() {
        return "ServiceContainerGroup{" +
               "id=" + id +
               ", hostGroupId=" + hostGroupId +
               ", portGroupId=" + portGroupId +
               ", serviceContainerIds=" + serviceContainerIds +
               '}';
    }
}

