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
import org.midonet.client.dto.DtoMacPort;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class MacPort extends ResourceBase<MacPort, DtoMacPort> {

    public MacPort(WebResource resource, URI uriForCreation, DtoMacPort mp) {
        super(resource, uriForCreation, mp,
                MidonetMediaTypes.APPLICATION_MAC_PORT_JSON_V2());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public Short getVlanId() {
        return principalDto.getVlanId();
    }
    public String getMacAddr() {
        return principalDto.getMacAddr();
    }

    public UUID getPortId() {
        return principalDto.getPortId();
    }

    @Override
    public String toString() {
        // String.format("Route{id=%s}", principalDto.getId());
        return String.format("MacPort{vlanId=%d, macAddr=%s, portId=%s}",
                getVlanId(), getMacAddr(), getPortId());
    }
}
