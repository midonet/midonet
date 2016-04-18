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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.State;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;

@ZoomClass(clazz = Topology.ServiceContainer.class)
// Ignore unknown props. so that we don't need to create a DTO in the client
// for testing
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceContainer extends UriResource {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "service_group_id")
    public UUID serviceGroupId;

    @ZoomField(name = "configuration_id")
    public UUID configurationId;

    @ZoomField(name = "port_id")
    public UUID portId;

    @NotNull
    @ZoomField(name = "service_type")
    public String serviceType;

    public State.ContainerStatus.Code statusCode;

    public String statusMessage;

    public UUID hostId;

    public String namespaceName;

    public String interfaceName;

    public ServiceContainer() {
        super();
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.SERVICE_CONTAINERS(), id);
    }

    public URI getPort() {
        return absoluteUri(ResourceUris.PORTS(), portId);
    }

    public URI getServiceContainerGroup() {
        return absoluteUri(ResourceUris.SERVICE_CONTAINER_GROUPS(), serviceGroupId);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(ServiceContainer from) {
        id = from.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, serviceType, serviceGroupId, configurationId,
                            portId);
    }

    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null || getClass() != that.getClass()) {
            return false;
        }
        ServiceContainer sc = (ServiceContainer)that;
        return Objects.equals(id, sc.id) &&
               Objects.equals(serviceType, sc.serviceType) &&
               Objects.equals(serviceGroupId, sc.serviceGroupId) &&
               Objects.equals(configurationId, sc.configurationId) &&
               Objects.equals(portId, sc.portId) &&
               Objects.equals(statusCode, sc.statusCode) &&
               Objects.equals(statusMessage, sc.statusMessage) &&
               Objects.equals(hostId, sc.hostId) &&
               Objects.equals(namespaceName, sc.namespaceName) &&
               Objects.equals(interfaceName, sc.namespaceName);
    }
}
