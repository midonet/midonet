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

package org.midonet.client.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoHostInterfacePort;
import org.midonet.client.dto.DtoInterface;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class Host extends ResourceBase<Host, DtoHost> {

    public Host(WebResource resource, URI uriForCreation, DtoHost host) {
        super(resource, uriForCreation, host,
              MidonetMediaTypes.APPLICATION_HOST_JSON_V3());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String[] getAddresses() {
        return principalDto.getAddresses();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public String getName() {
        return principalDto.getName();
    }

    public boolean isAlive() {
        return principalDto.isAlive();
    }

    public Integer getFloodingProxyWeight() {
        return principalDto.getFloodingProxyWeight();
    }

    public Integer getContainerWeight() {
        return principalDto.getContainerWeight();
    }

    public Integer getContainerLimit() {
        return principalDto.getContainerLimit();
    }

    public Boolean getEnforceContainerLimit() {
        return principalDto.getEnforceContainerLimit();
    }

    public ResourceCollection<HostInterface> getInterfaces() {
        return getChildResources(
            principalDto.getInterfaces(), null,
            MidonetMediaTypes.APPLICATION_INTERFACE_COLLECTION_JSON(),
            HostInterface.class, DtoInterface.class);
    }

    public List<HostInterface> getHostInterfaces() {
        List<HostInterface> hostInterfaces = new ArrayList<>();
        DtoInterface[] interfaces = principalDto.getHostInterfaces();
        for (DtoInterface intf : interfaces) {
            hostInterfaces.add(new HostInterface(this.resource, null, intf));
        }

        return hostInterfaces;
    }

    public ResourceCollection<HostInterfacePort> getPorts() {
        return getChildResources(
            principalDto.getPorts(), null,
            MidonetMediaTypes.APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON(),
            HostInterfacePort.class, DtoHostInterfacePort.class);
    }

    public HostInterfacePort addHostInterfacePort() {
        return new HostInterfacePort(resource, principalDto.getPorts(),
                                     new DtoHostInterfacePort());
    }
}
