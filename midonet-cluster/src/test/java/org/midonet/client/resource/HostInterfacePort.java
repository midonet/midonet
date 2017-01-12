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
import java.util.UUID;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoHostInterfacePort;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class HostInterfacePort
    extends ResourceBase<HostInterfacePort, DtoHostInterfacePort> {

    public HostInterfacePort(WebResource resource, URI uriForCreation,
                             DtoHostInterfacePort interfacePort) {
        super(resource, uriForCreation, interfacePort,
              MidonetMediaTypes.APPLICATION_HOST_INTERFACE_PORT_JSON());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public UUID getHostId() {
        return principalDto.getHostId();
    }

    public String getInterfaceName() {
        return principalDto.getInterfaceName();
    }

    public UUID getPortId() {
        return principalDto.getPortId();
    }

    public HostInterfacePort portId(UUID id) {
        principalDto.setPortId(id);
        return this;
    }

    public HostInterfacePort interfaceName(String name) {
        principalDto.setInterfaceName(name);
        return this;
    }
}
