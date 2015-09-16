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

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoDhcpV6Host;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class DhcpV6Host extends ResourceBase<DhcpV6Host, DtoDhcpV6Host> {

    public DhcpV6Host(WebResource resource, URI uriForCreation, DtoDhcpV6Host
        principalDto) {
        super(resource, uriForCreation, principalDto,
              MidonetMediaTypes.APPLICATION_DHCPV6_HOST_JSON());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getName() {
        return principalDto.getName();
    }

    public String getFixedAddress() {
        return principalDto.getFixedAddress();
    }

    public String getClientId() {
        return principalDto.getClientId();
    }

    public DhcpV6Host name(String name) {
        principalDto.setName(name);
        return this;
    }

    public DhcpV6Host clientId(String clientId) {
        principalDto.setClientId(clientId);
        return this;
    }

    public DhcpV6Host fixedAddress(String fixedAddress) {
        principalDto.setFixedAddress(fixedAddress);
        return this;
    }

    @Override
    public String toString() {
        return String.format("{DhcpV6Host, fixedAddress=%s, clientId=%s}", principalDto
                .getFixedAddress(), principalDto.getClientId());
    }
}


