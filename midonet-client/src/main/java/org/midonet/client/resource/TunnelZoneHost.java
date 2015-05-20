/*
 * Copyright 2014 Midokura SARL
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
import org.midonet.client.dto.DtoTunnelZoneHost;

public class TunnelZoneHost extends ResourceBase<TunnelZoneHost,
    DtoTunnelZoneHost> {

    public TunnelZoneHost(WebResource resource, URI uriForCreation,
                          DtoTunnelZoneHost tZoneHost, String vendorMediaType) {
        super(resource, uriForCreation, tZoneHost, vendorMediaType);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public UUID getTunnelZoneId() {
        return principalDto.getTunnelZoneId();
    }

    public UUID getHostId() {
        return principalDto.getHostId();
    }

    public String getIpAddress() {
        return principalDto.getIpAddress();
    }

    public TunnelZoneHost ipAddress(String ipAddress) {
        principalDto.setIpAddress(ipAddress);
        return this;
    }

    public TunnelZoneHost hostId(UUID hostId) {
        principalDto.setHostId(hostId);
        return this;
    }
}
