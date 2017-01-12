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
import org.midonet.client.dto.DtoDhcpHost;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class DhcpHost extends ResourceBase<DhcpHost, DtoDhcpHost> {

    public DhcpHost(WebResource resource, URI uriForCreation, DtoDhcpHost
        principalDto) {
        super(resource, uriForCreation, principalDto,
                MidonetMediaTypes.APPLICATION_DHCP_HOST_JSON_V2());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getName() {
        return principalDto.getName();
    }

    public String getIpAddr() {
        return principalDto.getIpAddr();
    }

    public String getMacAddr() {
        return principalDto.getMacAddr();
    }

    public DhcpHost name(String name) {
        principalDto.setName(name);
        return this;
    }

    public DhcpHost macAddr(String macAddr) {
        principalDto.setMacAddr(macAddr);
        return this;
    }

    public DhcpHost ipAddr(String ipAddr) {
        principalDto.setIpAddr(ipAddr);
        return this;
    }

    @Override
    public String toString() {
        return String.format("{DhcpHost, ip=%s, mac=%s}", principalDto
                .getIpAddr(), principalDto.getMacAddr());
    }
}


