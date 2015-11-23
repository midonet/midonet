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

    @ZoomEnum(clazz = Topology.ServiceContainerGroup.Service.class)
    public enum Service {
        @ZoomEnumValue("IPSEC") IPSEC,
        @ZoomEnumValue("QUAGGA") QUAGGA,
        @ZoomEnumValue("HAPROXY") HAPROXY;
    }

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "service_type")
    public Service serviceType;

    @ZoomField(name = "host_group_id", converter = UUIDUtil.Converter.class)
    public UUID hostGroupId;

    @ZoomField(name = "port_group_id", converter = UUIDUtil.Converter.class)
    public UUID portGroupId;

    @JsonIgnore
    public List<UUID> serviceContainerIds;

    public ServiceContainerGroup() {
        super();
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.SERVICE_CONTAINER_GROUPS, id);
    }

    public List<URI> getContainers() {
        if (serviceContainerIds == null) {
            return null;
        }
        ArrayList<URI> uris = new ArrayList<>(serviceContainerIds.size());
        for (UUID scId : serviceContainerIds) {
            uris.add(absoluteUri(SERVICE_CONTAINERS, scId));
        }
        return uris;
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
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
               && Objects.equals(this.serviceType, that.serviceType)
               && Objects.equals(this.hostGroupId, that.hostGroupId)
               && Objects.equals(this.portGroupId, that.portGroupId)
               && Objects.equals(this.serviceContainerIds,
                                 that.serviceContainerIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, serviceType, hostGroupId, portGroupId,
                            serviceContainerIds);
    }
}

