/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoTunnelZoneHost;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 9/13/12
 * Time: 11:09 PM
 */
public class TunnelZone<T extends DtoTunnelZone>
        extends ResourceBase<TunnelZone<T>, T> {

    private String tunnelZoneHostMediaType = null;
    private String tunnelZoneHostListMediaType = null;

    public TunnelZone(WebResource resource, URI uriForCreation,
                      T tunnelZone, String tunnelZoneHostMediaType,
                                    String tunnelZoneHostListMediaType) {
        super(resource, uriForCreation, tunnelZone,
              VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON);
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

    public TunnelZone<T> name(String name) {
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
