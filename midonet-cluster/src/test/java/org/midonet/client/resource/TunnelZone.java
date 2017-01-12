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
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoTunnelZoneHost;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class TunnelZone extends ResourceBase<TunnelZone, DtoTunnelZone> {

    private String tunnelZoneHostMediaType = null;
    private String tunnelZoneHostListMediaType = null;

    public TunnelZone(WebResource resource, URI uriForCreation,
                      DtoTunnelZone tunnelZone, String tunnelZoneHostMediaType,
                                    String tunnelZoneHostListMediaType) {
        super(resource, uriForCreation, tunnelZone,
              MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_JSON());
        this.tunnelZoneHostMediaType = tunnelZoneHostMediaType;
        this.tunnelZoneHostListMediaType = tunnelZoneHostListMediaType;

    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getType() {
        return principalDto.getType();
    }

    public String getName() {
        return principalDto.getName();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public TunnelZone name(String name) {
        principalDto.setName(name);
        return this;
    }

    public ResourceCollection<TunnelZoneHost> getHosts() {
        return getChildResources(
            principalDto.getHosts(), null,
            tunnelZoneHostListMediaType,
            tunnelZoneHostMediaType,
            TunnelZoneHost.class, DtoTunnelZoneHost.class);
    }

    public TunnelZoneHost addTunnelZoneHost() {
        return new TunnelZoneHost(resource, principalDto.getHosts(),
                            new DtoTunnelZoneHost(), tunnelZoneHostMediaType);
    }
}
